import type { Config } from "./config.js";

export type Fetch = typeof fetch;

/** Sends a verification code. Each provider is behind this one interface so switching is an env change. */
export interface OtpProvider {
  name: string;
  channels: string[];
  ready: boolean;
  send(phone: string, code: string, channel: string): Promise<void>;
}

const message = (code: string) => `رمز التحقق في دفتري: ${code}\nلا تشاركه مع أحد. صالح 10 دقائق.`;

/** WhatsApp Cloud API from Madyan's own business number (PENDING CONFIG: WHATSAPP_TOKEN + WHATSAPP_PHONE_ID). */
export class WhatsAppCloud implements OtpProvider {
  name = "whatsapp_cloud";
  channels = ["whatsapp"];
  constructor(private cfg: Config, private f: Fetch = fetch) {}
  get ready() { return !!(this.cfg.whatsappToken && this.cfg.whatsappPhoneId); }
  async send(phone: string, code: string) {
    if (!this.ready) throw new OtpError("not_configured", "إرسال الرمز بالواتساب قيد التجهيز");
    const to = phone.replace("+", "");
    const body = this.cfg.whatsappTemplate
      ? { messaging_product: "whatsapp", to, type: "template", template: { name: this.cfg.whatsappTemplate, language: { code: this.cfg.whatsappTemplateLang },
          components: [{ type: "body", parameters: [{ type: "text", text: code }] }, { type: "button", sub_type: "url", index: "0", parameters: [{ type: "text", text: code }] }] } }
      : { messaging_product: "whatsapp", to, type: "text", text: { body: message(code) } };
    const r = await this.f(`https://graph.facebook.com/v21.0/${this.cfg.whatsappPhoneId}/messages`, {
      method: "POST", headers: { Authorization: `Bearer ${this.cfg.whatsappToken}`, "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    if (!r.ok) throw new OtpError("send_failed", "تعذّر إرسال الرمز بالواتساب، جرّب SMS");
  }
}

/** Twilio-compatible SMS (PENDING CONFIG: SMS_PROVIDER_URL + SMS_ACCOUNT_SID + SMS_AUTH_TOKEN + SMS_FROM). */
export class Sms implements OtpProvider {
  name = "sms";
  channels = ["sms"];
  constructor(private cfg: Config, private f: Fetch = fetch) {}
  get ready() { return !!(this.cfg.smsUrl && this.cfg.smsSid && this.cfg.smsToken && this.cfg.smsFrom); }
  async send(phone: string, code: string) {
    if (!this.ready) throw new OtpError("not_configured", "إرسال الرمز برسالة SMS قيد التجهيز");
    const r = await this.f(this.cfg.smsUrl, {
      method: "POST",
      headers: { Authorization: "Basic " + Buffer.from(`${this.cfg.smsSid}:${this.cfg.smsToken}`).toString("base64"), "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ To: phone, From: this.cfg.smsFrom, Body: message(code) }).toString(),
    });
    if (!r.ok) throw new OtpError("send_failed", "تعذّر إرسال الرسالة");
  }
}

/** Development only: the code is returned to the app (never use in production). */
export class DevOtp implements OtpProvider {
  name = "dev"; channels = ["whatsapp", "sms"]; ready = true;
  last = new Map<string, string>();
  async send(phone: string, code: string) { this.last.set(phone, code); }
}

export class NoOtp implements OtpProvider {
  name = "none"; channels: string[] = []; ready = false;
  async send(): Promise<void> { throw new OtpError("not_configured", "التحقق برقم الجوال قيد التجهيز — استخدم Google أو تابع بدون حساب"); }
}

export class OtpError extends Error { constructor(public code: string, message: string) { super(message); } }

/** Primary provider by OTP_PROVIDER; SMS is offered as the alternative channel when configured. */
export class OtpRouter {
  constructor(private primary: OtpProvider, private alt?: OtpProvider) {}
  get name() { return this.primary.name; }
  get channels() { return [...new Set([...(this.primary.ready ? this.primary.channels : []), ...(this.alt?.ready ? this.alt.channels : [])])]; }
  get ready() { return this.primary.ready || !!this.alt?.ready; }
  get isDev() { return this.primary.name === "dev"; }
  async send(phone: string, code: string, channel: string) {
    const p = this.primary.ready && this.primary.channels.includes(channel) ? this.primary : this.alt?.ready && this.alt.channels.includes(channel) ? this.alt : this.primary.ready ? this.primary : this.alt;
    if (!p) throw new OtpError("not_configured", "التحقق برقم الجوال قيد التجهيز");
    await p.send(phone, code, channel);
    return p.channels.includes(channel) ? channel : p.channels[0];
  }
}

export function makeOtp(cfg: Config, f: Fetch = fetch): OtpRouter {
  const sms = new Sms(cfg, f);
  switch (cfg.otpProvider) {
    case "whatsapp_cloud": return new OtpRouter(new WhatsAppCloud(cfg, f), sms);
    case "sms": return new OtpRouter(sms);
    case "dev": return new OtpRouter(new DevOtp());
    default: return new OtpRouter(new NoOtp(), sms);
  }
}
