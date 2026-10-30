import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { z } from "zod";
import type { Config } from "./config.js";
import type { Store, SubRequest, User } from "./store.js";

/**
 * 1.1.0 — subscriptions by transfer / wallet receipt, approved by Madyan in Madyan Panel; plus the app state the panel
 * drives (maintenance, broadcast). Plans, payment methods, the number and the account name live in df_kv so the
 * panel edits them without an app update. Prices are never invented: seeded plans have price 0 and stay hidden
 * from the app until Madyan sets a price.
 */

export interface PlanCfg { id: string; name: string; days: number; price: number; currency: string; active: boolean; badge: string }
export interface MethodCfg { id: string; kind: "transfer" | "wallet"; label: string; number: string; accountName: string; active: boolean; other: boolean }
export interface SubConfig { plans: PlanCfg[]; methods: MethodCfg[]; maintenanceMode: "banner" | "full"; note: string }
export interface AppState {
  maintenance: { on: boolean; message: string; mode: "banner" | "full"; until: number; at: number };
  broadcast: { id: string; title: string; body: string; at: number } | null;
}

export const DEFAULT_NUMBER = "780647484";
export const DEFAULT_ACCOUNT = "مدين محافظ حميد اليتيم";
const m = (id: string, kind: MethodCfg["kind"], label: string, other = false): MethodCfg => ({ id, kind, label, number: DEFAULT_NUMBER, accountName: DEFAULT_ACCOUNT, active: true, other });
export const DEFAULT_CONFIG: SubConfig = {
  plans: [
    { id: "pro_month", name: "برو شهري", days: 30, price: 0, currency: "YER", active: true, badge: "برو" },
    { id: "pro_year", name: "برو سنوي", days: 365, price: 0, currency: "YER", active: true, badge: "برو" },
  ],
  methods: [
    m("kuraimi", "transfer", "الكريمي"), m("hazmi", "transfer", "الحزمي"), m("najm", "transfer", "النجم"), m("transfer_other", "transfer", "أخرى", true),
    m("jaib", "wallet", "جيب"), m("jawali", "wallet", "جوالي"), m("onecash", "wallet", "ون كاش"), m("floosak", "wallet", "فلوسك"), m("hasib", "wallet", "حاسب"),
    m("wallet_other", "wallet", "أخرى", true),
  ],
  maintenanceMode: "banner",
  note: "",
};
const DEFAULT_STATE: AppState = { maintenance: { on: false, message: "", mode: "banner", until: 0, at: 0 }, broadcast: null };

const err = (code: string, message: string) => ({ error: { code, message } });
const DAY = 864e5;

const planSchema = z.object({
  id: z.string().trim().regex(/^[a-z0-9_]{2,32}$/), name: z.string().trim().min(1).max(40), days: z.number().int().min(1).max(3660),
  price: z.number().min(0).max(1e9), currency: z.enum(["YER", "SAR", "USD"]).default("YER"), active: z.boolean().default(true), badge: z.string().trim().max(12).default("برو"),
});
const methodSchema = z.object({
  id: z.string().trim().regex(/^[a-z0-9_]{2,32}$/), kind: z.enum(["transfer", "wallet"]), label: z.string().trim().min(1).max(30),
  number: z.string().trim().max(40).default(""), accountName: z.string().trim().max(80).default(""), active: z.boolean().default(true), other: z.boolean().default(false),
});
export const configSchema = z.object({
  plans: z.array(planSchema).max(12), methods: z.array(methodSchema).max(30),
  maintenanceMode: z.enum(["banner", "full"]).default("banner"), note: z.string().max(300).default(""),
}).superRefine((c, ctx) => {
  const dup = (xs: string[]) => xs.find((x, i) => xs.indexOf(x) !== i);
  const dp = dup(c.plans.map((p) => p.id)); if (dp) ctx.addIssue({ code: "custom", message: `معرّف خطة مكرر: ${dp}` });
  const dm = dup(c.methods.map((x) => x.id)); if (dm) ctx.addIssue({ code: "custom", message: `معرّف طريقة دفع مكرر: ${dm}` });
});

/** «الاسم الرباعي»: four words or more, each at least two letters. */
export function isFourPartName(s: string): boolean {
  const ws = s.trim().split(/\s+/).filter(Boolean);
  return ws.length >= 4 && ws.every((w) => w.replace(/[^\p{L}]/gu, "").length >= 2);
}

/** JPEG / PNG / WEBP by magic bytes. */
export function imageMime(b: Buffer): string | null {
  if (b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  if (b.length > 8 && b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "image/png";
  if (b.length > 12 && b.subarray(0, 4).toString("ascii") === "RIFF" && b.subarray(8, 12).toString("ascii") === "WEBP") return "image/webp";
  return null;
}

export async function loadConfig(store: Store): Promise<SubConfig> {
  const raw = await store.getKv("sub_config");
  if (!raw) return structuredClone(DEFAULT_CONFIG);
  try { const p = configSchema.safeParse(JSON.parse(raw)); return p.success ? (p.data as SubConfig) : structuredClone(DEFAULT_CONFIG); } catch { return structuredClone(DEFAULT_CONFIG); }
}
export async function loadState(store: Store): Promise<AppState> {
  const raw = await store.getKv("app_state");
  if (!raw) return structuredClone(DEFAULT_STATE);
  try { return { ...structuredClone(DEFAULT_STATE), ...JSON.parse(raw) }; } catch { return structuredClone(DEFAULT_STATE); }
}

/** What the app shows: active, priced plans; active methods with a number. */
export function publicConfig(c: SubConfig) {
  return {
    plans: c.plans.filter((p) => p.active && p.price > 0).map(({ id, name, days, price, currency, badge }) => ({ id, name, days, price, currency, badge })),
    methods: c.methods.filter((x) => x.active && x.number).map(({ id, kind, label, number, accountName, other }) => ({ id, kind, label, number, accountName, other })),
    note: c.note,
  };
}

export function planOf(u: User, now: number) {
  if (!u.proUntil) return null;
  return { name: u.planName || "برو", badge: "برو", startsAt: u.planStart ?? 0, endsAt: u.proUntil, active: u.proUntil > now, daysLeft: Math.max(0, Math.ceil((u.proUntil - now) / DAY)) };
}

const userView = (r: SubRequest) => ({
  id: r.id, planId: r.planId, planName: r.planName, days: r.days, price: r.price, currency: r.currency, methodId: r.methodId, methodLabel: r.methodLabel,
  methodKind: r.methodKind, payTo: r.payTo, txnNo: r.txnNo, payerName: r.payerName, status: r.status, rejectReason: r.rejectReason,
  createdAt: r.createdAt, decidedAt: r.decidedAt, startsAt: r.startsAt, endsAt: r.endsAt, seen: r.seenAt > 0,
});
export const adminView = (r: SubRequest) => ({ ...userView(r), userId: r.userId, email: r.email, decidedBy: r.decidedBy, receiptMime: r.receiptMime, receiptBytes: r.receiptBytes });

export interface SubDeps { cfg: Config; store: Store; now: () => number; userOf: (req: FastifyRequest) => Promise<User | null> }

export function isAdmin(cfg: Config, req: FastifyRequest): boolean {
  const h = req.headers["x-admin-token"];
  const got = Buffer.from(typeof h === "string" ? h : "");
  const want = Buffer.from(cfg.adminToken);
  return cfg.adminToken.length > 0 && got.length === want.length && timingSafeEqual(got, want);
}

export function registerSubs(app: FastifyInstance, d: SubDeps) {
  const { cfg, store, now, userOf } = d;
  const admin = (req: FastifyRequest, reply: FastifyReply) => {
    if (!cfg.adminToken) { reply.code(503).send(err("not_configured", "لوحة الإدارة غير مُعدّة على الخادم (ADMIN_TOKEN)")); return false; }
    if (!isAdmin(cfg, req)) { reply.code(403).send(err("forbidden", "رمز الإدارة غير صحيح")); return false; }
    return true;
  };
  const blocked = async (reply: FastifyReply) => {
    const s = await loadState(store);
    if (s.maintenance.on) { reply.code(503).send({ ...err("maintenance", s.maintenance.message || "الخدمة متوقفة مؤقتاً للصيانة"), maintenance: true }); return true; }
    return false;
  };

  // ---------------- public / app ----------------
  app.get("/api/v1/app/status", async (req) => {
    const s = await loadState(store);
    const u = await userOf(req).catch(() => null);
    if (u) await store.touchUser(u.id, now()).catch(() => undefined);
    const mt = s.maintenance.on && s.maintenance.until && s.maintenance.until < now() ? { ...s.maintenance, on: false } : s.maintenance;
    return { maintenance: { on: mt.on, message: mt.message, mode: mt.mode, until: mt.until || null }, broadcast: s.broadcast, serverTime: now(), version: cfg.version };
  });

  app.get("/api/v1/subscription/config", async () => publicConfig(await loadConfig(store)));

  app.get("/api/v1/subscription/status", async (req, reply) => {
    const u = await userOf(req);
    if (!u) return reply.code(401).send(err("auth", "سجّل دخولك بحساب Google أولاً"));
    await store.touchUser(u.id, now()).catch(() => undefined);
    const reqs = await store.userSubRequests(u.id, 10);
    const pending = reqs.find((r) => r.status === "pending") ?? null;
    const notice = reqs.find((r) => r.status !== "pending" && !r.seenAt) ?? null;
    return { email: u.email, plan: planOf(u, now()), pending: pending && userView(pending), notice: notice && userView(notice), last: reqs[0] ? userView(reqs[0]) : null };
  });

  const submit = z.object({
    planId: z.string().min(1).max(40), methodId: z.string().min(1).max(40), methodOther: z.string().trim().max(40).default(""),
    txnNo: z.string().trim().min(3).max(40), payerName: z.string().trim().min(5).max(120),
    receipt: z.string().min(100).max(8_000_000), receiptMime: z.string().max(40).default("image/jpeg"),
  });
  app.post("/api/v1/subscription/requests", async (req, reply) => {
    const u = await userOf(req);
    if (!u) return reply.code(401).send(err("auth", "سجّل دخولك بحساب Google أولاً"));
    if (await blocked(reply)) return reply;
    const b = submit.safeParse(req.body);
    if (!b.success) {
      const f = b.error.issues[0]?.path[0];
      const msg = f === "txnNo" ? "اكتب رقم العملية" : f === "payerName" ? "اكتب الاسم الرباعي" : f === "receipt" ? "أرفق صورة الإيصال" : "طلب غير صالح";
      return reply.code(400).send(err("bad_request", msg));
    }
    if (!isFourPartName(b.data.payerName)) return reply.code(400).send(err("bad_name", "اكتب الاسم الرباعي كاملاً (أربعة أسماء)"));
    if (!/^[0-9A-Za-z\-\/]{3,40}$/.test(b.data.txnNo.replace(/[\u0660-\u0669]/g, (c) => String(c.charCodeAt(0) - 0x660)))) return reply.code(400).send(err("bad_txn", "رقم العملية غير صالح"));
    const conf = await loadConfig(store);
    const plan = publicConfig(conf).plans.find((p) => p.id === b.data.planId);
    if (!plan) return reply.code(400).send(err("bad_plan", "هذه الخطة غير متاحة الآن، حدّث الصفحة"));
    const method = conf.methods.find((x) => x.id === b.data.methodId && x.active && x.number);
    if (!method) return reply.code(400).send(err("bad_method", "طريقة الدفع هذه غير متاحة الآن"));
    if (method.other && !b.data.methodOther) return reply.code(400).send(err("bad_method", method.kind === "wallet" ? "اكتب اسم المحفظة" : "اكتب اسم شركة الصرافة"));
    let bytes: Buffer;
    try { bytes = Buffer.from(b.data.receipt.replace(/^data:[^,]*,/, ""), "base64"); } catch { return reply.code(400).send(err("bad_receipt", "صورة الإيصال غير صالحة")); }
    const mime = imageMime(bytes);
    if (!mime) return reply.code(400).send(err("bad_receipt", "صورة الإيصال غير صالحة (JPG أو PNG)"));
    if (bytes.length > 4 * 1024 * 1024) return reply.code(413).send(err("too_big", "صورة الإيصال كبيرة جداً"));
    const mine = await store.userSubRequests(u.id, 10);
    if (mine.some((r) => r.status === "pending")) return reply.code(409).send(err("pending_exists", "لديك طلب قيد المراجعة، انتظر حتى تتم مراجعته"));
    const day = new Date(now() + 3 * 3600_000).toISOString().slice(0, 10);
    if ((await store.bump(`sub:${u.id}:${day}`)) > 5) return reply.code(429).send(err("limit", "طلبات كثيرة اليوم، حاول غداً"));
    const r: SubRequest = {
      id: "sr_" + randomUUID().replace(/-/g, "").slice(0, 16), userId: u.id, email: u.email, planId: plan.id, planName: plan.name, days: plan.days, price: plan.price,
      currency: plan.currency, methodId: method.id, methodLabel: method.other ? b.data.methodOther : method.label, methodKind: method.kind, payTo: method.number,
      txnNo: b.data.txnNo, payerName: b.data.payerName.replace(/\s+/g, " "), receiptMime: mime, receiptBytes: bytes.length, status: "pending", rejectReason: "",
      createdAt: now(), decidedAt: 0, decidedBy: "", startsAt: 0, endsAt: 0, seenAt: 0,
    };
    await store.addSubRequest(r, bytes);
    return reply.code(201).send({ request: userView(r) });
  });

  app.post("/api/v1/subscription/requests/:id/seen", async (req, reply) => {
    const u = await userOf(req);
    if (!u) return reply.code(401).send(err("auth", "سجّل دخولك أولاً"));
    const r = await store.subRequest((req.params as any).id);
    if (!r || r.userId !== u.id) return reply.code(404).send(err("not_found", "الطلب غير موجود"));
    if (r.status !== "pending" && !r.seenAt) { r.seenAt = now(); await store.saveSubRequest(r); }
    return { ok: true };
  });

  // ---------------- admin (Madyan Panel connector, header x-admin-token) ----------------
  app.get("/api/v1/admin/overview", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const users = await store.allUsers();
    const t = now(); const dayStart = t - ((t + 3 * 3600_000) % DAY);
    const approved = await store.subRequests("approved", 1000);
    const pending = await store.subRequests("pending", 1000);
    const s = await loadState(store);
    return {
      version: cfg.version, users_total: users.length, users_today: users.filter((u) => (u.lastSeen ?? 0) >= dayStart).length,
      new_users: users.filter((u) => u.createdAt >= dayStart).length, subscribers: users.filter((u) => u.proUntil > t).length,
      pending: pending.length, revenue_today: approved.filter((r) => r.decidedAt >= dayStart && r.currency === "YER").reduce((a, r) => a + r.price, 0),
      maintenance: s.maintenance, broadcast: s.broadcast,
    };
  });

  app.get("/api/v1/admin/users", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const q = z.object({ q: z.string().default(""), plan: z.string().default(""), limit: z.coerce.number().int().min(1).max(200).default(50), cursor: z.coerce.number().int().min(0).default(0) }).parse(req.query ?? {});
    const t = now();
    let us = await store.allUsers();
    if (q.q) us = us.filter((u) => [u.email, u.name, u.phone].some((x) => (x ?? "").toLowerCase().includes(q.q.toLowerCase())));
    if (q.plan === "pro") us = us.filter((u) => u.proUntil > t);
    if (q.plan === "free") us = us.filter((u) => u.proUntil <= t);
    const page = us.slice(q.cursor, q.cursor + q.limit);
    return { items: page.map((u) => ({ id: u.id, email: u.email, name: u.name, phone: u.phone, plan: planOf(u, t), createdAt: u.createdAt, lastSeen: u.lastSeen ?? 0 })), next_cursor: q.cursor + q.limit < us.length ? String(q.cursor + q.limit) : null };
  });

  app.get("/api/v1/admin/sub/expiring", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const days = z.coerce.number().int().min(1).max(366).default(7).parse((req.query as any)?.days);
    const t = now();
    const us = (await store.allUsers()).filter((u) => u.proUntil > t && u.proUntil <= t + days * DAY).sort((a, b) => a.proUntil - b.proUntil);
    return { items: us.map((u) => ({ id: u.id, email: u.email, name: u.name, phone: u.phone, plan: planOf(u, t), lastSeen: u.lastSeen ?? 0, createdAt: u.createdAt })) };
  });

  app.get("/api/v1/admin/sub/requests", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const status = z.enum(["pending", "approved", "rejected", "all"]).default("pending").parse((req.query as any)?.status ?? "pending");
    return { items: (await store.subRequests(status, 200)).map(adminView) };
  });

  app.get("/api/v1/admin/sub/requests/:id/receipt", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const x = await store.subReceipt((req.params as any).id);
    if (!x) return reply.code(404).send(err("not_found", "لا توجد صورة إيصال"));
    return reply.header("content-type", x.mime).header("cache-control", "private, max-age=300").send(x.bytes);
  });

  const decided = (r: SubRequest, reply: FastifyReply) => {
    if (r.status !== "pending") { reply.code(409).send(err("already_decided", r.status === "approved" ? "تمت الموافقة على هذا الطلب سابقاً" : "تم رفض هذا الطلب سابقاً")); return true; }
    return false;
  };

  app.post("/api/v1/admin/sub/requests/:id/approve", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const by = z.object({ by: z.string().max(80).default("") }).parse(req.body ?? {}).by;
    const r = await store.subRequest((req.params as any).id);
    if (!r) return reply.code(404).send(err("not_found", "الطلب غير موجود"));
    if (decided(r, reply)) return reply;
    const u = await store.userById(r.userId);
    if (!u) return reply.code(404).send(err("no_user", "صاحب الطلب غير موجود"));
    const t = now();
    const start = u.proUntil > t ? u.proUntil : t; // a renewal stacks after the running period
    const end = start + r.days * DAY;
    await store.saveUser({ ...u, proUntil: end, planName: r.planName, planStart: u.proUntil > t ? (u.planStart || t) : t });
    Object.assign(r, { status: "approved", decidedAt: t, decidedBy: by, startsAt: start, endsAt: end, seenAt: 0 });
    await store.saveSubRequest(r);
    return { request: adminView(r), expiresAt: end };
  });

  app.post("/api/v1/admin/sub/requests/:id/reject", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const b = z.object({ reason: z.string().trim().min(1).max(300), by: z.string().max(80).default("") }).safeParse(req.body ?? {});
    if (!b.success) return reply.code(400).send(err("reason_required", "اكتب سبب الرفض"));
    const r = await store.subRequest((req.params as any).id);
    if (!r) return reply.code(404).send(err("not_found", "الطلب غير موجود"));
    if (decided(r, reply)) return reply;
    Object.assign(r, { status: "rejected", rejectReason: b.data.reason, decidedAt: now(), decidedBy: b.data.by, seenAt: 0 });
    await store.saveSubRequest(r);
    return { request: adminView(r) };
  });

  app.get("/api/v1/admin/sub/config", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    return await loadConfig(store);
  });
  app.put("/api/v1/admin/sub/config", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const p = configSchema.safeParse(req.body);
    if (!p.success) return reply.code(400).send(err("bad_config", p.error.issues[0]?.message ?? "إعدادات غير صالحة"));
    await store.setKv("sub_config", JSON.stringify(p.data));
    return p.data;
  });

  app.post("/api/v1/admin/maintenance", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const b = z.object({ on: z.boolean(), message: z.string().trim().max(500).default(""), mode: z.enum(["banner", "full"]).optional(), until: z.number().int().min(0).default(0) }).safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "طلب غير صالح"));
    const s = await loadState(store);
    const mode = b.data.mode ?? (await loadConfig(store)).maintenanceMode;
    s.maintenance = { on: b.data.on, message: b.data.on ? b.data.message || "دفتري في صيانة قصيرة، وسنعود قريباً." : "", mode, until: b.data.on ? b.data.until : 0, at: now() };
    await store.setKv("app_state", JSON.stringify(s));
    return { maintenance: s.maintenance };
  });

  app.post("/api/v1/admin/broadcast", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const b = z.object({ title: z.string().trim().max(80).default(""), body: z.string().trim().min(1).max(1000) }).safeParse(req.body);
    if (!b.success) return reply.code(400).send(err("bad_request", "اكتب نص الرسالة"));
    const s = await loadState(store);
    s.broadcast = { id: "bc_" + now().toString(36), title: b.data.title, body: b.data.body, at: now() };
    await store.setKv("app_state", JSON.stringify(s));
    return { broadcast: s.broadcast, recipients: (await store.allUsers()).length };
  });
  app.delete("/api/v1/admin/broadcast", async (req, reply) => {
    if (!admin(req, reply)) return reply;
    const s = await loadState(store); s.broadcast = null;
    await store.setKv("app_state", JSON.stringify(s));
    return { ok: true };
  });
}
