export interface Config {
  port: number;
  host: string;
  authSecret: string;
  adminToken: string;
  databaseUrl: string;
  otpProvider: "whatsapp_cloud" | "sms" | "dev" | "none";
  whatsappToken: string;
  whatsappPhoneId: string;
  whatsappTemplate: string;
  whatsappTemplateLang: string;
  smsUrl: string;
  smsSid: string;
  smsToken: string;
  smsFrom: string;
  googleClientId: string;
  /** every accepted audience (GOOGLE_CLIENT_IDS, comma-separated, plus GOOGLE_CLIENT_ID) — a move to a new Cloud project keeps old installs working */
  googleClientIds: string[];
  groqKey: string;
  /** optional second Groq key, used by voice when the first answers 429 */
  groqKey2: string;
  /** speech-to-text model for /api/v1/voice/transcribe */
  groqSttModel: string;
  voiceHourlyLimit: number;
  anonVoiceHourlyLimit: number;
  groqModel: string;
  groqVisionModel: string;
  geminiKey: string;
  geminiModel: string;
  chatDailyLimit: number;
  anonDailyLimit: number;
  version: string;
}

/** 1.2.1: PORT that is unset, empty or not a number falls back to 8080 (Number("") was 0 = a random port). */
export function parsePort(v: string | undefined, fallback = 8080): number {
  const n = Number((v ?? "").trim());
  return Number.isInteger(n) && n > 0 && n < 65536 ? n : fallback;
}

/** 1.2.1: DATABASE_URL pasted the way Neon's dashboard shows it — `psql 'postgresql://…'`, quotes or spaces — is cleaned to the bare URL. */
export function cleanDatabaseUrl(v: string | undefined): string {
  let s = (v ?? "").trim();
  if (/^psql\s+/i.test(s)) s = s.replace(/^psql\s+/i, "").trim();
  if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith('"') && s.endsWith('"'))) s = s.slice(1, -1).trim();
  return s;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const p = (env.OTP_PROVIDER ?? "").trim();
  const otpProvider = (["whatsapp_cloud", "sms", "dev", "none"].includes(p) ? p : (env.WHATSAPP_TOKEN ? "whatsapp_cloud" : "none")) as Config["otpProvider"];
  return {
    port: parsePort(env.PORT),
    host: (env.HOST ?? "").trim() || "0.0.0.0",
    authSecret: env.AUTH_SECRET ?? "dev-secret-change-me",
    adminToken: env.ADMIN_TOKEN ?? "",
    databaseUrl: cleanDatabaseUrl(env.DATABASE_URL),
    otpProvider,
    whatsappToken: env.WHATSAPP_TOKEN ?? "",
    whatsappPhoneId: env.WHATSAPP_PHONE_ID ?? "",
    whatsappTemplate: env.WHATSAPP_TEMPLATE ?? "",
    whatsappTemplateLang: env.WHATSAPP_TEMPLATE_LANG ?? "ar",
    smsUrl: env.SMS_PROVIDER_URL ?? "",
    smsSid: env.SMS_ACCOUNT_SID ?? "",
    smsToken: env.SMS_AUTH_TOKEN ?? "",
    smsFrom: env.SMS_FROM ?? "",
    googleClientIds: [...new Set([...(env.GOOGLE_CLIENT_IDS ?? "").split(","), env.GOOGLE_CLIENT_ID ?? ""].map((x) => x.trim()).filter(Boolean))],
    googleClientId: (env.GOOGLE_CLIENT_ID ?? "").trim() || (env.GOOGLE_CLIENT_IDS ?? "").split(",").map((x) => x.trim()).filter(Boolean)[0] || "",
    groqKey: env.GROQ_API_KEY ?? "",
    groqKey2: env.GROQ_API_KEY_2 ?? "",
    groqSttModel: env.GROQ_STT_MODEL ?? "whisper-large-v3",
    voiceHourlyLimit: Number(env.VOICE_HOURLY_LIMIT ?? 60),
    anonVoiceHourlyLimit: Number(env.ANON_VOICE_HOURLY_LIMIT ?? 20),
    groqModel: env.GROQ_MODEL ?? "llama-3.3-70b-versatile",
    groqVisionModel: env.GROQ_VISION_MODEL ?? "meta-llama/llama-4-scout-17b-16e-instruct",
    geminiKey: env.GEMINI_API_KEY ?? "",
    geminiModel: env.GEMINI_MODEL ?? "gemini-2.0-flash",
    chatDailyLimit: Number(env.CHAT_DAILY_LIMIT ?? 60),
    anonDailyLimit: Number(env.ANON_DAILY_LIMIT ?? 15),
    version: "1.2.1",
  };
}
