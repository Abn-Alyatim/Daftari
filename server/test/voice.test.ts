/*
 * دفتري · server/test/voice.test.ts
 * اختبارات مسار الصوت (1.2.0): بلا مفتاح ← 503، WAV خام ومتعدد الأجزاء ← نص، المفتاح الثاني عند 429، الحجم والمدة، وحدّ الساعة.
 * Voice route tests with a mocked Groq (no real key, no network).
 */
import { describe, it, expect } from "vitest";
import { buildApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import { MemoryStore } from "../src/store.js";
import { makeOtp } from "../src/otp.js";
import { Ai } from "../src/ai.js";
import { firstFilePart, wavSeconds } from "../src/voice.js";

function wav(seconds: number): Buffer {
  const rate = 16000, n = Math.round(rate * seconds), b = Buffer.alloc(44 + n * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + n * 2, 4); b.write("WAVE", 8); b.write("fmt ", 12); b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write("data", 36); b.writeUInt32LE(n * 2, 40);
  return b;
}

function setup(env: Record<string, string>, answers: Array<() => Response>) {
  const calls: Array<{ url: string; auth: string; form: FormData | null }> = [];
  const f = (async (url: any, init?: any) => {
    calls.push({ url: String(url), auth: init?.headers?.Authorization ?? "", form: init?.body instanceof FormData ? init.body : null });
    const next = answers.shift();
    return next ? next() : new Response("{}", { status: 500 });
  }) as typeof fetch;
  const cfg = loadConfig({ AUTH_SECRET: "s", ADMIN_TOKEN: "a", ...env });
  const app = buildApp({ cfg, store: new MemoryStore(), otp: makeOtp(cfg, f), ai: new Ai(cfg, f), fetch: f });
  return { app, calls };
}

const ok = (text: string) => () => new Response(JSON.stringify({ text }), { status: 200, headers: { "content-type": "application/json" } });

describe("voice transcribe", () => {
  it("is off without a Groq key, and info says so", async () => {
    const { app, calls } = setup({}, []);
    const r = await app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "audio/wav" }, payload: wav(2) });
    expect(r.statusCode).toBe(503);
    expect(r.json().error.code).toBe("voice_disabled");
    expect((await app.inject({ url: "/api/v1/info" })).json().voiceReady).toBe(false);
    expect(calls.length).toBe(0);
  });

  it("raw WAV → Groq whisper-large-v3 in Arabic → { text }", async () => {
    const { app, calls } = setup({ GROQ_API_KEY: "k1" }, [ok(" سجل على محمد خمسة آلاف ")]);
    const r = await app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "audio/wav" }, payload: wav(3) });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ text: "سجل على محمد خمسة آلاف" });
    expect(calls[0].url).toBe("https://api.groq.com/openai/v1/audio/transcriptions");
    expect(calls[0].auth).toBe("Bearer k1");
    expect(calls[0].form?.get("model")).toBe("whisper-large-v3");
    expect(calls[0].form?.get("language")).toBe("ar");
    expect((calls[0].form?.get("file") as File).size).toBe(wav(3).length);
    expect((await app.inject({ url: "/api/v1/info" })).json().voiceReady).toBe(true);
  });

  it("multipart with a file part works too", async () => {
    const { app } = setup({ GROQ_API_KEY: "k1" }, [ok("سدد علي ألفين")]);
    const audio = wav(1);
    const body = Buffer.concat([
      Buffer.from('--XyZ\r\nContent-Disposition: form-data; name="lang"\r\n\r\nar\r\n'),
      Buffer.from('--XyZ\r\nContent-Disposition: form-data; name="file"; filename="v.wav"\r\nContent-Type: audio/wav\r\n\r\n'), audio,
      Buffer.from("\r\n--XyZ--\r\n"),
    ]);
    const r = await app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "multipart/form-data; boundary=XyZ" }, payload: body });
    expect(r.statusCode).toBe(200);
    expect(r.json().text).toBe("سدد علي ألفين");
    const part = firstFilePart(body, "multipart/form-data; boundary=XyZ")!;
    expect(part.name).toBe("v.wav"); expect(part.data.equals(audio)).toBe(true);
  });

  it("second key on 429; both busy → 429", async () => {
    const busy = () => new Response("{}", { status: 429 });
    const a = setup({ GROQ_API_KEY: "k1", GROQ_API_KEY_2: "k2" }, [busy, ok("نص")]);
    const r = await a.app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "audio/wav" }, payload: wav(1) });
    expect(r.json().text).toBe("نص");
    expect(a.calls.map((c) => c.auth)).toEqual(["Bearer k1", "Bearer k2"]);
    const b = setup({ GROQ_API_KEY: "k1" }, [busy]);
    expect((await b.app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "audio/wav" }, payload: wav(1) })).statusCode).toBe(429);
    const c = setup({ GROQ_API_KEY: "k1" }, [() => new Response("x", { status: 500 })]);
    expect((await c.app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "audio/wav" }, payload: wav(1) })).statusCode).toBe(502);
  });

  it("rejects empty, too long and too big audio", async () => {
    const { app, calls } = setup({ GROQ_API_KEY: "k1" }, []);
    const post = (payload: Buffer) => app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "audio/wav" }, payload });
    expect((await post(Buffer.alloc(0))).statusCode).toBe(400);
    expect((await post(wav(20))).statusCode).toBe(413);          // 20 s
    expect((await post(Buffer.alloc(2 * 1024 * 1024))).statusCode).toBe(413); // > 1 MB
    expect(calls.length).toBe(0);
    expect(wavSeconds(wav(2.5))).toBeCloseTo(2.5, 2);
    expect(wavSeconds(Buffer.from("not a wav"))).toBeNull();
  });

  it("limits requests per IP per hour when not signed in", async () => {
    const answers = Array.from({ length: 5 }, () => ok("x"));
    const { app } = setup({ GROQ_API_KEY: "k1", ANON_VOICE_HOURLY_LIMIT: "3" }, answers);
    const codes: number[] = [];
    for (let i = 0; i < 4; i++) codes.push((await app.inject({ method: "POST", url: "/api/v1/voice/transcribe", headers: { "content-type": "audio/wav" }, payload: wav(1) })).statusCode);
    expect(codes).toEqual([200, 200, 200, 429]);
  });
});
