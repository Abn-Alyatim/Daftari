<!--
  دفتري · docs/voice-entry.md
  التسجيل بالصوت (1.1.7): نموذج صوت خاص بنا دون إنترنت (whisper.cpp)، البنية والقرارات وما لم يُتحقّق منه — بالعربية ثم بالإنجليزية.
  Voice entry (1.1.7): our own offline speech model (whisper.cpp) — structure, decisions, what is unverified — Arabic, then English.
-->
# التسجيل بالصوت · Voice entry (1.1.7)

## بالعربية

**الفكرة:** صاحب المحل يتكلّم بشكل طبيعي، ودفتري يحوّل الكلام إلى نص **على الجوال نفسه دون إنترنت** بنموذج Whisper، ثم يفهمه ويعبّئ الحقول، وهو يراجع ويضغط «حفظ». لا حفظ تلقائي أبداً للدين والتسديد.

**لماذا تغيّر في 1.1.7:** في 1.1.6 اعتمد الصوت على محرّك Google في الجوال. على جوال المالك (Samsung Galaxy A53) لم تتوفر حزمة العربية دون إنترنت، ولم يعمل التعرّف عبر الإنترنت أيضاً، واختفى الميكروفون من محادثة «اسأل مدين». وكانت هناك أسباب في الكود نفسه: 1.1.6 فرض `EXTRA_PREFER_OFFLINE` على كل محاولة، وتوقّف عند أخطاء مثل «لا يوجد تطابق» و«صلاحيات الخدمة» بدلاً من الانتقال للمحاولة التالية، وانتهت السلسلة دائماً بشاشة «نزّل حزمة العربية». لم نعرف أيّها حدث بالضبط على جواله، لأنه لم تصلنا صورة للخطأ. **قرّر المالك إلغاء Google بالكامل**، فصار الصوت يعمل بنموذجنا الخاص فقط.

**المسار:**
1. زر 🎤 دائماً ظاهر: في أعلى الرئيسية، وفي نافذة «دين جديد» و«تسديد»، وفي محادثة «اسأل مدين» بجانب زر الإرسال.
2. أول ضغطة دون نموذج ودون إنترنت (أو مع «دون إنترنت فقط») ← عرض «الإدخال الصوتي دون إنترنت»: اختيار «أساسي · 57 م.ب (موصى به)» أو «خفيف · 31 م.ب»، وتنبيه Wi‑Fi إن كان على بيانات الجوال، وزر «تنزيل نموذج الصوت دون إنترنت (57 م.ب) مرة واحدة».
3. التنزيل في الخلفية (`ModelDownloads`): نسبة مئوية، ويكمل من حيث توقّف (`Range` من ملف ‎.part)، وبعد اكتماله تُطابَق بصمة sha256 ثم يأخذ الملف اسمه النهائي في ملفات التطبيق الخاصة (`filesDir/voice`). إن لم تطابق البصمة يُحذف الملف ويُعاد. يمكن إغلاق النافذة ومتابعة الكتابة أثناء التنزيل.
4. إذن الميكروفون عند الحاجة فقط (بعد شرح بالفصحى).
5. «أستمع إليك…»: تسجيل `AudioRecord` بتردد 16 ك.هـ أحادي، ويتوقف وحده بعد سكوت قصير (1.3 ث) أو بزر «تم»، وبحدّ أقصى 15 ثانية، وإن لم يتكلم أحد خلال 7 ثوانٍ يظهر «لم أسمع كلاماً واضحاً».
6. «جارٍ التعرّف…»: إن كان الجوال متصلاً والإعداد «تلقائي (الإنترنت أولاً)» يُرسل التسجيل نفسه (WAV) إلى خادم دفتري `POST /api/v1/voice/transcribe`، والخادم يرسله إلى Whisper large-v3 من Groq بالعربية ويرجع النص. لا يُحفظ الصوت، ومفتاح Groq في الخادم فقط. أي فشل (لا إنترنت، أكثر من 8 ثوانٍ، خطأ، الخادم بلا مفتاح) ← نموذج الجوال: whisper.cpp على خيط خلفي باللغة العربية، وعدد الخيوط = min(4, أنوية المعالج). وإن لم يكن النموذج منزّلاً يُعرض تنزيله. ويظهر بجانب «سمعتُ» أيّ محرّك استُخدم: «عبر الإنترنت» أو «دون إنترنت».
7. النص يذهب إلى `VoiceEntryParser` كما في 1.1.6 ← المراجعة ← المستخدم يحفظ. في المحادثة يذهب النص إلى خانة الكتابة، أو يُرسل مباشرة إن فعّل «إرسال تلقائي بعد التحدث» من الإعدادات (المحادثة فقط).

**الإعدادات ← «الإدخال الصوتي»:** «محرك الصوت: تلقائي (الإنترنت أولاً) / دون إنترنت فقط» (الافتراضي تلقائي)، حالة النموذج (منزّل/يُنزَّل/غير منزّل)، اختيار «أساسي/خفيف»، تنزيل، إيقاف مؤقت، حذف النموذج، ومفتاح «إرسال تلقائي بعد التحدث». و«عن التطبيق ← التراخيص» فيها ترخيص whisper.cpp ونماذج Whisper.

**الأداء (قياس على الحاسوب، x86 بأربعة خيوط):** 4.9 ثانية من الكلام تُحوَّل في 0.33 ث بنموذج «أساسي» (و0.17–0.45 ث بالخفيف). قصّ نافذة المُرمِّز على طول الكلام بدلاً من 30 ثانية جعل التحويل أسرع بخمس مرات بالنص نفسه. على Galaxy A53 نتوقع ثانية إلى ثانيتين للأساسي، ولم نقِسه على جوال حقيقي بعد.

**الجودة (عيّنات صوت Google العربي، نموذج أساسي):** «سجل على محمد أحمد خمسة آلاف ريال» ← «سجل على محمد أحمد خمسة ألاف ريالاً» (صحيح). «علي صالح سدد ألفين وخمسمية» ← «عليون صالح سدد الفين وخمس ميطن» (الفعل والمبلغ الأساسي صحيحان، والاسم والكسر خطأ). «عليه ثلاثة آلاف حق كيس رز» ← «عليه ثلاثة ألاف حقكي سريزن». النموذج الخفيف أسوأ في الأسماء، ويكرّر الجملة أحياناً، لذلك أضفنا حدّاً لعدد الرموز وإزالةً للتكرار. والصوت الآلي (espeak) لم يُفهم تقريباً.

**الملفات:** `cpp/` (whisper.cpp 1.9.5 مقتطع للمعالج فقط + `daftari_whisper.cpp` + `whisper_jni.cpp` + `CMakeLists.txt`)، `voice/VoiceModel.kt`، `voice/ModelDownloader.kt`، `voice/ModelDownloads.kt`، `voice/EndOfSpeech.kt`، `voice/WhisperNative.kt`، `voice/VoiceRecorderEngine.kt`، `voice/VoiceUi.kt`، `voice/VoiceSettingsUi.kt`، `voice/VoiceProblem.kt`، `voice/SpeechEngine.kt`، والمحلّل كما هو. واختبار الحاسوب في `tools/whisper-host-test/`. المحذوف: `AndroidRecognizerEngine.kt`، `SpeechPack.kt`، `SpeechPackStatus.kt`، و`<queries>` الخاصة بخدمة التعرّف.

**لم يُتحقّق منه بعد:** السرعة والدقة على جوال حقيقي (لهجة يمنية، ضجيج المحل)، والتسجيل بـ`AudioRecord` على Samsung، وتنزيل 57 م.ب عبر شبكات اليمن، ونسخة armeabi-v7a على جوال قديم.

## 1.1.8 — التلميح وإعادة المحاولة وعبارات المحل
- **التلميح:** قبل التعرّف يُبنى نص قصير: عبارات المحل أولاً («سجل عليه»، «سدد الي عليه كامل»، «اخذ بـ»، «طلع/نزل/اطبع/خرج لي كشف»، الأرقام والعملات) ثم أسماء أكثر 50 زبوناً نشاطاً. يذهب للخادم في `?prompt=` (≤400 حرف، الخادم 1.2.2 يمرّره لـ Groq Whisper)، وأقصر منه (≤220 حرفاً، 20 اسماً) للنموذج دون إنترنت كـ `initial_prompt`. خادم قديم يتجاهله؛ وتطبيق قديم لا يرسله.
- **إعادة المحاولة:** 1.1.7 كان يتوقف عن الإنترنت بقية الجلسة بعد أول «voice_disabled» أو 404. الآن يتوقف دقيقة واحدة فقط، ويعود فوراً حين يقول `/api/v1/info` إن `voiceReady: true`.
- **لم يُتحقّق منه على جهاز حقيقي:** أثر التلميح على دقة النموذج الصغير، واحتمال أن يكرر النموذج الصغير نص التلميح عند تسجيل شبه صامت.

## English

**Why 1.1.7 changed:** 1.1.6 used the phone's Google recognizer. On the owner's Galaxy A53 Google offered no offline Arabic pack, online recognition did not work either, and the assistant chat had no mic at all. The 1.1.6 code had its own faults too: it forced `EXTRA_PREFER_OFFLINE` on every attempt, stopped (instead of moving on) on NO_MATCH / INSUFFICIENT_PERMISSIONS / AUDIO / BUSY, and always ended at «download the Arabic pack». Which of these actually hit his phone is unknown, because no error screenshot was received. **The owner then chose to drop Google entirely.** Voice now runs only on our own offline Whisper model.

**Engines (owner's choice, 8:15 PM):** online first — the recording goes as a WAV to the Daftari server `POST /api/v1/voice/transcribe`, which calls Groq `whisper-large-v3` (language ar) with the server-side `GROQ_API_KEY` (and `GROQ_API_KEY_2` on 429). The audio is never stored, there is a per-user / per-IP hourly limit, and with no key the server answers 503 `voice_disabled`. The app gives up after 8 s, and any failure falls back to the offline model. Settings «محرك الصوت»: تلقائي (default) / دون إنترنت فقط. A small «عبر الإنترنت» / «دون إنترنت» label sits beside «سمعتُ». Selection is the pure `VoiceEngines.plan`. With online possible, the mic records even before the model is downloaded, and if online then fails the download is offered.

**Flow:** mic (always shown: home header, debt + payment sheets, chat next to «إرسال») → no model yet and no online voice: the one-time download offer («أساسي» base-q5_1 57 MB default / «خفيف» tiny-q5_1 31 MB, Wi-Fi hint on mobile data) → `ModelDownloads` (background thread, resumable via HTTP Range from the `.part` file, percent progress, sha256 checked before the final rename; mismatch deletes and restarts) → RECORD_AUDIO when needed → `VoiceRecorderEngine`: `AudioRecord` 16 kHz mono PCM16, `EndOfSpeech` (noise floor from the first 0.3 s, 1.3 s silence ends, 15 s max, 7 s no speech → «لم أسمع كلاماً واضحاً»), «تم» at any time → whisper.cpp in Arabic on a background thread, threads = min(4, cores) → text → the same `VoiceEntryParser` → review → the user saves (never auto-saved). Chat: text goes to the input box, or is sent at once when «إرسال تلقائي بعد التحدث» is on (Settings; chat only).

**Native:** `android/app/src/main/cpp`: whisper.cpp v1.9.5 (MIT), with the non-CPU ggml backends removed from the copy. It is built through AGP `externalNativeBuild` (CMake 3.22.1, NDK 28.2.13676358) for `arm64-v8a` + `armeabi-v7a` only, always Release/-O3, static libc++, 16 KB page alignment. `daftari_whisper.cpp` uses greedy decoding, no context, a single segment, no timestamps, no temperature fallback, and max 128 tokens. The encoder window is cut to the spoken length (`audio_ctx` = 50/s + 128, min 384), which is 5× faster on the computer with identical text. Bracket markers are stripped, and back-to-back repeated (or cut-off repeated) sentences are kept once. `WhisperNative` loads the library lazily and guarded (JVM tests / x86 → «غير مدعوم»), and keeps one model loaded.

**Settings:** model status, «أساسي/خفيف», download / pause / delete, the chat auto-send switch. **About → Licenses:** whisper.cpp/ggml (MIT) and the Whisper models (MIT, OpenAI), which are downloaded on demand and never bundled.

**On-computer check** (`tools/whisper-host-test`, the exact shipped C++): base-q5_1 on 4.9 s of Google-TTS Arabic gives the correct sentence «سجل على محمد أحمد خمسة ألاف ريالاً» in 0.33 s with 4 threads (0.59 s with 2). The other samples get the verb and the main amount right but some names and fractions wrong. tiny is faster but worse on names and looped once (now guarded). Robotic espeak-ng Arabic was not understood.

**Server:** `server/src/voice.ts` (1.2.0): raw audio or multipart, ≤1 MB, ≤15 s WAV, `voiceReady` in `/api/v1/info`. Tests in `server/test/voice.test.ts` use a mocked Groq (disabled, raw WAV, multipart, second key, 5xx, size/length, hourly limit). Setup: `server/HOSTLESS_AR.md`.

**Tests:** `Features117Test` (mic decision, engine order online/offline, download resume / sha / cancel / range-ignored / no network with a fake source, end of speech, chat auto-send on/off, parsing of the real whisper outputs, catalog, version) and `Shots117Test` (mic in the debt sheet, the payment sheet and the chat input, found by its accessibility label; download offer; progress; failure; settings). Parser tests unchanged.

**Unverified:** speed and accuracy on a real phone (Yemeni dialect, shop noise), `AudioRecord` on Samsung, the 57 MB download over Yemeni networks, the armeabi-v7a build on an old phone.

## 1.1.8 — hint, retry, shop phrases
- **Hint:** the shop's phrases first, then the 50 most recently active customer names. Sent to the server as `?prompt=` (≤400 chars; server 1.2.2 forwards it as Groq Whisper's `prompt`), and a shorter one (≤220 chars, 20 names) to whisper.cpp as `initial_prompt`. Old servers ignore it, and old apps don't send it.
- **Retry:** 1.1.7 disabled online voice for the session after one `voice_disabled`/404. Now it skips online for 60 s only, and comes back at once when `/api/v1/info` says `voiceReady: true`.
- **Unverified on a real phone:** how much the hint helps the small offline model, and whether that model echoes the hint on a near-silent take.

