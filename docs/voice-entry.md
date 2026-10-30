<!--
  دفتري · docs/voice-entry.md
  التسجيل بالصوت (1.1.6): البنية، القرارات، وما لم يُتحقّق منه بعد — بالعربية ثم بالإنجليزية.
  Voice entry (1.1.6): structure, decisions and what is still unverified — Arabic, then English.
-->
# التسجيل بالصوت · Voice entry (1.1.6)

## بالعربية

**الفكرة:** صاحب المحل يتكلّم بشكل طبيعي، ودفتري يحوّل الكلام إلى نص على الجهاز، ثم يفهمه ويعبّئ الحقول، وهو يراجع ويضغط «حفظ». لا حفظ تلقائي أبداً.

**المسار:**
1. زر 🎤 في نافذة المبلغ (`MoneySheet`) أو في أعلى الرئيسية.
2. `VoiceCaptureDialog` يطلب إذن الميكروفون عند أول مرة فقط (بعد شرح بالفصحى)، ثم يستمع ويعرض النص مباشرة.
3. `SpeechEngines.pick()` يختار المحرّك — اليوم محرّك واحد: `AndroidRecognizerEngine` (محرّك أندرويد المدمج، `EXTRA_PREFER_OFFLINE=true`، المحاولات: المحرّك على الجهاز ثم الافتراضي، كلٌّ بـ`ar-YE` ثم `ar-SA`).
4. النص يذهب إلى `VoiceEntryParser.parse(text, customers)` فيرجع `VoiceDraft`: النوع، المبلغ، العملة، الزبون (مؤكَّد/مقترح/عدة خيارات/جديد)، الملاحظة.
5. من الرئيسية: `VoiceReviewSheet` للمراجعة السريعة ثم نافذة المبلغ معبّأة (`VoicePrefill`). من نافذة المبلغ نفسها: تُعبَّأ الحقول مباشرة مع سطر «سمعتُ: … / عُبّئ: …».

**قواعد المحلّل (بلا تخمين):**
- النوع: أفعال السداد (سدد، دفع، سلّم، رجّع، عطاني، حوّل، استلمت/قبضت من…) ← تسديد؛ «عليه/عليها»، «دين»، «أخذ مني»، «اشترى»، «تسلّف»، «سلّفت»، «أعطيته»، و«سجّل على فلان» ← دين؛ فعل سداد مع فعل دين حقيقي ← غير واضح؛ «سجّل محمد 5000» وحدها ← غير واضح (يختار المستخدم).
- «على» بالألف المقصورة حرف جر، و«علي» بالياء اسم.
- العملة: «دولار» ← USD، «سعودي» ← SAR، «يمني» ← YER؛ «ريال» وحدها لا تحدد عملة (عملة الحساب).
- المبلغ: عبارة واحدة؛ إن وُجدت عدة أرقام: المتبوع بعملة، ثم المسبوق بـ«ب/بمبلغ»، ثم الأكبر. أرقام الجوال لا تُعدّ مبلغاً، و«محل 12» حساب وليس مبلغاً.
- الملاحظة: ما بعد «حق / قيمة / ثمن / عشان / مقابل…» فقط.
- الاسم: أول تسلسل من كلمات الاسم بعد حرف الجر أو الفعل (حتى 4 كلمات)، ثم المطابقة: تطابق تام بعد التوحيد ← مؤكَّد؛ خطأ إملائي قريب أو الاسم الأول لحساب واحد ← «هل تقصد…؟» (يؤكّد المستخدم)؛ أكثر من حساب ← قائمة؛ لا شيء ← «زبون جديد».

**حزمة العربية دون إنترنت (`SpeechPack`):** أندرويد 13+ مع محرّك على الجهاز: `checkRecognitionSupport` يختار رمز العربية المدعوم ثم `triggerModelDownload` يُنزّل في الخلفية (أندرويد 14+: `ModelDownloadListener` للتقدّم/الجدولة/النجاح/الخطأ)، مع سطر حالة `PackStatusLine`. غير ذلك أو عند الفشل: شاشة Google للتعرّف دون اتصال ← إعدادات الإدخال الصوتي ← صفحة `com.google.android.tts` (ثم `com.google.android.googlequicksearchbox`) في المتجر (`market://` ثم `https`).

**الملفات:** `voice/SpokenNumbers.kt`، `voice/VoiceEntryParser.kt`، `voice/VoiceProblem.kt`، `voice/SpeechEngine.kt`، `voice/AndroidRecognizerEngine.kt`، `voice/SpeechPack.kt`، `voice/SpeechPackStatus.kt`، `voice/VoicePrefill.kt`، `voice/VoiceUi.kt`، `voice/VoiceReview.kt`، `ui/parties/QuickPick.kt`، `core/QuickEntry.kt`. التعديلات: `Forms.kt` (الميكروفون، التعبئة، مبالغ التسديد، فتح لوحة الأرقام)، `PartyDetailsScreen.kt` («سداد كامل»)، `HomeScreen.kt`، `MainActivity.kt`، `AndroidManifest.xml` (`RECORD_AUDIO` و`queries` لخدمة التعرّف).

**لم يُتحقّق منه بعد:** جودة التعرّف على جوال حقيقي (لهجة، ضجيج المحل)، وتوفّر حزمة العربية دون إنترنت ومسار التنزيل في الخلفية على Samsung (A53 / One UI)، وفتح شاشة Google القديمة للحزم في الإصدارات الحديثة من تطبيق Google.

## English

**Idea:** the shopkeeper speaks naturally; Daftari turns speech into text on the phone, understands it and pre-fills the form; he reviews and taps «حفظ». Never auto-saved.

**Flow:** mic (money sheet or home header) → `VoiceCaptureDialog` (RECORD_AUDIO asked only on the first tap, after a فصحى explanation; refusal never blocks typing) → `SpeechEngines.pick()` → `AndroidRecognizerEngine` (built-in `SpeechRecognizer`, `EXTRA_PREFER_OFFLINE=true`; attempts: on-device recognizer (Android 12+) then the default one, each `ar-YE` then `ar-SA`; network/language errors move to the next attempt, silence does not) → `VoiceEntryParser.parse()` → `VoiceDraft` → home: `VoiceReviewSheet` then the pre-filled money sheet (`VoicePrefill`, survives rotation); sheet: fields filled in place with a «سمعتُ / عُبّئ» line and a kind-mismatch switch.

**Parser rules (no guessing):** see the Arabic list above — payment verbs vs debt verbs/«عليه»/«على + name»; «على» (ى) is the preposition, «علي» (ي) the name; currency only from دولار/سعودي/يمني (bare «ريال» = the account currency); one amount (currency-tagged, then «ب…», then largest; phones and «محل N» excluded); note only after a marker; name = first run of name words, matched exact → sure, close/first-name-of-one → suggested, several → options, none → new customer.

**Offline Arabic pack (`SpeechPack`):** Android 13+ with an on-device recognizer: `checkRecognitionSupport` → `triggerModelDownload` in the background (14+: `ModelDownloadListener` progress) with a small status line while typing continues. Otherwise / on failure: Google's offline speech screen → voice-input settings → Play Store page of Google's speech services. Google only: no own model, nothing downloaded by the app itself.

**Pluggable engine:** `SpeechEngine` (`isAvailable / start / stop / cancel` + a text listener). Only the Android engine ships; another engine plugs into `SpeechEngines.pick()` without touching the parser or the UI.

**Also in 1.1.6:** «سداد كامل» on the customer screen (full remaining balance pre-filled, user confirms), one-tap payment amounts 500/1,000/5,000/10,000 (debt chips already existed), the home quick picker with the 5 most recent customers + search + mic, the amount field opening the number pad. No undo snackbar (undo already lives in «السجل»).

**Tests:** `VoiceEntryParserTest` (50 phrases → fields, 43 spoken numbers, name normalization, never-invents) and `Features116Test` (speech-error decisions, best alternative, pre-fill round trip, recent customers, locale choice/status lines, full-balance text).

**Unverified:** real-phone recognition quality (dialect, shop noise), offline-pack availability and the background download on Samsung (A53 / One UI), and whether Google's legacy language-pack screen still opens on current Google app versions (guarded fallbacks follow).
