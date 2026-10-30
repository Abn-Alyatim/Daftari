import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";
import { randomUUID, randomBytes } from "node:crypto";
import { z } from "zod";
import type { Config } from "./config.js";
import type { Store, User } from "./store.js";
import { OtpError, type Fetch, type OtpRouter } from "./otp.js";
import { Ai, AiError, NOT_READY, chatSystemPrompt, importSystemPrompt, parseJsonLoose } from "./ai.js";
import { hashCode, newOtp, normalizePhone, signToken, verifyToken } from "./auth.js";
import { isAdmin, planOf, registerSubs } from "./subs.js";
import { registerVoice } from "./voice.js";

export interface Deps { cfg: Config; store: Store; otp: OtpRouter; ai: Ai; fetch?: Fetch; now?: () => number }

const err = (code: string, message: string) => ({ error: { code, message } });
const today = (now: number) => new Date(now + 3 * 3600_000).toISOString().slice(0, 10); // Asia/Aden day

export function buildApp(d: Deps): FastifyInstance {
  const { cfg, store, otp, ai } = d;
  const now = d.now ?? Date.now;
  const f = d.fetch ?? fetch;
  const app = Fastify({ logger: false, bodyLimit: 12 * 1024 * 1024 });

  async function userOf(req: FastifyRequest): Promise<User | null> {
    const h = req.headers.authorization ?? "";
    const id = h.startsWith("Bearer ") ? verifyToken(cfg.authSecret, h.slice(7)) : null;
    return id ? store.userById(id) : null;
  }
  const auth = (u: User) => ({ token: signToken(cfg.authSecret, u.id), user: { id: u.id, phone: u.phone, email: u.email, name: u.name, role: u.role }, proUntil: u.proUntil, plan: planOf(u, now()) });

  app.get("/health", async () => ({ ok: true, name: "daftari", version: cfg.version }));
  app.get("/api/v1/info", async () => ({
    name: "daftari", version: cfg.version, otpProvider: otp.name, otpChannels: otp.channels, otpReady: otp.ready,
    googleClientId: cfg.googleClientId, aiReady: ai.ready, voiceReady: !!(cfg.groqKey || cfg.groqKey2),
  }));

  // ---------------- OTP ----------------
  app.post("/api/v1/auth/otp/request", async (req, reply) => {
    const b = z.object({ phone: z.string(), channel: z.string().default("whatsapp") }).safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "طلب غير صالح"));
    const phone = normalizePhone(b.data.phone);
    if (!phone) return reply.code(400).send(err("bad_phone", "رقم الجوال غير صحيح"));
    const prev = await store.getOtp(phone);
    if (prev && now() - prev.sentAt < 60_000) return reply.code(429).send(err("too_soon", "انتظر دقيقة قبل طلب رمز جديد"));
    if ((await store.bump(`otp:${phone}:${today(now())}`)) > 6) return reply.code(429).send(err("too_many", "طلبات كثيرة اليوم، جرّب بكرة"));
    const code = newOtp();
    try {
      const channel = await otp.send(phone, code, b.data.channel);
      await store.setOtp({ phone, codeHash: hashCode(cfg.authSecret, phone, code), expiresAt: now() + 10 * 60_000, attempts: 0, sentAt: now() });
      return { ok: true, channel, retryAfterSec: 60, devCode: otp.isDev ? code : undefined };
    } catch (e) {
      if (e instanceof OtpError) return reply.code(e.code === "not_configured" ? 503 : 502).send(err(e.code, e.message));
      throw e;
    }
  });

  app.post("/api/v1/auth/otp/verify", async (req, reply) => {
    const b = z.object({ phone: z.string(), code: z.string(), role: z.string().default(""), email: z.string().default(""), name: z.string().default("") }).safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "طلب غير صالح"));
    const phone = normalizePhone(b.data.phone);
    const rec = phone ? await store.getOtp(phone) : null;
    if (!phone || !rec || rec.expiresAt < now()) return reply.code(400).send(err("expired", "الرمز انتهى، اطلب رمز جديد"));
    if (rec.attempts >= 5) { await store.deleteOtp(phone); return reply.code(429).send(err("locked", "محاولات كثيرة، اطلب رمز جديد")); }
    if (rec.codeHash !== hashCode(cfg.authSecret, phone, b.data.code.trim())) {
      await store.setOtp({ ...rec, attempts: rec.attempts + 1 });
      return reply.code(400).send(err("wrong_code", "الرمز غير صحيح"));
    }
    await store.deleteOtp(phone);
    let u = await store.userByPhone(phone);
    if (!u) u = { id: randomUUID(), phone, email: b.data.email, name: b.data.name, role: b.data.role, proUntil: 0, createdAt: now() };
    else u = { ...u, email: b.data.email || u.email, name: b.data.name || u.name, role: b.data.role || u.role };
    await store.saveUser(u);
    return auth(u);
  });

  // ---------------- Google ----------------
  app.post("/api/v1/auth/google", async (req, reply) => {
    const b = z.object({ idToken: z.string().min(10), role: z.string().default("") }).safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "طلب غير صالح"));
    if (!cfg.googleClientIds.length) return reply.code(503).send(err("not_configured", "الدخول بـ Google قيد التجهيز"));
    const r = await f("https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(b.data.idToken));
    if (!r.ok) return reply.code(401).send(err("bad_token", "تعذّر التحقق من حساب Google"));
    const t: any = await r.json();
    if (!cfg.googleClientIds.includes(String(t.aud)) || !t.email || (t.email_verified !== undefined && String(t.email_verified) !== "true")) return reply.code(401).send(err("bad_audience", "حساب Google غير مطابق"));
    let u = await store.userByEmail(t.email);
    if (!u) u = { id: randomUUID(), phone: "", email: t.email, name: t.name ?? "", role: b.data.role, proUntil: 0, createdAt: now() };
    await store.saveUser(u);
    return auth(u);
  });

  // ---------------- «مدين» chat ----------------
  const chatBody = z.object({ messages: z.array(z.object({ role: z.enum(["user", "assistant"]), content: z.string().max(4000) })).min(1).max(30), context: z.unknown() });
  app.post("/api/v1/chat", async (req, reply) => {
    const b = chatBody.safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "طلب غير صالح"));
    if (!ai.ready) return reply.code(503).send(err("not_ready", NOT_READY));
    const u = await userOf(req);
    const key = u ? `chat:${u.id}:${today(now())}` : `chat:ip:${req.ip}:${today(now())}`;
    const limit = u ? cfg.chatDailyLimit : cfg.anonDailyLimit;
    if ((await store.bump(key)) > limit) return reply.code(429).send(err("limit", `وصلت حد اليوم (${limit} رسالة). يتجدد بكرة`));
    try {
      const text = await ai.complete(chatSystemPrompt(b.data.context), b.data.messages);
      const j = parseJsonLoose(text);
      return { reply: String(j.reply ?? ""), table: j.table && Array.isArray(j.table.columns) ? j.table : null, action: j.action && j.action.type ? j.action : null };
    } catch (e) {
      if (e instanceof AiError) return reply.code(e.code === "not_ready" ? 503 : 502).send(err(e.code, e.message));
      throw e;
    }
  });

  // ---------------- smart import ----------------
  const importBody = z.object({ fileName: z.string(), mime: z.string().default(""), text: z.string().max(80_000).default(""), images: z.array(z.string()).max(6).default([]), role: z.string().default("") });
  app.post("/api/v1/import", async (req, reply) => {
    const b = importBody.safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "طلب غير صالح"));
    if (!ai.ready) return reply.code(503).send(err("not_ready", NOT_READY));
    if (!b.data.text.trim() && b.data.images.length === 0) return reply.code(400).send(err("empty", "الملف فاضي"));
    const u = await userOf(req);
    if ((await store.bump(`import:${u?.id ?? req.ip}:${today(now())}`)) > 20) return reply.code(429).send(err("limit", "وصلت حد الرفع لليوم"));
    try {
      const text = await ai.complete(importSystemPrompt(b.data.role), [{ role: "user", content: `اسم الملف: ${b.data.fileName}\n\n${b.data.text || "(صورة مرفقة)"}` }], b.data.images);
      const j = parseJsonLoose(text);
      const rows = Array.isArray(j.rows) ? j.rows : [];
      return { businessName: j.businessName ?? "", ownerName: j.ownerName ?? "", warnings: Array.isArray(j.warnings) ? j.warnings : [], rows: rows.map((r: any) => ({
        name: String(r.name ?? "").trim(), shopNumber: String(r.shopNumber ?? ""), phone: String(r.phone ?? ""), activity: String(r.activity ?? ""),
        amount: Number(r.amount) || 0, arrears: Number(r.arrears) || 0, note: String(r.note ?? ""), doubtful: !!r.doubtful, reason: String(r.reason ?? ""),
      })).filter((r: any) => r.name) };
    } catch (e) {
      if (e instanceof AiError) return reply.code(502).send(err(e.code, e.message));
      throw e;
    }
  });

  // ---------------- backup ----------------
  app.post("/api/v1/backup", async (req, reply) => {
    const u = await userOf(req);
    if (!u) return reply.code(401).send(err("auth", "سجّل دخولك أولاً للنسخ على الخادم"));
    const json = JSON.stringify(req.body ?? {});
    if (json.length > 10_000_000) return reply.code(413).send(err("too_big", "النسخة كبيرة جداً"));
    await store.putBackup(u.id, json);
    return { ok: true, at: now() };
  });
  app.get("/api/v1/backup/latest", async (req, reply) => {
    const u = await userOf(req);
    if (!u) return reply.code(401).send(err("auth", "سجّل دخولك أولاً"));
    const b = await store.latestBackup(u.id);
    if (!b) return reply.code(404).send(err("none", "ما في نسخة محفوظة"));
    return { at: b.at, snapshot: JSON.parse(b.json) };
  });

  // ---------------- Pro ----------------
  app.post("/api/v1/plan/activate", async (req, reply) => {
    const b = z.object({ code: z.string().min(6).max(40) }).safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "اكتب الكود كاملاً"));
    const u = await userOf(req);
    if (!u) return reply.code(401).send(err("auth", "سجّل دخولك أولاً لتفعيل برو"));
    const c = await store.useCode(b.data.code.trim().toUpperCase(), u.id);
    if (!c) return reply.code(400).send(err("bad_code", "الكود غير صحيح أو مستخدم"));
    const base = Math.max(u.proUntil, now());
    const proUntil = base + c.months * 30 * 864e5;
    await store.saveUser({ ...u, proUntil });
    return { ok: true, proUntil, plan: "pro" };
  });

  /** Madyan creates activation codes after a transfer arrives: POST with header x-admin-token. */
  app.post("/api/v1/admin/codes", async (req, reply) => {
    if (!isAdmin(cfg, req)) return reply.code(403).send(err("forbidden", "ممنوع"));
    const b = z.object({ months: z.number().int().min(1).max(36).default(1), count: z.number().int().min(1).max(50).default(1) }).safeParse(req.body ?? {});
    if (!b.success) return reply.code(400).send(err("bad_request", "طلب غير صالح"));
    const codes: string[] = [];
    for (let i = 0; i < b.data.count; i++) {
      const code = "DF-" + randomBytes(5).toString("hex").toUpperCase();
      await store.addCode({ code, months: b.data.months, usedBy: null, usedAt: null, createdAt: now() });
      codes.push(code);
    }
    return { codes, months: b.data.months };
  });

  registerSubs(app, { cfg, store, now, userOf });
  registerVoice(app, { cfg, store, fetch: f, now, userOf });

  app.setErrorHandler((e, _req, reply) => {
    // 1.2.0: a body over the route's limit (e.g. a long voice recording) is the client's 413, not a server error
    if ((e as { statusCode?: number }).statusCode === 413) return reply.code(413).send(err("too_big", "الطلب أكبر من المسموح"));
    reply.code(500).send(err("server", "خطأ في الخادم"));
  });
  return app;
}
