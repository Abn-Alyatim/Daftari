// Daftari server 1.2.1 — startup that survives a slow or unreachable database.
// Before 1.2.1 main.ts awaited store.init() before listen(): a wrong DATABASE_URL, a Neon compute waking up,
// or a network blip made the process exit before the port opened, so the host's health check never passed
// and the container restarted in a loop. Now the port opens first and the database is migrated in the
// background with retries; /health reports dbReady and DB routes wait for it (or answer 503).
import type { Store } from "./store.js";
import type { DbGate } from "./app.js";

/** A short, password-free word for /health (the full message goes to the log only). */
export function dbErrorWord(e: unknown): string {
  const x = e as { code?: string; message?: string };
  const m = String(x?.message ?? e ?? "").toLowerCase();
  if (x?.code === "28P01" || m.includes("password authentication")) return "auth_failed";
  if (x?.code === "3D000" || m.includes("does not exist")) return "database_not_found";
  if (x?.code === "ENOTFOUND" || x?.code === "EAI_AGAIN") return "host_not_found";
  if (m.includes("timeout") || m.includes("timed out")) return "timeout";
  if (x?.code === "ECONNREFUSED" || x?.code === "ECONNRESET" || m.includes("terminated")) return "unreachable";
  if (m.includes("invalid url") || m.includes("invalid connection string")) return "bad_url";
  if (m.includes("ssl") || m.includes("certificate")) return "ssl";
  return "error";
}

export interface DbStarter extends DbGate { start(): Promise<void>; stop(): void }

export function makeDbStarter(store: Store, kind: "postgres" | "memory", opts: { log?: (s: string) => void; delaysMs?: number[] } = {}): DbStarter {
  const log = opts.log ?? ((s: string) => console.log(s));
  const delays = opts.delaysMs ?? [1_000, 2_000, 5_000, 10_000, 20_000, 30_000];
  let ready = false, lastError = "", stopped = false, attempt = 0;
  let wake: Array<() => void> = [];
  const markReady = () => { ready = true; lastError = ""; for (const w of wake) w(); wake = []; };
  return {
    kind,
    isReady: () => ready,
    lastError: () => lastError,
    waitReady(ms: number) {
      if (ready) return Promise.resolve(true);
      return new Promise<boolean>((res) => {
        const t = setTimeout(() => res(ready), ms);
        wake.push(() => { clearTimeout(t); res(true); });
      });
    },
    stop() { stopped = true; },
    async start() {
      while (!stopped) {
        attempt++;
        try {
          await store.init();
          markReady();
          log(`[db] ${kind} ready${attempt > 1 ? ` after ${attempt} attempts` : ""}`);
          return;
        } catch (e) {
          lastError = dbErrorWord(e);
          const wait = delays[Math.min(attempt - 1, delays.length - 1)];
          log(`[db] ${kind} not ready (attempt ${attempt}, ${lastError}): ${(e as Error)?.message || (e as { code?: string })?.code || e} — retrying in ${Math.round(wait / 1000)} s`);
          await new Promise((r) => setTimeout(r, wait));
        }
      }
    },
  };
}
