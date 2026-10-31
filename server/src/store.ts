import pg from "pg";

export interface User { id: string; phone: string; email: string; name: string; role: string; proUntil: number; createdAt: number; planName?: string; planStart?: number; lastSeen?: number }

/** 1.1.0: a subscription request (receipt photo stored apart, in the DB — FILE_STORAGE=db like ALYATIM). */
export interface SubRequest {
  id: string; userId: string; email: string; planId: string; planName: string; days: number; price: number; currency: string;
  methodId: string; methodLabel: string; methodKind: string; payTo: string; txnNo: string; payerName: string;
  receiptMime: string; receiptBytes: number; status: "pending" | "approved" | "rejected"; rejectReason: string;
  createdAt: number; decidedAt: number; decidedBy: string; startsAt: number; endsAt: number; seenAt: number;
}
export interface OtpRecord { phone: string; codeHash: string; expiresAt: number; attempts: number; sentAt: number }
export interface ActivationCode { code: string; months: number; usedBy: string | null; usedAt: number | null; createdAt: number }

/** Storage: in-memory for tests/dev, Postgres (Neon) in production. */
export interface Store {
  init(): Promise<void>;
  userByPhone(phone: string): Promise<User | null>;
  userByEmail(email: string): Promise<User | null>;
  userById(id: string): Promise<User | null>;
  saveUser(u: User): Promise<void>;
  setOtp(r: OtpRecord): Promise<void>;
  getOtp(phone: string): Promise<OtpRecord | null>;
  deleteOtp(phone: string): Promise<void>;
  /** increments and returns the count for key (e.g. chat:userId:2026-10-28) */
  bump(key: string): Promise<number>;
  putBackup(userId: string, json: string): Promise<void>;
  latestBackup(userId: string): Promise<{ json: string; at: number } | null>;
  addCode(c: ActivationCode): Promise<void>;
  useCode(code: string, userId: string): Promise<ActivationCode | null>;
  // ---- 1.1.0 subscriptions, app state, users for the panel ----
  getKv(key: string): Promise<string | null>;
  setKv(key: string, value: string): Promise<void>;
  addSubRequest(r: SubRequest, receipt: Buffer): Promise<void>;
  subRequest(id: string): Promise<SubRequest | null>;
  saveSubRequest(r: SubRequest): Promise<void>;
  subRequests(status: string, limit?: number): Promise<SubRequest[]>;
  userSubRequests(userId: string, limit?: number): Promise<SubRequest[]>;
  subReceipt(id: string): Promise<{ mime: string; bytes: Buffer } | null>;
  allUsers(): Promise<User[]>;
  touchUser(id: string, at: number): Promise<void>;
  /** 1.2.1: release connections on shutdown (SIGTERM from the host on redeploy) */
  close?(): Promise<void>;
}

export class MemoryStore implements Store {
  users = new Map<string, User>();
  otps = new Map<string, OtpRecord>();
  counters = new Map<string, number>();
  backups = new Map<string, { json: string; at: number }>();
  codes = new Map<string, ActivationCode>();
  kv = new Map<string, string>();
  subs = new Map<string, SubRequest>();
  receipts = new Map<string, { mime: string; bytes: Buffer }>();
  async init() {}
  async getKv(key: string) { return this.kv.get(key) ?? null; }
  async setKv(key: string, value: string) { this.kv.set(key, value); }
  async addSubRequest(r: SubRequest, receipt: Buffer) { this.subs.set(r.id, { ...r }); this.receipts.set(r.id, { mime: r.receiptMime, bytes: receipt }); }
  async subRequest(id: string) { const r = this.subs.get(id); return r ? { ...r } : null; }
  async saveSubRequest(r: SubRequest) { this.subs.set(r.id, { ...r }); }
  async subRequests(status: string, limit = 200) {
    return [...this.subs.values()].filter((r) => status === "all" || r.status === status).sort((a, b) => b.createdAt - a.createdAt).slice(0, limit).map((r) => ({ ...r }));
  }
  async userSubRequests(userId: string, limit = 20) { return [...this.subs.values()].filter((r) => r.userId === userId).sort((a, b) => b.createdAt - a.createdAt).slice(0, limit).map((r) => ({ ...r })); }
  async subReceipt(id: string) { return this.receipts.get(id) ?? null; }
  async allUsers() { return [...this.users.values()].map((u) => ({ ...u })); }
  async touchUser(id: string, at: number) { const u = this.users.get(id); if (u) u.lastSeen = at; }
  async userByPhone(phone: string) { return [...this.users.values()].find((u) => u.phone === phone) ?? null; }
  async userByEmail(email: string) { return [...this.users.values()].find((u) => u.email && u.email === email) ?? null; }
  async userById(id: string) { return this.users.get(id) ?? null; }
  async saveUser(u: User) { this.users.set(u.id, { ...u }); }
  async setOtp(r: OtpRecord) { this.otps.set(r.phone, { ...r }); }
  async getOtp(phone: string) { return this.otps.get(phone) ?? null; }
  async deleteOtp(phone: string) { this.otps.delete(phone); }
  async bump(key: string) { const n = (this.counters.get(key) ?? 0) + 1; this.counters.set(key, n); return n; }
  async putBackup(userId: string, json: string) { this.backups.set(userId, { json, at: Date.now() }); }
  async latestBackup(userId: string) { return this.backups.get(userId) ?? null; }
  async addCode(c: ActivationCode) { this.codes.set(c.code, { ...c }); }
  async useCode(code: string, userId: string) {
    const c = this.codes.get(code);
    if (!c || c.usedBy) return null;
    c.usedBy = userId; c.usedAt = Date.now();
    return { ...c };
  }
}

export class PgStore implements Store {
  private pool: pg.Pool;
  constructor(url: string) {
    const local = /@(localhost|127\.0\.0\.1)(:|\/)/.test(url);
    this.pool = new pg.Pool({
      connectionString: url, max: 4, ssl: local ? undefined : { rejectUnauthorized: false },
      // 1.2.1: a dead DB host fails in 15 s instead of hanging; idle clients are let go before Neon suspends its compute
      connectionTimeoutMillis: 15_000, idleTimeoutMillis: 60_000, keepAlive: true,
    });
    // 1.2.1: Neon (and any Postgres) closes idle connections — compute auto-suspend, restarts, admin terminate.
    // pg.Pool re-emits that as an 'error' event; with no listener Node treats it as uncaught and the whole server exits.
    this.pool.on("error", (e) => console.error(`[db] idle connection dropped: ${e.message} (pool reconnects on the next query)`));
  }
  async close() { await this.pool.end(); }
  private q(text: string, values: unknown[] = []) { return this.pool.query(text, values); }
  async init() {
    await this.q(`CREATE TABLE IF NOT EXISTS df_users (id TEXT PRIMARY KEY, phone TEXT, email TEXT, name TEXT, role TEXT, pro_until BIGINT DEFAULT 0, created_at BIGINT)`);
    await this.q(`CREATE UNIQUE INDEX IF NOT EXISTS df_users_phone ON df_users(phone) WHERE phone <> ''`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_otps (phone TEXT PRIMARY KEY, code_hash TEXT, expires_at BIGINT, attempts INT, sent_at BIGINT)`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_counters (key TEXT PRIMARY KEY, n INT)`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_backups (user_id TEXT PRIMARY KEY, json TEXT, at BIGINT)`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_codes (code TEXT PRIMARY KEY, months INT, used_by TEXT, used_at BIGINT, created_at BIGINT)`);
    // 1.1.0 subscriptions
    await this.q(`ALTER TABLE df_users ADD COLUMN IF NOT EXISTS plan_name TEXT DEFAULT ''`);
    await this.q(`ALTER TABLE df_users ADD COLUMN IF NOT EXISTS plan_start BIGINT DEFAULT 0`);
    await this.q(`ALTER TABLE df_users ADD COLUMN IF NOT EXISTS last_seen BIGINT DEFAULT 0`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_kv (key TEXT PRIMARY KEY, value TEXT)`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_sub_requests (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, email TEXT, plan_id TEXT, plan_name TEXT, days INT, price NUMERIC, currency TEXT,
      method_id TEXT, method_label TEXT, method_kind TEXT, pay_to TEXT, txn_no TEXT, payer_name TEXT, receipt_mime TEXT, receipt_bytes INT, receipt BYTEA,
      status TEXT NOT NULL, reject_reason TEXT DEFAULT '', created_at BIGINT, decided_at BIGINT DEFAULT 0, decided_by TEXT DEFAULT '', starts_at BIGINT DEFAULT 0, ends_at BIGINT DEFAULT 0, seen_at BIGINT DEFAULT 0)`);
    await this.q(`CREATE INDEX IF NOT EXISTS df_sub_requests_status ON df_sub_requests(status, created_at DESC)`);
    await this.q(`CREATE INDEX IF NOT EXISTS df_sub_requests_user ON df_sub_requests(user_id, created_at DESC)`);
  }
  private sr(x: any): SubRequest {
    return { id: x.id, userId: x.user_id, email: x.email ?? "", planId: x.plan_id ?? "", planName: x.plan_name ?? "", days: Number(x.days ?? 0), price: Number(x.price ?? 0), currency: x.currency ?? "YER",
      methodId: x.method_id ?? "", methodLabel: x.method_label ?? "", methodKind: x.method_kind ?? "", payTo: x.pay_to ?? "", txnNo: x.txn_no ?? "", payerName: x.payer_name ?? "",
      receiptMime: x.receipt_mime ?? "", receiptBytes: Number(x.receipt_bytes ?? 0), status: x.status, rejectReason: x.reject_reason ?? "", createdAt: Number(x.created_at ?? 0),
      decidedAt: Number(x.decided_at ?? 0), decidedBy: x.decided_by ?? "", startsAt: Number(x.starts_at ?? 0), endsAt: Number(x.ends_at ?? 0), seenAt: Number(x.seen_at ?? 0) };
  }
  private static SR_COLS = "id,user_id,email,plan_id,plan_name,days,price,currency,method_id,method_label,method_kind,pay_to,txn_no,payer_name,receipt_mime,receipt_bytes,status,reject_reason,created_at,decided_at,decided_by,starts_at,ends_at,seen_at";
  async getKv(key: string) { const r = await this.q(`SELECT value FROM df_kv WHERE key=$1`, [key]); return r.rows[0]?.value ?? null; }
  async setKv(key: string, value: string) { await this.q(`INSERT INTO df_kv (key,value) VALUES ($1,$2) ON CONFLICT (key) DO UPDATE SET value=$2`, [key, value]); }
  async addSubRequest(r: SubRequest, receipt: Buffer) {
    await this.q(`INSERT INTO df_sub_requests (${PgStore.SR_COLS},receipt) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25)`,
      [r.id, r.userId, r.email, r.planId, r.planName, r.days, r.price, r.currency, r.methodId, r.methodLabel, r.methodKind, r.payTo, r.txnNo, r.payerName, r.receiptMime, r.receiptBytes,
        r.status, r.rejectReason, r.createdAt, r.decidedAt, r.decidedBy, r.startsAt, r.endsAt, r.seenAt, receipt]);
  }
  async subRequest(id: string) { const r = await this.q(`SELECT ${PgStore.SR_COLS} FROM df_sub_requests WHERE id=$1`, [id]); return r.rows[0] ? this.sr(r.rows[0]) : null; }
  async saveSubRequest(r: SubRequest) {
    await this.q(`UPDATE df_sub_requests SET status=$2, reject_reason=$3, decided_at=$4, decided_by=$5, starts_at=$6, ends_at=$7, seen_at=$8 WHERE id=$1`,
      [r.id, r.status, r.rejectReason, r.decidedAt, r.decidedBy, r.startsAt, r.endsAt, r.seenAt]);
  }
  async subRequests(status: string, limit = 200) {
    const r = status === "all"
      ? await this.q(`SELECT ${PgStore.SR_COLS} FROM df_sub_requests ORDER BY created_at DESC LIMIT $1`, [limit])
      : await this.q(`SELECT ${PgStore.SR_COLS} FROM df_sub_requests WHERE status=$1 ORDER BY created_at DESC LIMIT $2`, [status, limit]);
    return r.rows.map((x) => this.sr(x));
  }
  async userSubRequests(userId: string, limit = 20) {
    const r = await this.q(`SELECT ${PgStore.SR_COLS} FROM df_sub_requests WHERE user_id=$1 ORDER BY created_at DESC LIMIT $2`, [userId, limit]);
    return r.rows.map((x) => this.sr(x));
  }
  async subReceipt(id: string) { const r = await this.q(`SELECT receipt_mime, receipt FROM df_sub_requests WHERE id=$1`, [id]); const x = r.rows[0]; return x && x.receipt ? { mime: x.receipt_mime, bytes: x.receipt as Buffer } : null; }
  async allUsers() { const r = await this.q(`SELECT * FROM df_users ORDER BY created_at DESC LIMIT 5000`); return r.rows.map((x) => this.u(x)); }
  async touchUser(id: string, at: number) { await this.q(`UPDATE df_users SET last_seen=$2 WHERE id=$1`, [id, at]); }
  private u(r: any): User { return { id: r.id, phone: r.phone ?? "", email: r.email ?? "", name: r.name ?? "", role: r.role ?? "", proUntil: Number(r.pro_until ?? 0), createdAt: Number(r.created_at ?? 0), planName: r.plan_name ?? "", planStart: Number(r.plan_start ?? 0), lastSeen: Number(r.last_seen ?? 0) }; }
  async userByPhone(phone: string) { const r = await this.q(`SELECT * FROM df_users WHERE phone=$1`, [phone]); return r.rows[0] ? this.u(r.rows[0]) : null; }
  async userByEmail(email: string) { const r = await this.q(`SELECT * FROM df_users WHERE email=$1 AND email<>''`, [email]); return r.rows[0] ? this.u(r.rows[0]) : null; }
  async userById(id: string) { const r = await this.q(`SELECT * FROM df_users WHERE id=$1`, [id]); return r.rows[0] ? this.u(r.rows[0]) : null; }
  async saveUser(u: User) {
    await this.q(`INSERT INTO df_users (id,phone,email,name,role,pro_until,created_at,plan_name,plan_start) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
      ON CONFLICT (id) DO UPDATE SET phone=$2,email=$3,name=$4,role=$5,pro_until=$6,plan_name=$8,plan_start=$9`, [u.id, u.phone, u.email, u.name, u.role, u.proUntil, u.createdAt, u.planName ?? "", u.planStart ?? 0]);
  }
  async setOtp(r: OtpRecord) {
    await this.q(`INSERT INTO df_otps (phone,code_hash,expires_at,attempts,sent_at) VALUES ($1,$2,$3,$4,$5)
      ON CONFLICT (phone) DO UPDATE SET code_hash=$2,expires_at=$3,attempts=$4,sent_at=$5`, [r.phone, r.codeHash, r.expiresAt, r.attempts, r.sentAt]);
  }
  async getOtp(phone: string) {
    const r = await this.q(`SELECT * FROM df_otps WHERE phone=$1`, [phone]);
    const x = r.rows[0]; return x ? { phone: x.phone, codeHash: x.code_hash, expiresAt: Number(x.expires_at), attempts: x.attempts, sentAt: Number(x.sent_at) } : null;
  }
  async deleteOtp(phone: string) { await this.q(`DELETE FROM df_otps WHERE phone=$1`, [phone]); }
  async bump(key: string) {
    const r = await this.q(`INSERT INTO df_counters (key,n) VALUES ($1,1) ON CONFLICT (key) DO UPDATE SET n=df_counters.n+1 RETURNING n`, [key]);
    return r.rows[0].n as number;
  }
  async putBackup(userId: string, json: string) {
    await this.q(`INSERT INTO df_backups (user_id,json,at) VALUES ($1,$2,$3) ON CONFLICT (user_id) DO UPDATE SET json=$2, at=$3`, [userId, json, Date.now()]);
  }
  async latestBackup(userId: string) { const r = await this.q(`SELECT json, at FROM df_backups WHERE user_id=$1`, [userId]); return r.rows[0] ? { json: r.rows[0].json, at: Number(r.rows[0].at) } : null; }
  async addCode(c: ActivationCode) { await this.q(`INSERT INTO df_codes (code,months,used_by,used_at,created_at) VALUES ($1,$2,NULL,NULL,$3)`, [c.code, c.months, c.createdAt]); }
  async useCode(code: string, userId: string) {
    const r = await this.q(`UPDATE df_codes SET used_by=$2, used_at=$3 WHERE code=$1 AND used_by IS NULL RETURNING *`, [code, userId, Date.now()]);
    const x = r.rows[0]; return x ? { code: x.code, months: x.months, usedBy: x.used_by, usedAt: Number(x.used_at), createdAt: Number(x.created_at) } : null;
  }
}
