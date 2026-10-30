import { describe, it, expect } from "vitest";
import { buildApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import { MemoryStore } from "../src/store.js";
import { makeOtp } from "../src/otp.js";
import { Ai } from "../src/ai.js";
import { signToken } from "../src/auth.js";
import { DEFAULT_CONFIG, imageMime, isFourPartName, publicConfig } from "../src/subs.js";

const ADMIN = "adm-0123456789abcdef";
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(400, 7)]).toString("base64");
const DAY = 864e5;

function setup(clock = { t: Date.UTC(2026, 9, 30, 9) }) {
  const cfg = loadConfig({ AUTH_SECRET: "s", ADMIN_TOKEN: ADMIN, GOOGLE_CLIENT_ID: "web-id.apps.googleusercontent.com" });
  const store = new MemoryStore();
  const f = (async (url: string) => {
    if (String(url).includes("tokeninfo")) {
      const tok = decodeURIComponent(String(url).split("id_token=")[1]);
      if (tok === "good-token-for-ali") return new Response(JSON.stringify({ aud: "web-id.apps.googleusercontent.com", email: "ali@gmail.com", name: "Ali" }));
      if (tok === "other-aud-token-xx") return new Response(JSON.stringify({ aud: "someone-else", email: "x@gmail.com" }));
      return new Response("{}", { status: 400 });
    }
    return new Response("{}", { status: 500 });
  }) as unknown as typeof fetch;
  const app = buildApp({ cfg, store, otp: makeOtp(cfg, f), ai: new Ai(cfg, f), fetch: f, now: () => clock.t });
  return { app, store, clock };
}
const H = { "x-admin-token": ADMIN };

async function googleUser(app: any) {
  const r = await app.inject({ method: "POST", url: "/api/v1/auth/google", payload: { idToken: "good-token-for-ali" } });
  expect(r.statusCode).toBe(200);
  return { token: r.json().token as string, auth: { authorization: "Bearer " + r.json().token } };
}
async function priced(app: any) {
  const c = (await app.inject({ url: "/api/v1/admin/sub/config", headers: H })).json();
  c.plans[0].price = 3000; c.plans[1].price = 30000;
  const r = await app.inject({ method: "PUT", url: "/api/v1/admin/sub/config", headers: H, payload: c });
  expect(r.statusCode).toBe(200);
}
const body = (over: Record<string, unknown> = {}) => ({ planId: "pro_month", methodId: "kuraimi", txnNo: "123456789", payerName: "مدين محافظ حميد اليتيم", receipt: JPEG, receiptMime: "image/jpeg", ...over });

describe("helpers", () => {
  it("four-part names", () => {
    expect(isFourPartName("مدين محافظ حميد اليتيم")).toBe(true);
    expect(isFourPartName("مدين محافظ اليتيم")).toBe(false);
    expect(isFourPartName("a b c d")).toBe(false);
  });
  it("image magic", () => {
    expect(imageMime(Buffer.from(JPEG, "base64"))).toBe("image/jpeg");
    expect(imageMime(Buffer.from("hello world, not an image"))).toBeNull();
  });
  it("seed: no invented prices, methods share number and name", () => {
    expect(DEFAULT_CONFIG.plans.every((p) => p.price === 0)).toBe(true);
    expect(publicConfig(DEFAULT_CONFIG).plans).toHaveLength(0);
    expect(DEFAULT_CONFIG.methods.every((m) => m.number === "780647484" && m.accountName === "مدين محافظ حميد اليتيم")).toBe(true);
    expect(DEFAULT_CONFIG.methods.filter((m) => m.kind === "transfer").map((m) => m.label)).toEqual(["الكريمي", "الحزمي", "النجم", "أخرى"]);
  });
});

describe("google auth", () => {
  it("verifies audience", async () => {
    const { app } = setup();
    expect((await app.inject({ method: "POST", url: "/api/v1/auth/google", payload: { idToken: "other-aud-token-xx" } })).statusCode).toBe(401);
    expect((await app.inject({ method: "POST", url: "/api/v1/auth/google", payload: { idToken: "bad-token-zzzzzz" } })).statusCode).toBe(401);
    const { token } = await googleUser(app);
    expect(token).toBeTruthy();
  });
  it("accepts every audience in GOOGLE_CLIENT_IDS (new Cloud project) and never a hardcoded one", () => {
    const c = loadConfig({ GOOGLE_CLIENT_IDS: "new-web.apps.googleusercontent.com, old-web.apps.googleusercontent.com" });
    expect(c.googleClientIds).toEqual(["new-web.apps.googleusercontent.com", "old-web.apps.googleusercontent.com"]);
    expect(loadConfig({}).googleClientIds).toEqual([]);
  });
});

describe("subscription flow", () => {
  it("public config hides unpriced plans until Madyan sets a price", async () => {
    const { app } = setup();
    expect((await app.inject({ url: "/api/v1/subscription/config" })).json().plans).toHaveLength(0);
    await priced(app);
    const c = (await app.inject({ url: "/api/v1/subscription/config" })).json();
    expect(c.plans.map((p: any) => [p.name, p.days, p.price])).toEqual([["برو شهري", 30, 3000], ["برو سنوي", 365, 30000]]);
    expect(c.methods.find((m: any) => m.id === "jaib")).toMatchObject({ kind: "wallet", number: "780647484" });
  });

  it("submit → pending → approve → status notice + plan → seen", async () => {
    const { app, clock } = setup();
    await priced(app);
    const { auth } = await googleUser(app);
    expect((await app.inject({ url: "/api/v1/subscription/status" })).statusCode).toBe(401);
    const s = await app.inject({ method: "POST", url: "/api/v1/subscription/requests", headers: auth, payload: body() });
    expect(s.statusCode).toBe(201);
    const id = s.json().request.id;
    expect(s.json().request).toMatchObject({ status: "pending", planName: "برو شهري", price: 3000, methodLabel: "الكريمي", payTo: "780647484" });
    // one pending at a time
    expect((await app.inject({ method: "POST", url: "/api/v1/subscription/requests", headers: auth, payload: body() })).statusCode).toBe(409);
    let st = (await app.inject({ url: "/api/v1/subscription/status", headers: auth })).json();
    expect(st.pending.id).toBe(id); expect(st.plan).toBeNull();
    // panel side
    const list = (await app.inject({ url: "/api/v1/admin/sub/requests", headers: H })).json().items;
    expect(list[0]).toMatchObject({ id, email: "ali@gmail.com", payerName: "مدين محافظ حميد اليتيم", txnNo: "123456789", receiptMime: "image/jpeg" });
    const rc = await app.inject({ url: `/api/v1/admin/sub/requests/${id}/receipt`, headers: H });
    expect(rc.statusCode).toBe(200); expect(rc.headers["content-type"]).toBe("image/jpeg"); expect(rc.rawPayload.length).toBe(404);
    const ap = await app.inject({ method: "POST", url: `/api/v1/admin/sub/requests/${id}/approve`, headers: H, payload: { by: "مدين" } });
    expect(ap.statusCode).toBe(200);
    expect(ap.json().expiresAt).toBe(clock.t + 30 * DAY);
    expect((await app.inject({ method: "POST", url: `/api/v1/admin/sub/requests/${id}/approve`, headers: H })).statusCode).toBe(409);
    st = (await app.inject({ url: "/api/v1/subscription/status", headers: auth })).json();
    expect(st.pending).toBeNull();
    expect(st.notice).toMatchObject({ id, status: "approved", planName: "برو شهري", payerName: "مدين محافظ حميد اليتيم", endsAt: clock.t + 30 * DAY });
    expect(st.plan).toMatchObject({ name: "برو شهري", active: true, daysLeft: 30, endsAt: clock.t + 30 * DAY });
    await app.inject({ method: "POST", url: `/api/v1/subscription/requests/${id}/seen`, headers: auth });
    st = (await app.inject({ url: "/api/v1/subscription/status", headers: auth })).json();
    expect(st.notice).toBeNull();
    // renewal stacks after the running period
    const s2 = await app.inject({ method: "POST", url: "/api/v1/subscription/requests", headers: auth, payload: body({ planId: "pro_year", methodId: "jaib", txnNo: "A-77" }) });
    expect(s2.statusCode).toBe(201);
    const ap2 = await app.inject({ method: "POST", url: `/api/v1/admin/sub/requests/${s2.json().request.id}/approve`, headers: H });
    expect(ap2.json().expiresAt).toBe(clock.t + (30 + 365) * DAY);
    // expiry: badge goes away once the date passes
    clock.t += 400 * DAY;
    st = (await app.inject({ url: "/api/v1/subscription/status", headers: auth })).json();
    expect(st.plan.active).toBe(false); expect(st.plan.daysLeft).toBe(0);
  });

  it("reject carries the reason to the app", async () => {
    const { app } = setup();
    await priced(app);
    const { auth } = await googleUser(app);
    const id = (await app.inject({ method: "POST", url: "/api/v1/subscription/requests", headers: auth, payload: body() })).json().request.id;
    expect((await app.inject({ method: "POST", url: `/api/v1/admin/sub/requests/${id}/reject`, headers: H, payload: { reason: "" } })).statusCode).toBe(400);
    expect((await app.inject({ method: "POST", url: `/api/v1/admin/sub/requests/${id}/reject`, headers: H, payload: { reason: "المبلغ لم يصل" } })).statusCode).toBe(200);
    const st = (await app.inject({ url: "/api/v1/subscription/status", headers: auth })).json();
    expect(st.notice).toMatchObject({ status: "rejected", rejectReason: "المبلغ لم يصل" });
    expect(st.plan).toBeNull();
  });

  it("validates fields in فصحى", async () => {
    const { app } = setup();
    await priced(app);
    const { auth } = await googleUser(app);
    const post = (p: any) => app.inject({ method: "POST", url: "/api/v1/subscription/requests", headers: auth, payload: p });
    expect((await post(body({ payerName: "مدين اليتيم" }))).json().error.message).toContain("الرباعي");
    expect((await post(body({ txnNo: "" }))).json().error.message).toContain("رقم العملية");
    expect((await post(body({ receipt: Buffer.alloc(300, 1).toString("base64") }))).json().error.code).toBe("bad_receipt");
    expect((await post(body({ planId: "nope" }))).json().error.code).toBe("bad_plan");
    expect((await post(body({ methodId: "wallet_other" }))).json().error.message).toContain("اسم المحفظة");
    const ok = await post(body({ methodId: "wallet_other", methodOther: "كاش" }));
    expect(ok.statusCode).toBe(201); expect(ok.json().request.methodLabel).toBe("كاش");
  });

  it("admin endpoints need the token", async () => {
    const { app } = setup();
    for (const url of ["/api/v1/admin/sub/requests", "/api/v1/admin/sub/config", "/api/v1/admin/overview", "/api/v1/admin/users", "/api/v1/admin/sub/expiring"]) {
      expect((await app.inject({ url })).statusCode).toBe(403);
      expect((await app.inject({ url, headers: { "x-admin-token": "wrong" } })).statusCode).toBe(403);
      expect((await app.inject({ url, headers: H })).statusCode).toBe(200);
    }
    const { token } = await googleUser(app);
    expect((await app.inject({ url: "/api/v1/admin/sub/requests", headers: { authorization: "Bearer " + token } })).statusCode).toBe(403);
  });

  it("config validation rejects duplicates", async () => {
    const { app } = setup();
    const c = structuredClone(DEFAULT_CONFIG); c.methods.push({ ...c.methods[0] });
    expect((await app.inject({ method: "PUT", url: "/api/v1/admin/sub/config", headers: H, payload: c })).statusCode).toBe(400);
  });
});

describe("maintenance, broadcast, expiring", () => {
  it("maintenance shows in app status and blocks new requests; resume clears", async () => {
    const { app } = setup();
    await priced(app);
    const { auth } = await googleUser(app);
    let r = await app.inject({ method: "POST", url: "/api/v1/admin/maintenance", headers: H, payload: { on: true, message: "صيانة حتى العصر" } });
    expect(r.json().maintenance).toMatchObject({ on: true, mode: "banner" });
    let st = (await app.inject({ url: "/api/v1/app/status" })).json();
    expect(st.maintenance).toMatchObject({ on: true, message: "صيانة حتى العصر", mode: "banner" });
    r = await app.inject({ method: "POST", url: "/api/v1/subscription/requests", headers: auth, payload: body() });
    expect(r.statusCode).toBe(503); expect(r.json().maintenance).toBe(true);
    await app.inject({ method: "POST", url: "/api/v1/admin/maintenance", headers: H, payload: { on: true, message: "x", mode: "full" } });
    expect((await app.inject({ url: "/api/v1/app/status" })).json().maintenance.mode).toBe("full");
    await app.inject({ method: "POST", url: "/api/v1/admin/maintenance", headers: H, payload: { on: false } });
    st = (await app.inject({ url: "/api/v1/app/status" })).json();
    expect(st.maintenance.on).toBe(false);
  });
  it("broadcast reaches app status", async () => {
    const { app } = setup();
    await googleUser(app);
    const r = await app.inject({ method: "POST", url: "/api/v1/admin/broadcast", headers: H, payload: { title: "تحديث", body: "نسخة جديدة متاحة" } });
    expect(r.json().recipients).toBe(1);
    expect((await app.inject({ url: "/api/v1/app/status" })).json().broadcast).toMatchObject({ title: "تحديث", body: "نسخة جديدة متاحة" });
  });
  it("expiring lists subscriptions ending within N days", async () => {
    const { app, store, clock } = setup();
    await store.saveUser({ id: "u1", phone: "", email: "a@x", name: "A", role: "", proUntil: clock.t + 2 * DAY, createdAt: 0, planName: "برو شهري" });
    await store.saveUser({ id: "u2", phone: "", email: "b@x", name: "B", role: "", proUntil: clock.t + 20 * DAY, createdAt: 0 });
    await store.saveUser({ id: "u3", phone: "", email: "c@x", name: "C", role: "", proUntil: clock.t - DAY, createdAt: 0 });
    expect((await app.inject({ url: "/api/v1/admin/sub/expiring?days=3", headers: H })).json().items.map((x: any) => x.id)).toEqual(["u1"]);
    expect((await app.inject({ url: "/api/v1/admin/sub/expiring?days=30", headers: H })).json().items.map((x: any) => x.id)).toEqual(["u1", "u2"]);
    const ov = (await app.inject({ url: "/api/v1/admin/overview", headers: H })).json();
    expect(ov).toMatchObject({ users_total: 3, subscribers: 2 });
    void signToken;
  });
});
