/*
 * دفتري · voice/ModelDownloads.kt
 * حالة تنزيل نموذج الصوت على مستوى التطبيق (تستمر إن أُغلقت النافذة)، وإعداد «إرسال تلقائي بعد التحدث» لمحادثة مدين.
 * App-wide download state of the voice model (a single background thread; closing the dialog does not stop it; the
 * .part file makes it resumable after the app is killed) and the voice preferences: «إرسال تلقائي بعد التحدث» applies
 * to the assistant chat only — debt / payment entries are never saved automatically; «محرك الصوت» (تلقائي = الإنترنت أولاً /
 * دون إنترنت فقط) and the pure engine plan: online (server → Groq Whisper) when connected, the offline model as fallback.
 * 1.1.8: «voice off» from the server is retried after a minute ([VoiceCooldown]) or as soon as /api/v1/info says voiceReady.
 */
package com.daftari.app.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface ModelState {
    data object Idle : ModelState
    data class Downloading(val model: VoiceModel, val percent: Int) : ModelState
    data class Failed(val result: DownloadResult) : ModelState
    data object Ready : ModelState

    /** The فصحى line for this state (null = nothing to show). */
    val line: String? get() = when (this) {
        Idle -> null
        is Downloading -> "جارٍ تنزيل نموذج الصوت… \u2066$percent%\u2069 يمكنك متابعة الكتابة."
        is Failed -> when (result) {
            DownloadResult.SHA_MISMATCH -> "وصل ملف النموذج تالفاً فحُذف. أعد المحاولة."
            DownloadResult.NO_SPACE -> "لا توجد مساحة كافية في الجوال لنموذج الصوت."
            DownloadResult.CANCELLED -> "أُوقف التنزيل، ويكمل من حيث توقّف عند المتابعة."
            else -> "انقطع التنزيل. تأكّد من الإنترنت، وسيكمل من حيث توقّف."
        }
        Ready -> "نموذج الصوت جاهز ✅ يعمل الآن دون إنترنت."
    }
}

object ModelDownloads {
    val state = MutableStateFlow<ModelState>(ModelState.Idle)
    @Volatile private var cancel = false
    @Volatile private var running = false

    val busy: Boolean get() = running

    /** Starts (or resumes) downloading [model] in the background; returns at once. */
    fun start(ctx: Context, model: VoiceModel = VoiceModels.chosen(ctx)) {
        if (running) return
        val app = ctx.applicationContext
        VoiceModels.choose(app, model)
        running = true; cancel = false
        state.value = ModelState.Downloading(model, ModelDownloader.percent(VoiceModels.partFile(app, model).length(), model.bytes))
        Thread({
            val r = runCatching {
                ModelDownloader(HttpRangeSource).download(
                    model.url, model.bytes, model.sha256, VoiceModels.partFile(app, model), VoiceModels.file(app, model),
                    onProgress = { pct -> state.value = ModelState.Downloading(model, pct) }, cancelled = { cancel },
                )
            }.getOrDefault(DownloadResult.NETWORK)
            running = false
            state.value = if (r == DownloadResult.OK) ModelState.Ready else ModelState.Failed(r)
        }, "daftari-model-download").start()
    }

    fun stop() { cancel = true }

    /** After the first successful recognition the «ready» line has done its job. */
    fun used() { if (state.value == ModelState.Ready) state.value = ModelState.Idle }

    /** On mobile data (not Wi-Fi): the offer shows a Wi-Fi hint. */
    fun onMobileData(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)
}

object VoicePrefs {
    private const val PREFS = "daftari_voice"
    private const val AUTO_SEND = "chat_auto_send"
    private const val MODE = "engine_mode"
    val chatAutoSend = MutableStateFlow(false)
    /** «محرك الصوت»: تلقائي (الإنترنت أولاً) — the default — or دون إنترنت فقط */
    val mode = MutableStateFlow(VoiceMode.AUTO)
    /** what the server said about online voice (null = not asked yet → try it); false only for [VoiceCooldown.RETRY_MS] (1.1.8) */
    @Volatile var serverVoice: Boolean? = null
    /** 1.1.8: when online voice last failed with «voice_disabled» / 404 (elapsedRealtime ms) */
    @Volatile var serverVoiceOffAt: Long = 0L

    /** 1.1.8: the online state for the plan now — a «no» expires after a minute instead of lasting the whole session. */
    fun serverVoiceNow(nowMs: Long): Boolean? = VoiceCooldown.state(serverVoice, serverVoiceOffAt, nowMs)
    fun onlineWorked() { serverVoice = true; serverVoiceOffAt = 0L }
    fun onlineDisabled(nowMs: Long) { serverVoice = false; serverVoiceOffAt = nowMs }
    /** /api/v1/info said voiceReady: true (e.g. the key was just added on the server) → try online again at once. */
    fun serverSaysReady(ready: Boolean) { if (ready && serverVoice == false) { serverVoice = null; serverVoiceOffAt = 0L } }
    @Volatile private var loaded = false

    fun load(ctx: Context): Boolean {
        if (!loaded) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            chatAutoSend.value = p.getBoolean(AUTO_SEND, false)
            mode.value = VoiceMode.entries.firstOrNull { it.name == p.getString(MODE, null) } ?: VoiceMode.AUTO
            loaded = true
        }
        return chatAutoSend.value
    }

    fun setMode(ctx: Context, m: VoiceMode) {
        load(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE, m.name).apply()
        mode.value = m
    }

    fun setChatAutoSend(ctx: Context, on: Boolean) {
        load(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_SEND, on).apply()
        chatAutoSend.value = on; loaded = true
    }
}

enum class VoiceMode(val label: String) { AUTO("تلقائي (الإنترنت أولاً)"), OFFLINE_ONLY("دون إنترنت فقط") }

/** Which engine turned the speech into text (shown as a small label). */
enum class VoiceVia(val label: String) { ONLINE("عبر الإنترنت"), OFFLINE("دون إنترنت") }

/** The last engine used, read by the screens when they show what was heard. */
object VoiceLast { @Volatile var via: VoiceVia? = null }

/**
 * 1.1.8: a server that said «voice off» (no Groq key yet) is asked again after [RETRY_MS] — 1.1.7 stopped trying until the
 * app was restarted, so after Madyan added the key the app kept using the phone's small model. Pure, unit-tested.
 */
object VoiceCooldown {
    const val RETRY_MS = 60_000L
    fun state(serverVoice: Boolean?, offAt: Long, nowMs: Long): Boolean? =
        if (serverVoice == false && (nowMs - offAt >= RETRY_MS || nowMs < offAt)) null else serverVoice
}

/** Engine selection — pure, unit-tested (online ok / online fails → offline / offline only). */
object VoiceEngines {
    /** The engines to try after recording, in order. */
    fun plan(mode: VoiceMode, networkUp: Boolean, serverConfigured: Boolean, serverVoice: Boolean?, offlineReady: Boolean): List<VoiceVia> = buildList {
        if (mode == VoiceMode.AUTO && networkUp && serverConfigured && serverVoice != false) add(VoiceVia.ONLINE)
        if (offlineReady) add(VoiceVia.OFFLINE)
    }

    /** Nothing left to try (online failed, or nothing was possible): what the user is offered. */
    fun noEngineProblem(nativeOk: Boolean): VoiceProblem = if (nativeOk) VoiceProblem.MODEL_MISSING else VoiceProblem.UNSUPPORTED
}

/** What the chat does with a transcription — pure, unit-tested in both states. */
object ChatVoice {
    /** Returns the new input box text and the message to send now (null = wait for the user's «إرسال»). */
    fun apply(input: String, heard: String, autoSend: Boolean): Pair<String, String?> {
        val text = heard.trim()
        if (text.isEmpty()) return input to null
        val combined = if (input.isBlank()) text else "${input.trimEnd()} $text"
        return if (autoSend) "" to combined else combined to null
    }
}
