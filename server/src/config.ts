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
  groqKey: string;
  groqModel: string;
  groqVisionModel: string;
  geminiKey: string;
  geminiModel: string;
  chatDailyLimit: number;
  anonDailyLimit: number;
  version: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const p = (env.OTP_PROVIDER ?? "").trim();
  const otpProvider = (["whatsapp_cloud", "sms", "dev", "none"].includes(p) ? p : (env.WHATSAPP_TOKEN ? "whatsapp_cloud" : "none")) as Config["otpProvider"];
  return {
    port: Number(env.PORT ?? 8080),
    host: env.HOST ?? "0.0.0.0",
    authSecret: env.AUTH_SECRET ?? "dev-secret-change-me",
    adminToken: env.ADMIN_TOKEN ?? "",
    databaseUrl: env.DATABASE_URL ?? "",
    otpProvider,
    whatsappToken: env.WHATSAPP_TOKEN ?? "",
    whatsappPhoneId: env.WHATSAPP_PHONE_ID ?? "",
    whatsappTemplate: env.WHATSAPP_TEMPLATE ?? "",
    whatsappTemplateLang: env.WHATSAPP_TEMPLATE_LANG ?? "ar",
    smsUrl: env.SMS_PROVIDER_URL ?? "",
    smsSid: env.SMS_ACCOUNT_SID ?? "",
    smsToken: env.SMS_AUTH_TOKEN ?? "",
    smsFrom: env.SMS_FROM ?? "",
    googleClientId: env.GOOGLE_CLIENT_ID ?? "",
    groqKey: env.GROQ_API_KEY ?? "",
    groqModel: env.GROQ_MODEL ?? "llama-3.3-70b-versatile",
    groqVisionModel: env.GROQ_VISION_MODEL ?? "meta-llama/llama-4-scout-17b-16e-instruct",
    geminiKey: env.GEMINI_API_KEY ?? "",
    geminiModel: env.GEMINI_MODEL ?? "gemini-2.0-flash",
    chatDailyLimit: Number(env.CHAT_DAILY_LIMIT ?? 60),
    anonDailyLimit: Number(env.ANON_DAILY_LIMIT ?? 15),
    version: "1.0.0",
  };
}
