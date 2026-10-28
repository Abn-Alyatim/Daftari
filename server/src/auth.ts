import { createHmac, createHash, randomInt, timingSafeEqual } from "node:crypto";

/** Compact signed token: base64url(payload).base64url(hmac). 60 days. */
export function signToken(secret: string, userId: string, days = 60): string {
  const body = Buffer.from(JSON.stringify({ sub: userId, exp: Date.now() + days * 864e5 })).toString("base64url");
  const sig = createHmac("sha256", secret).update(body).digest("base64url");
  return `${body}.${sig}`;
}

export function verifyToken(secret: string, token: string): string | null {
  const [body, sig] = token.split(".");
  if (!body || !sig) return null;
  const want = createHmac("sha256", secret).update(body).digest("base64url");
  const a = Buffer.from(sig), b = Buffer.from(want);
  if (a.length !== b.length || !timingSafeEqual(a, b)) return null;
  try {
    const p = JSON.parse(Buffer.from(body, "base64url").toString());
    return typeof p.sub === "string" && p.exp > Date.now() ? p.sub : null;
  } catch { return null; }
}

export const hashCode = (secret: string, phone: string, code: string) => createHash("sha256").update(`${secret}|${phone}|${code}`).digest("hex");
export const newOtp = () => String(randomInt(0, 1_000_000)).padStart(6, "0");

/** +967 77x… normalisation (Yemen default), digits only after the plus. */
export function normalizePhone(raw: string): string | null {
  const d = raw.replace(/[\u0660-\u0669]/g, (c) => String(c.charCodeAt(0) - 0x660)).replace(/[^0-9+]/g, "");
  let digits = d.startsWith("+") ? d.slice(1) : d.startsWith("00") ? d.slice(2) : d;
  if (/^7\d{8}$/.test(digits)) digits = "967" + digits;
  if (/^0\d{9}$/.test(digits)) digits = "967" + digits.slice(1);
  return /^\d{9,15}$/.test(digits) ? "+" + digits : null;
}
