import { loadConfig } from "./config.js";
import { MemoryStore, PgStore, type Store } from "./store.js";
import { makeOtp } from "./otp.js";
import { Ai } from "./ai.js";
import { buildApp } from "./app.js";
import { makeDbStarter } from "./startup.js";

// 1.2.1: log instead of dying on a stray promise rejection (a crash = a restart loop on the host)
process.on("unhandledRejection", (e) => console.error("[unhandledRejection]", e));

const cfg = loadConfig();
const store: Store = cfg.databaseUrl ? new PgStore(cfg.databaseUrl) : new MemoryStore();
const db = makeDbStarter(store, cfg.databaseUrl ? "postgres" : "memory");
const app = buildApp({ cfg, store, otp: makeOtp(cfg), ai: new Ai(cfg), db });

// 1.2.1: open the port first, then migrate the database in the background (with retries)
await app.listen({ port: cfg.port, host: cfg.host });
console.log(`daftari server ${cfg.version} on ${cfg.host}:${cfg.port} · db=${cfg.databaseUrl ? "postgres" : "memory"} · otp=${cfg.otpProvider} · ai=${cfg.groqKey || cfg.geminiKey ? "on" : "pending"}`);
if (!cfg.databaseUrl) console.warn("[db] DATABASE_URL is not set — data lives in memory and is lost on every redeploy");
void db.start();

// 1.2.1: a clean stop when the host redeploys (SIGTERM) instead of a killed process
let closing = false;
for (const sig of ["SIGTERM", "SIGINT"] as const) {
  process.on(sig, async () => {
    if (closing) return;
    closing = true;
    console.log(`[${sig}] shutting down`);
    db.stop();
    const force = setTimeout(() => process.exit(0), 8_000);
    force.unref();
    try { await app.close(); await store.close?.(); } catch (e) { console.error("[shutdown]", e); }
    process.exit(0);
  });
}
