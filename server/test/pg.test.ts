import { describe, it, expect } from "vitest";
import { buildApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import { PgStore } from "../src/store.js";
import { makeOtp } from "../src/otp.js";
import { Ai } from "../src/ai.js";

/** Same flow on real Postgres: PG_TEST_URL=postgres://dftest:dftest@localhost/df_subs_test npx vitest run */
const url = process.env.PG_TEST_URL;
const ADMIN = "adm-0123456789abcdef";
const H = { "x-admin-token": ADMIN };
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(5000, 9)]);

describe.skipIf(!url)("postgres store", () => {
  it("migrates twice, stores receipt bytes, approves, expiring", async () => {
    const store = new PgStore(url!);
    await store.init(); await store.init();
    const cfg = loadConfig({ AUTH_SECRET: "s", ADMIN_TOKEN: ADMIN, GOOGLE_CLIENT_ID: "web" });
    const email = `u${Date.now()}@gmail.com`;
    const f = (async () => new Response(JSON.stringify({ aud: "web", email, name: "U" }))) as unknown as typeof fetch;
    const app = buildApp({ cfg, store, otp: makeOtp(cfg, f), ai: new Ai(cfg, f), fetch: f });
    const tok = (await app.inject({ method: "POST", url: "/api/v1/auth/google", payload: { idToken: "anything-long" } })).json().token;
    const auth = { authorization: "Bearer " + tok };
    const c = (await app.inject({ url: "/api/v1/admin/sub/config", headers: H })).json();
    c.plans[0].price = 2500;
    expect((await app.inject({ method: "PUT", url: "/api/v1/admin/sub/config", headers: H, payload: c })).statusCode).toBe(200);
    const r = await app.inject({ method: "POST", url: "/api/v1/subscription/requests", headers: auth, payload: { planId: "pro_month", methodId: "jaib", txnNo: "998877", payerName: "علي محمد صالح أحمد", receipt: JPEG.toString("base64") } });
    expect(r.statusCode).toBe(201);
    const id = r.json().request.id;
    const rc = await app.inject({ url: `/api/v1/admin/sub/requests/${id}/receipt`, headers: H });
    expect(Buffer.compare(rc.rawPayload, JPEG)).toBe(0);
    const ap = await app.inject({ method: "POST", url: `/api/v1/admin/sub/requests/${id}/approve`, headers: H, payload: { by: "panel" } });
    expect(ap.statusCode).toBe(200);
    const st = (await app.inject({ url: "/api/v1/subscription/status", headers: auth })).json();
    expect(st.plan).toMatchObject({ name: "برو شهري", active: true });
    expect(st.notice).toMatchObject({ id, status: "approved", payerName: "علي محمد صالح أحمد" });
    const ex = (await app.inject({ url: "/api/v1/admin/sub/expiring?days=31", headers: H })).json().items;
    expect(ex.some((x: any) => x.email === email)).toBe(true);
    await app.inject({ method: "POST", url: "/api/v1/admin/maintenance", headers: H, payload: { on: true, message: "m" } });
    expect((await app.inject({ url: "/api/v1/app/status" })).json().maintenance.on).toBe(true);
    await app.inject({ method: "POST", url: "/api/v1/admin/maintenance", headers: H, payload: { on: false } });
    await app.close();
  });
});
