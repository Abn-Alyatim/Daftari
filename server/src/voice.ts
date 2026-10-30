/*
 * دفتري · server/src/voice.ts
 * تحويل الصوت إلى نص عبر الإنترنت لتطبيق دفتري (1.1.7): يستقبل تسجيلاً قصيراً (≤15 ث، ≤1 م.ب) ويرسله إلى Whisper من Groq
 * باللغة العربية، ويرجع النص فقط. لا يُحفظ الصوت. المفتاح في متغيّر البيئة GROQ_API_KEY فقط (وGROQ_API_KEY_2 احتياطاً عند 429).
 * POST /api/v1/voice/transcribe — body: raw audio (Content-Type audio/wav | audio/x-wav | audio/mp4 | audio/m4a |
 * audio/mpeg | application/octet-stream) or multipart/form-data with a "file" part. Auth: the app's Bearer token when
 * signed in (VOICE_HOURLY_LIMIT per user per hour), else per IP (ANON_VOICE_HOURLY_LIMIT). Missing key → 503 voice_disabled.
 * Returns { text }. Audio is never stored or logged.
 */
import type { FastifyInstance, FastifyRequest } from "fastify";
import type { Config } from "./config.js";
import type { Store, User } from "./store.js";
import type { Fetch } from "./otp.js";

export const VOICE_MAX_BYTES = 1024 * 1024;
const RAW_TYPES = ["audio/wav", "audio/x-wav", "audio/wave", "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/mpeg", "application/octet-stream"];
const err = (code: string, message: string) => ({ error: { code, message } });

export interface VoiceDeps { cfg: Config; store: Store; fetch: Fetch; now: () => number; userOf: (req: FastifyRequest) => Promise<User | null> }

/** First file part of a multipart body: its bytes, file name and content type (null when there is none). */
export function firstFilePart(body: Buffer, contentType: string): { data: Buffer; name: string; type: string } | null {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);
  if (!m) return null;
  const boundary = Buffer.from("--" + (m[1] ?? m[2]).trim());
  let pos = body.indexOf(boundary);
  while (pos >= 0) {
    const headStart = pos + boundary.length + 2; // skip CRLF
    const headEnd = body.indexOf("\r\n\r\n", headStart);
    if (headEnd < 0) return null;
    const head = body.subarray(headStart, headEnd).toString("utf8");
    const next = body.indexOf(boundary, headEnd + 4);
    if (next < 0) return null;
    const fn = /filename="([^"]*)"/i.exec(head);
    if (fn) {
      const type = /content-type:\s*([^\r\n]+)/i.exec(head)?.[1]?.trim() ?? "application/octet-stream";
      return { data: body.subarray(headEnd + 4, next - 2), name: fn[1] || "audio.wav", type };
    }
    pos = next;
  }
  return null;
}

/** Seconds of audio in a PCM WAV (null when it is not a plain WAV we can measure). */
export function wavSeconds(b: Buffer): number | null {
  if (b.length < 44 || b.toString("ascii", 0, 4) !== "RIFF" || b.toString("ascii", 8, 12) !== "WAVE") return null;
  let p = 12, byteRate = 0;
  while (p + 8 <= b.length) {
    const id = b.toString("ascii", p, p + 4), size = b.readUInt32LE(p + 4);
    if (id === "fmt ") byteRate = b.readUInt32LE(p + 16);
    if (id === "data") return byteRate > 0 ? Math.min(size, b.length - p - 8) / byteRate : null;
    p += 8 + size + (size % 2);
  }
  return null;
}

export function registerVoice(app: FastifyInstance, d: VoiceDeps) {
  const { cfg, store, now, userOf } = d;
  const parse = (_req: unknown, body: Buffer, done: (e: Error | null, b?: Buffer) => void) => done(null, body);
  app.addContentTypeParser(RAW_TYPES, { parseAs: "buffer", bodyLimit: VOICE_MAX_BYTES + 64 * 1024 }, parse);
  app.addContentTypeParser("multipart/form-data", { parseAs: "buffer", bodyLimit: VOICE_MAX_BYTES + 64 * 1024 }, parse);

  app.post("/api/v1/voice/transcribe", { bodyLimit: VOICE_MAX_BYTES + 64 * 1024 }, async (req, reply) => {
    const keys = [cfg.groqKey, cfg.groqKey2].filter(Boolean);
    if (!keys.length) return reply.code(503).send(err("voice_disabled", "الإدخال الصوتي عبر الإنترنت غير مفعّل على الخادم"));
    const ctRaw = String(req.headers["content-type"] ?? "");
    const ct = ctRaw.toLowerCase();
    const raw = Buffer.isBuffer(req.body) ? (req.body as Buffer) : null;
    if (!raw || raw.length === 0) return reply.code(400).send(err("no_audio", "لم يصل تسجيل صوتي"));
    const file = ct.startsWith("multipart/") ? firstFilePart(raw, ctRaw) : { data: raw, name: ct.includes("wav") ? "audio.wav" : ct.includes("mpeg") ? "audio.mp3" : "audio.m4a", type: ct.split(";")[0] || "audio/wav" };
    if (!file || file.data.length === 0) return reply.code(400).send(err("no_audio", "لم يصل تسجيل صوتي"));
    if (file.data.length > VOICE_MAX_BYTES) return reply.code(413).send(err("too_big", "التسجيل أطول من المسموح"));
    const secs = wavSeconds(file.data);
    if (secs !== null && secs > 16) return reply.code(413).send(err("too_long", "التسجيل أطول من 15 ثانية"));

    const u = await userOf(req);
    const hour = Math.floor(now() / 3600_000);
    const limit = u ? cfg.voiceHourlyLimit : cfg.anonVoiceHourlyLimit;
    if ((await store.bump(`voice:${u ? u.id : "ip:" + req.ip}:${hour}`)) > limit) return reply.code(429).send(err("limit", "طلبات صوتية كثيرة، حاول بعد قليل"));

    for (let i = 0; i < keys.length; i++) {
      const form = new FormData();
      form.append("file", new Blob([new Uint8Array(file.data)], { type: file.type }), file.name);
      form.append("model", cfg.groqSttModel);
      form.append("language", "ar");
      form.append("response_format", "json");
      form.append("temperature", "0");
      let r: Response;
      try {
        r = await d.fetch("https://api.groq.com/openai/v1/audio/transcriptions", { method: "POST", headers: { Authorization: `Bearer ${keys[i]}` }, body: form });
      } catch {
        return reply.code(502).send(err("upstream", "تعذّر الوصول إلى خدمة الصوت"));
      }
      if (r.status === 429 && i + 1 < keys.length) continue; // the second key, if any
      if (!r.ok) return reply.code(r.status === 429 ? 429 : 502).send(err(r.status === 429 ? "busy" : "upstream", "خدمة الصوت غير متاحة الآن"));
      const j: any = await r.json().catch(() => ({}));
      return { text: String(j.text ?? "").trim() };
    }
    return reply.code(429).send(err("busy", "خدمة الصوت غير متاحة الآن"));
  });
}
