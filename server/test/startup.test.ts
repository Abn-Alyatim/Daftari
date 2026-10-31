/**
 * 1.2.1 deploy robustness: PORT/HOST/DATABASE_URL parsing, the port opens before the DB,
 * DB routes wait or answer 503, retries until the DB comes up, and a dropped idle connection does not kill the process.
 */
import { describe, it, expect } from "vitest";
import { buildApp } from "../src/app.js";
import { loadConfig, parsePort, cleanDatabaseUrl } from "../src/config.js";
import { MemoryStore, PgStore } from "../src/store.js";
import { makeOtp } from "../src/otp.js";
import { Ai } from "../src/ai.js";
import { makeDbStarter, dbErrorWord } from "../src/startup.js";

describe("config for Docker hosts", () => {
  it("PORT: unset / empty / junk → 8080, a real one is kept", () => {
    expect(parsePort(undefined)).toBe(8080);
    expect(parsePort("")).toBe(8080);
    expect(parsePort(" 3000 ")).toBe(3000);
    expect(parsePort("abc")).toBe(8080);
    expect(parsePort("70000")).toBe(8080);
    expect(loadConfig({ PORT: "" }).port).toBe(8080);
    expect(loadConfig({ PORT: "10000" }).port).toBe(10000);
  });
  it("HOST empty → 0.0.0.0", () => {
    expect(loadConfig({}).host).toBe("0.0.0.0");
    expect(loadConfig({ HOST: " " }).host).toBe("0.0.0.0");
  });
  it("DATABASE_URL pasted with psql / quotes / spaces is cleaned", () => {
    const u = "postgresql://neondb_owner:pw@ep-x-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require";
    expect(cleanDatabaseUrl(`psql '${u}'`)).toBe(u);
    expect(cleanDatabaseUrl(`  "${u}"\n`)).toBe(u);
    expect(cleanDatabaseUrl(u)).toBe(u);
    expect(cleanDatabaseUrl(undefined)).toBe("");
    expect(loadConfig({ DATABASE_URL: `psql '${u}'` }).databaseUrl).toBe(u);
  });
});

class FlakyStore extends MemoryStore {
  fails: number; calls = 0;
  constructor(fails: number) { super(); this.fails = fails; }
  async init() { this.calls++; if (this.calls <= this.fails) throw Object.assign(new Error("connect ECONNREFUSED 1.2.3.4:5432"), { code: "ECONNREFUSED" }); }
}

function appWith(store: MemoryStore, delays: number[]) {
  const cfg = loadConfig({ AUTH_SECRET: "s", ADMIN_TOKEN: "adm-0123456789abcdef" });
  const db = makeDbStarter(store, "postgres", { log: () => {}, delaysMs: delays });
  const app = buildApp({ cfg, store, otp: makeOtp(cfg), ai: new Ai(cfg), db });
  return { app, db };
}

describe("startup without a ready database", () => {
  it("health answers at once with dbReady=false; DB routes answer 503; info still works", async () => {
    const store = new FlakyStore(1_000_000);
    const { app, db } = appWith(store, [5]);
    void db.start();
    await new Promise((r) => setTimeout(r, 20));
    const h = await app.inject({ url: "/health" });
    expect(h.statusCode).toBe(200);
    expect(h.json()).toMatchObject({ ok: true, version: "1.2.1", db: "postgres", dbReady: false, dbError: "unreachable" });
    expect((await app.inject({ url: "/api/v1/info" })).statusCode).toBe(200);
    db.waitReady = async () => false; // skip the 10 s wait in the test
    const r = await app.inject({ url: "/api/v1/subscription/config" });
    expect(r.statusCode).toBe(503);
    expect(r.json().error.code).toBe("db_unavailable");
    db.stop();
  });
  it("retries until the database is up, then routes work", async () => {
    const store = new FlakyStore(3);
    const { app, db } = appWith(store, [5, 5, 5]);
    await db.start();
    expect(store.calls).toBe(4);
    expect(db.isReady()).toBe(true);
    expect((await app.inject({ url: "/health" })).json()).toMatchObject({ dbReady: true });
    expect((await app.inject({ url: "/api/v1/info" })).statusCode).toBe(200);
  });
  it("a request that arrives while connecting waits for the database instead of failing", async () => {
    const store = new FlakyStore(2);
    const { app, db } = appWith(store, [30, 30]);
    void db.start();
    const res = await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone: "bad" } });
    expect(res.statusCode).toBe(400); // reached the route (bad phone), not 503
  });
  it("error words never carry the password", () => {
    expect(dbErrorWord(Object.assign(new Error("password authentication failed for user 'neondb_owner'"), { code: "28P01" }))).toBe("auth_failed");
    expect(dbErrorWord(Object.assign(new Error("getaddrinfo ENOTFOUND ep-x"), { code: "ENOTFOUND" }))).toBe("host_not_found");
    expect(dbErrorWord(new Error("Connection terminated due to connection timeout"))).toBe("timeout");
  });
});

describe("PgStore", () => {
  it("listens for pool errors (an idle connection closed by Neon must not crash the server)", async () => {
    const s = new PgStore("postgresql://u:p@127.0.0.1:1/x");
    const pool = (s as unknown as { pool: { listenerCount(e: string): number; emit(e: string, x: unknown): boolean } }).pool;
    expect(pool.listenerCount("error")).toBeGreaterThan(0);
    expect(() => pool.emit("error", new Error("Connection terminated unexpectedly"))).not.toThrow();
    await s.close();
  });
});
