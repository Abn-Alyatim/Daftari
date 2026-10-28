import { loadConfig } from "./config.js";
import { MemoryStore, PgStore } from "./store.js";
import { makeOtp } from "./otp.js";
import { Ai } from "./ai.js";
import { buildApp } from "./app.js";

const cfg = loadConfig();
const store = cfg.databaseUrl ? new PgStore(cfg.databaseUrl) : new MemoryStore();
await store.init();
const app = buildApp({ cfg, store, otp: makeOtp(cfg), ai: new Ai(cfg) });
await app.listen({ port: cfg.port, host: cfg.host });
console.log(`daftari server ${cfg.version} on ${cfg.host}:${cfg.port} · db=${cfg.databaseUrl ? "postgres" : "memory"} · otp=${cfg.otpProvider} · ai=${cfg.groqKey || cfg.geminiKey ? "on" : "pending"}`);
