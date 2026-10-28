# تشغيل خادم دفتري — خطوة بخطوة

التطبيق يشتغل كامل **بدون الخادم** (كل البيانات على الجوال). الخادم يضيف: الدخول برقم الجوال وGoogle، شات «مدين»، الرفع الذكي، النسخ على الخادم، وأكواد تفعيل برو.

رابط الخادم مثبّت داخل التطبيق: `https://daftari.hostless.app` — لذلك سمِّ التطبيق في hostless **daftari** بالضبط.

---

## الخطوة 1: قاعدة البيانات (Neon، مجاني)
1. افتح neon.tech وسجّل دخول.
2. اضغط **New Project** واكتب الاسم `daftari`.
3. انسخ **Connection string** (يبدأ بـ `postgres://`).

## الخطوة 2: ارفع الكود على GitHub
الكود في المستودع `Abn-Alyatim/Daftari`، ومجلد الخادم هو `server/`.

## الخطوة 3: أنشئ التطبيق على hostless
1. افتح hostless واضغط **New App**.
2. اختر المستودع `Daftari`.
3. **Root directory**: اكتب `server`.
4. **Name**: اكتب `daftari`.
5. نوع البناء: **Dockerfile** (موجود جاهز).

## الخطوة 4: المتغيرات (Environment)
أضف هذه، كل واحد في سطر:

| المتغير | القيمة |
|---|---|
| `DATABASE_URL` | الرابط من Neon |
| `AUTH_SECRET` | أي نص طويل عشوائي (40 حرف) |
| `ADMIN_TOKEN` | كلمة سر لك لتوليد أكواد برو |
| `OTP_PROVIDER` | `whatsapp_cloud` |

ثم اضغط **Deploy**. افتح `https://daftari.hostless.app/health` لازم يطلع `"ok":true`.

---

## الخطوة 5 (لاحقاً): رمز التحقق بالواتساب من رقمك
قيد التجهيز حتى تضيف المتغيرين. لين ذلك التطبيق يعرض Google و«المتابعة بدون حساب».
1. افتح developers.facebook.com ← **My Apps** ← **Create App** ← **Business**.
2. أضف منتج **WhatsApp** واربط رقمك.
3. من **API Setup** انسخ **Phone number ID** ← ضعه في `WHATSAPP_PHONE_ID`.
4. من **Business Settings ← System users** أنشئ توكن دائم بصلاحية `whatsapp_business_messaging` ← ضعه في `WHATSAPP_TOKEN`.
5. (مُستحسن) أنشئ قالب **Authentication** باسم `daftari_code` ولغة العربية ← ضع الاسم في `WHATSAPP_TEMPLATE`.

**بديل SMS** (اختياري، Twilio أو أي مزود متوافق): `SMS_PROVIDER_URL`، `SMS_ACCOUNT_SID`، `SMS_AUTH_TOKEN`، `SMS_FROM`.

## الخطوة 6 (لاحقاً): «مدين» والرفع الذكي
| المتغير | من وين |
|---|---|
| `GROQ_API_KEY` | console.groq.com ← **API Keys** ← **Create** |
| `GEMINI_API_KEY` | aistudio.google.com ← **Get API key** (احتياطي) |

بدونهم يظهر في التطبيق «الخدمة قيد التجهيز».

## الخطوة 7 (لاحقاً): الدخول بـ Google
1. console.cloud.google.com ← **APIs & Services ← Credentials ← Create credentials ← OAuth client ID**.
2. النوع **Web application** ← انسخ **Client ID** ← ضعه في `GOOGLE_CLIENT_ID`.
3. أنشئ Client ثاني نوع **Android**: الحزمة `com.daftari.app` والبصمة SHA-1:
   `76:29:9F:69:58:16:51:2D:43:E7:F0:DF:6D:1E:45:6C:11:D9:B2:BF`

---

## توليد كود برو لعميل حوّل لك
```
curl -X POST https://daftari.hostless.app/api/v1/admin/codes \
  -H "x-admin-token: كلمة_سر_الأدمن" -H "content-type: application/json" \
  -d '{"months":1,"count":1}'
```
يرجع كود مثل `DF-3FA92C11B0`. أرسله للعميل، يكتبه في **الإعدادات ← دفتري برو ← كود التفعيل**.

## الحدود الافتراضية
- رمز تحقق واحد كل دقيقة لكل رقم، و6 في اليوم.
- «مدين»: 60 رسالة يومياً للمسجّل (`CHAT_DAILY_LIMIT`)، و15 لغير المسجّل (`ANON_DAILY_LIMIT`).
