import pg from "pg";

export interface User { id: string; phone: string; email: string; name: string; role: string; proUntil: number; createdAt: number }
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
}

export class MemoryStore implements Store {
  users = new Map<string, User>();
  otps = new Map<string, OtpRecord>();
  counters = new Map<string, number>();
  backups = new Map<string, { json: string; at: number }>();
  codes = new Map<string, ActivationCode>();
  async init() {}
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
    this.pool = new pg.Pool({ connectionString: url, max: 4, ssl: url.includes("localhost") ? undefined : { rejectUnauthorized: false } });
  }
  private q(text: string, values: unknown[] = []) { return this.pool.query(text, values); }
  async init() {
    await this.q(`CREATE TABLE IF NOT EXISTS df_users (id TEXT PRIMARY KEY, phone TEXT, email TEXT, name TEXT, role TEXT, pro_until BIGINT DEFAULT 0, created_at BIGINT)`);
    await this.q(`CREATE UNIQUE INDEX IF NOT EXISTS df_users_phone ON df_users(phone) WHERE phone <> ''`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_otps (phone TEXT PRIMARY KEY, code_hash TEXT, expires_at BIGINT, attempts INT, sent_at BIGINT)`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_counters (key TEXT PRIMARY KEY, n INT)`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_backups (user_id TEXT PRIMARY KEY, json TEXT, at BIGINT)`);
    await this.q(`CREATE TABLE IF NOT EXISTS df_codes (code TEXT PRIMARY KEY, months INT, used_by TEXT, used_at BIGINT, created_at BIGINT)`);
  }
  private u(r: any): User { return { id: r.id, phone: r.phone ?? "", email: r.email ?? "", name: r.name ?? "", role: r.role ?? "", proUntil: Number(r.pro_until ?? 0), createdAt: Number(r.created_at ?? 0) }; }
  async userByPhone(phone: string) { const r = await this.q(`SELECT * FROM df_users WHERE phone=$1`, [phone]); return r.rows[0] ? this.u(r.rows[0]) : null; }
  async userByEmail(email: string) { const r = await this.q(`SELECT * FROM df_users WHERE email=$1 AND email<>''`, [email]); return r.rows[0] ? this.u(r.rows[0]) : null; }
  async userById(id: string) { const r = await this.q(`SELECT * FROM df_users WHERE id=$1`, [id]); return r.rows[0] ? this.u(r.rows[0]) : null; }
  async saveUser(u: User) {
    await this.q(`INSERT INTO df_users (id,phone,email,name,role,pro_until,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7)
      ON CONFLICT (id) DO UPDATE SET phone=$2,email=$3,name=$4,role=$5,pro_until=$6`, [u.id, u.phone, u.email, u.name, u.role, u.proUntil, u.createdAt]);
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
