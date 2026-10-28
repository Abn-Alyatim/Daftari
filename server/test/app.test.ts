import { describe, it, expect, beforeEach } from "vitest";
import { buildApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import { MemoryStore } from "../src/store.js";
import { makeOtp } from "../src/otp.js";
import { Ai, chatSystemPrompt, parseJsonLoose } from "../src/ai.js";
import { normalizePhone, signToken, verifyToken } from "../src/auth.js";

function setup(env: Record<string, string> = {}, fakeFetch?: typeof fetch) {
  const cfg = loadConfig({ AUTH_SECRET: "test-secret", ADMIN_TOKEN: "adm", ...env });
  const store = new MemoryStore();
  const f = fakeFetch ?? (async () => new Response("{}", { status: 500 }));
  const app = buildApp({ cfg, store, otp: makeOtp(cfg, f), ai: new Ai(cfg, f), fetch: f });
  return { app, store, cfg };
}

async function login(app: ReturnType<typeof setup>["app"], phone = "777123456") {
  const r1 = await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone, channel: "whatsapp" } });
  const code = r1.json().devCode;
  const r2 = await app.inject({ method: "POST", url: "/api/v1/auth/otp/verify", payload: { phone, code, role: "SHOP_OWNER" } });
  return r2.json().token as string;
}

describe("info + health", () => {
  it("reports pending config honestly", async () => {
    const { app } = setup();
    const r = await app.inject({ url: "/api/v1/info" });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toMatchObject({ otpProvider: "none", otpReady: false, aiReady: false });
    expect((await app.inject({ url: "/health" })).json().ok).toBe(true);
  });
  it("whatsapp_cloud without token is not ready", async () => {
    const { app } = setup({ OTP_PROVIDER: "whatsapp_cloud" });
    const r = await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone: "777123456" } });
    expect(r.statusCode).toBe(503);
    expect(r.json().error.message).toContain("قيد التجهيز");
  });
});

describe("phone OTP", () => {
  it("normalizes Yemeni numbers", () => {
    expect(normalizePhone("777123456")).toBe("+967777123456");
    expect(normalizePhone("٧٧٧١٢٣٤٥٦")).toBe("+967777123456");
    expect(normalizePhone("+966501234567")).toBe("+966501234567");
    expect(normalizePhone("12")).toBeNull();
  });
  it("dev provider: request → verify → token", async () => {
    const { app } = setup({ OTP_PROVIDER: "dev" });
    const token = await login(app);
    expect(token).toBeTruthy();
    expect(verifyToken("test-secret", token)).toBeTruthy();
  });
  it("wrong code is rejected and limited", async () => {
    const { app } = setup({ OTP_PROVIDER: "dev" });
    await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone: "777000111" } });
    for (let i = 0; i < 5; i++) {
      const r = await app.inject({ method: "POST", url: "/api/v1/auth/otp/verify", payload: { phone: "777000111", code: "000000x" } });
      expect(r.statusCode).toBe(400);
    }
    const r = await app.inject({ method: "POST", url: "/api/v1/auth/otp/verify", payload: { phone: "777000111", code: "1" } });
    expect(r.statusCode).toBe(429);
  });
  it("resend too soon → 429", async () => {
    const { app } = setup({ OTP_PROVIDER: "dev" });
    await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone: "777000222" } });
    const r = await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone: "777000222" } });
    expect(r.statusCode).toBe(429);
  });
  it("whatsapp cloud posts to Graph with the token", async () => {
    const calls: any[] = [];
    const f = (async (url: any, init: any) => { calls.push({ url: String(url), init }); return new Response("{}", { status: 200 }); }) as typeof fetch;
    const { app } = setup({ OTP_PROVIDER: "whatsapp_cloud", WHATSAPP_TOKEN: "T", WHATSAPP_PHONE_ID: "123" }, f);
    const r = await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone: "777123456" } });
    expect(r.statusCode).toBe(200);
    expect(r.json().devCode).toBeUndefined();
    expect(calls[0].url).toContain("graph.facebook.com/v21.0/123/messages");
    expect(calls[0].init.headers.Authorization).toBe("Bearer T");
  });
});

describe("token", () => {
  it("rejects tampered tokens", () => {
    const t = signToken("s", "u1");
    expect(verifyToken("s", t)).toBe("u1");
    expect(verifyToken("other", t)).toBeNull();
    expect(verifyToken("s", t.slice(0, -2) + "xx")).toBeNull();
  });
});

describe("«مدين» chat", () => {
  it("not configured → الخدمة قيد التجهيز", async () => {
    const { app } = setup();
    const r = await app.inject({ method: "POST", url: "/api/v1/chat", payload: { messages: [{ role: "user", content: "كم عليه أحمد؟" }], context: {} } });
    expect(r.statusCode).toBe(503);
    expect(r.json().error.message).toBe("الخدمة قيد التجهيز");
  });
  it("Groq answer is parsed into reply/table/action", async () => {
    const f = (async (url: any) => {
      if (String(url).includes("groq")) return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ reply: "عليه 5,000 YER", table: { title: "t", columns: ["a"], rows: [["1"]] }, action: null }) } }] }), { status: 200 });
      return new Response("{}", { status: 500 });
    }) as typeof fetch;
    const { app } = setup({ GROQ_API_KEY: "g" }, f);
    const r = await app.inject({ method: "POST", url: "/api/v1/chat", payload: { messages: [{ role: "user", content: "كم عليه؟" }], context: { parties: [] } } });
    expect(r.statusCode).toBe(200);
    expect(r.json().reply).toContain("5,000");
    expect(r.json().table.columns).toEqual(["a"]);
  });
  it("falls back to Gemini when Groq fails", async () => {
    const f = (async (url: any) => {
      if (String(url).includes("generativelanguage")) return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: '{"reply":"من جيميني"}' }] } }] }), { status: 200 });
      return new Response("{}", { status: 500 });
    }) as typeof fetch;
    const { app } = setup({ GROQ_API_KEY: "g", GEMINI_API_KEY: "m" }, f);
    const r = await app.inject({ method: "POST", url: "/api/v1/chat", payload: { messages: [{ role: "user", content: "مرحبا" }], context: {} } });
    expect(r.json().reply).toBe("من جيميني");
  });
  it("daily limit per anonymous device", async () => {
    const f = (async () => new Response(JSON.stringify({ choices: [{ message: { content: '{"reply":"ok"}' } }] }), { status: 200 })) as typeof fetch;
    const { app } = setup({ GROQ_API_KEY: "g", ANON_DAILY_LIMIT: "2" }, f);
    const p = { messages: [{ role: "user", content: "x" }], context: {} };
    expect((await app.inject({ method: "POST", url: "/api/v1/chat", payload: p })).statusCode).toBe(200);
    expect((await app.inject({ method: "POST", url: "/api/v1/chat", payload: p })).statusCode).toBe(200);
    expect((await app.inject({ method: "POST", url: "/api/v1/chat", payload: p })).statusCode).toBe(429);
  });
  it("system prompt restricts scope and keeps the name", () => {
    const s = chatSystemPrompt({ a: 1 });
    expect(s).toContain("«مدين»");
    expect(s).toContain("خارج بيانات التطبيق");
  });
  it("parses fenced / loose JSON", () => {
    expect(parseJsonLoose("```json\n{\"reply\":\"a\"}\n```").reply).toBe("a");
    expect(parseJsonLoose("نص فقط").reply).toBe("نص فقط");
  });
});

describe("smart import", () => {
  it("returns cleaned rows", async () => {
    const f = (async () => new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ businessName: "سوق الكحلاني", rows: [{ name: "أيوب العبدلي", shopNumber: "1", amount: "2000", arrears: 0 }, { name: "" }] }) } }] }), { status: 200 })) as typeof fetch;
    const { app } = setup({ GROQ_API_KEY: "g" }, f);
    const r = await app.inject({ method: "POST", url: "/api/v1/import", payload: { fileName: "x.docx", text: "أيوب العبدلي 2000", role: "LANDLORD" } });
    expect(r.statusCode).toBe(200);
    expect(r.json().rows).toHaveLength(1);
    expect(r.json().rows[0].amount).toBe(2000);
  });
});

describe("backup + Pro codes", () => {
  let ctx: ReturnType<typeof setup>;
  beforeEach(() => { ctx = setup({ OTP_PROVIDER: "dev" }); });
  it("backup needs login and round-trips", async () => {
    expect((await ctx.app.inject({ method: "POST", url: "/api/v1/backup", payload: { a: 1 } })).statusCode).toBe(401);
    const token = await login(ctx.app);
    const h = { authorization: `Bearer ${token}` };
    expect((await ctx.app.inject({ method: "POST", url: "/api/v1/backup", payload: { parties: [1, 2] }, headers: h })).statusCode).toBe(200);
    const r = await ctx.app.inject({ url: "/api/v1/backup/latest", headers: h });
    expect(r.json().snapshot.parties).toEqual([1, 2]);
  });
  it("admin code activates Pro once", async () => {
    expect((await ctx.app.inject({ method: "POST", url: "/api/v1/admin/codes", payload: { months: 1 } })).statusCode).toBe(403);
    const c = await ctx.app.inject({ method: "POST", url: "/api/v1/admin/codes", payload: { months: 6, count: 2 }, headers: { "x-admin-token": "adm" } });
    const code = c.json().codes[0];
    const token = await login(ctx.app, "777999888");
    const h = { authorization: `Bearer ${token}` };
    const r = await ctx.app.inject({ method: "POST", url: "/api/v1/plan/activate", payload: { code }, headers: h });
    expect(r.statusCode).toBe(200);
    expect(r.json().proUntil).toBeGreaterThan(Date.now() + 170 * 864e5);
    expect((await ctx.app.inject({ method: "POST", url: "/api/v1/plan/activate", payload: { code }, headers: h })).statusCode).toBe(400);
  });
});
