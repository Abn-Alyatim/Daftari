/*
 * دفتري · voice/SpeechPack.kt
 * تنزيل حزمة العربية للتعرّف على الكلام دون إنترنت، في الخلفية، بضغطة واحدة — والمستخدم يتابع الكتابة أثناء ذلك.
 * One-tap download of the offline Arabic speech pack:
 *  - Android 13+ with an on-device recognizer: SpeechRecognizer.checkRecognitionSupport picks the Arabic locale the phone
 *    supports, then triggerModelDownload downloads it IN THE BACKGROUND (Android 14+: ModelDownloadListener gives progress,
 *    scheduled, success, error). [status] is app-wide so a small line can follow it while the user keeps typing.
 *  - Older Android, or when that path is missing / fails: Google's offline speech settings screen, else the Play Store page of
 *    Google's speech services (com.google.android.tts, then com.google.android.googlequicksearchbox), market:// then https.
 * Every call is guarded (missing activity, refused call → next option). Google only: no own model, no download by the app.
 */
package com.daftari.app.voice

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

object SpeechPack {
    val status = MutableStateFlow<PackStatus>(PackStatus.Idle)

    /** The Arabic locale the on-device recognizer said it supports (tried by the engine after ar-YE / ar-SA). */
    @Volatile var onDeviceLanguage: String? = null

    private var holder: SpeechRecognizer? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** True when this phone can download the pack in the background (Android 13+ with an on-device recognizer). */
    fun canDownloadInBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

    /** After a successful recognition the «ready» line has done its job. */
    fun used() { if (status.value == PackStatus.Ready || status.value == PackStatus.Failed) status.value = PackStatus.Idle }

    /** Starts the background download. Returns false when this phone has no such path (the caller then opens settings / store). */
    fun startBackground(ctx: Context): Boolean {
        if (!canDownloadInBackground(ctx) || Build.VERSION.SDK_INT < 33) return false
        if (status.value.busy) return true
        return check(ctx.applicationContext)
    }

    @RequiresApi(33)
    private fun check(app: Context): Boolean {
        return runCatching {
            release()
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            holder = r
            status.value = PackStatus.Checking
            val exec = ContextCompat.getMainExecutor(app)
            r.checkRecognitionSupport(AndroidRecognizerEngine.intentFor("ar-SA"), exec, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val installed = ArabicLocales.pick(s.installedOnDeviceLanguages)
                    val pending = ArabicLocales.pick(s.pendingOnDeviceLanguages)
                    val supported = ArabicLocales.pick(s.supportedOnDeviceLanguages + s.onlineLanguages)
                    when {
                        installed != null -> { onDeviceLanguage = installed; status.value = PackStatus.Ready; release() }
                        pending != null -> { onDeviceLanguage = pending; status.value = PackStatus.Downloading(null); download(app, pending) }
                        else -> { onDeviceLanguage = supported ?: "ar-SA"; download(app, onDeviceLanguage!!) }
                    }
                }
                override fun onError(error: Int) { onDeviceLanguage = "ar-SA"; download(app, "ar-SA") }
            })
            true
        }.getOrElse { status.value = PackStatus.Failed; release(); false }
    }

    @RequiresApi(33)
    private fun download(ctx: Context, language: String) {
        val r = holder ?: return
        runCatching {
            val intent = AndroidRecognizerEngine.intentFor(language)
            if (Build.VERSION.SDK_INT >= 34) {
                r.triggerModelDownload(intent, ContextCompat.getMainExecutor(ctx), object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) { status.value = PackStatus.Downloading(completedPercent.coerceIn(0, 100)) }
                    override fun onSuccess() { status.value = PackStatus.Ready; release() }
                    override fun onScheduled() { status.value = PackStatus.Scheduled; release() }
                    override fun onError(error: Int) { status.value = PackStatus.Failed; release() }
                })
            } else {
                // Android 13: no progress callback — the system downloads on its own; the next mic tap simply works when it is done
                r.triggerModelDownload(intent)
                status.value = PackStatus.Downloading(null)
                main.postDelayed({ release() }, 15_000)
            }
        }.onFailure { status.value = PackStatus.Failed; release() }
    }

    private fun release() { runCatching { holder?.destroy() }; holder = null }

    enum class Opened(val hint: String) {
        GOOGLE_OFFLINE("اختر «العربية» من قائمة اللغات ونزّلها، ثم عُد إلى دفتري واضغط على الميكروفون."),
        VOICE_SETTINGS("اختر «خدمات الكلام من Google» ← «التعرّف على الكلام دون اتصال» ← نزّل «العربية»."),
        STORE("ثبّت «خدمات الكلام من Google» أو حدّثها من المتجر، ثم نزّل «العربية» من إعداداتها."),
        NOTHING("تعذّر فتح إعدادات الكلام في هذا الجهاز. يمكنك متابعة الكتابة يدوياً."),
    }

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false } catch (_: RuntimeException) { false }

    /** Google's offline speech settings, else the system voice-input settings, else the store page. */
    fun openSettingsOrStore(ctx: Context): Opened {
        val google = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.greco3.languagepack.InstallActivity"),
        )
        if (launch(ctx, google)) return Opened.GOOGLE_OFFLINE
        if (launch(ctx, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) return Opened.VOICE_SETTINGS
        return if (openStore(ctx)) Opened.STORE else Opened.NOTHING
    }

    /** The Play Store page of Google's speech services (market://, then https), Google app as the fallback package. */
    fun openStore(ctx: Context): Boolean {
        for (pkg in listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")) {
            if (launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))) return true
            if (launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))) return true
        }
        return false
    }
}
