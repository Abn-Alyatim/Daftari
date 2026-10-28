# دفتري · Daftari

تطبيق أندرويد عربي لتسجيل الديون والإيجارات — لصاحب المحل/البقالة ولصاحب السوق/المؤجر. يشتغل بدون إنترنت (Room) ويزامن مع الخادم لما يتوفر.

- `android/` — Kotlin + Jetpack Compose، الحزمة `com.daftari.app`. الاسم يتغير من `core/AppInfo.kt` و`res/values/strings.xml`.
- `server/` — Node/TypeScript (Fastify + Postgres/Neon): رمز التحقق (WhatsApp Cloud / SMS)، Google، شات «مدين» (Groq ثم Gemini)، الرفع الذكي، النسخ الاحتياطي، أكواد برو.
- خطوات التشغيل: `server/DEPLOY_AR.md`.

## Build
```
cd android
DF_STORE_FILE=... DF_STORE_PASSWORD=... DF_KEY_ALIAS=... DF_KEY_PASSWORD=... ./gradlew testDebugUnitTest assembleRelease
cd ../server && npm install && npm test
```
