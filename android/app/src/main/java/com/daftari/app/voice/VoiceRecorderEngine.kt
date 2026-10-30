/*
 * دفتري · voice/VoiceRecorderEngine.kt
 * محرّك الصوت: يسجّل من الميكروفون (16 ك.هـ أحادي)، ويتوقف بعد السكوت أو بـ«تم»، ثم يحوّل الكلام إلى نص:
 * عبر الإنترنت أولاً (خادم دفتري ← Whisper من Groq) إن كان متصلاً والإعداد «تلقائي»، وإلا أو عند أي فشل بنموذج الجوال دون إنترنت.
 * 1.1.7: one recording, then the engines of [VoiceEngines.plan] in order — ONLINE (the same audio as a WAV to
 * /api/v1/voice/transcribe, 8 s timeout; the Groq key lives on the server only) then OFFLINE (whisper.cpp in Arabic on a
 * background thread, threads = min(4, cores)). Any online failure (offline, timeout, 5xx, 429, voice disabled) falls
 * through to the offline model; with no model it offers the download. Results reach the listener on the main thread.
 */
package com.daftari.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.daftari.app.DaftariApp
import com.daftari.app.data.ApiException
import kotlin.math.max

class VoiceRecorderEngine(private val ctx: Context) : SpeechEngine {
    override val id: String = SpeechEngine.WHISPER
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false

    override fun isAvailable(): Boolean = true

    /** Online voice is worth trying now (decides whether the mic may record before a model is downloaded). */
    fun onlineLikely(): Boolean {
        VoicePrefs.load(ctx)
        return VoiceEngines.plan(VoicePrefs.mode.value, networkUp(ctx), serverConfigured(), VoicePrefs.serverVoice, offlineReady = false).isNotEmpty()
    }

    override fun start(listener: SpeechEngine.Listener) {
        stopRequested = false; cancelled = false
        VoicePrefs.load(ctx)
        Thread({ run(listener) }, "daftari-voice").start()
    }

    override fun stop() { stopRequested = true }

    override fun cancel() { cancelled = true; stopRequested = true }

    private fun post(block: () -> Unit) = main.post { if (!cancelled) block() }

    private fun serverConfigured(): Boolean = runCatching { (ctx.applicationContext as DaftariApp).graph.api.configured }.getOrDefault(false)

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before starting
    private fun run(listener: SpeechEngine.Listener) {
        val model = VoiceModels.usable(ctx)
        val offlineReady = model != null && WhisperNative.available
        val modelPath = model?.let { VoiceModels.file(ctx, it).absolutePath }
        // load the offline model while the microphone opens (first time ≈ 1 s for «أساسي»), so a fallback is quick
        val loader = if (offlineReady) Thread { WhisperNative.ensure(modelPath!!) }.apply { start() } else null
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); post { listener.onProblem(VoiceProblem.MIC_ERROR) }; return }
        val eos = EndOfSpeech(rate)
        val frame = ShortArray(rate / 50) // 20 ms
        val take = ShortArray(rate * 16)
        var len = 0
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { post { listener.onProblem(VoiceProblem.MIC_ERROR) }; return }
            post { listener.onListening() }
            while (!stopRequested) {
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) { post { listener.onProblem(VoiceProblem.MIC_ERROR) }; return }
                if (n == 0) continue
                val copy = minOf(n, take.size - len)
                System.arraycopy(frame, 0, take, len, copy); len += copy
                val st = eos.feed(frame, n)
                val level = (EndOfSpeech.rms(frame, n) * 8).coerceIn(0.0, 1.0).toFloat()
                post { listener.onLevel(level) }
                if (st == TakeState.DONE || st == TakeState.MAX_LENGTH || st == TakeState.NO_SPEECH || len >= take.size) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
        if (cancelled) return
        // «تم» before any loud frame still transcribes (a quiet speaker); a take with no speech at all does not
        if (!eos.heardSpeech && !(stopRequested && len > rate / 2)) { post { listener.onProblem(VoiceProblem.NOTHING_HEARD) }; return }
        post { listener.onTranscribing() }

        val plan = VoiceEngines.plan(VoicePrefs.mode.value, networkUp(ctx), serverConfigured(), VoicePrefs.serverVoice, offlineReady)
        for (via in plan) {
            if (cancelled) return
            val text = when (via) {
                VoiceVia.ONLINE -> online(take, len)
                VoiceVia.OFFLINE -> {
                    loader?.join()
                    if (!WhisperNative.ensure(modelPath!!)) { post { listener.onProblem(VoiceProblem.MODEL_BROKEN) }; return }
                    WhisperNative.transcribe(FloatArray(len) { take[it] / 32768f }, "ar")
                }
            } ?: continue // online failed → next engine
            if (cancelled) return
            post {
                if (text.isBlank()) listener.onProblem(VoiceProblem.NOTHING_HEARD)
                else { VoiceLast.via = via; listener.onFinal(text) }
            }
            return
        }
        post { listener.onProblem(VoiceEngines.noEngineProblem(WhisperNative.available)) }
    }

    /** The recording through the server; null on any failure (the caller falls back to the offline model). */
    private fun online(pcm: ShortArray, len: Int): String? = try {
        val text = (ctx.applicationContext as DaftariApp).graph.api.transcribeVoice(Wav.encode(pcm, len, 16_000))
        VoicePrefs.serverVoice = true
        text
    } catch (e: ApiException) {
        if (e.code == "voice_disabled" || e.status == 404) VoicePrefs.serverVoice = false // not on this server: stop trying this session
        null
    } catch (_: Exception) { null }

    companion object {
        fun networkUp(ctx: Context): Boolean = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }
}

/** 16-bit PCM mono WAV bytes — pure (unit-tested). */
object Wav {
    fun encode(pcm: ShortArray, len: Int = pcm.size, rate: Int = 16_000): ByteArray {
        val n = len.coerceIn(0, pcm.size)
        val out = ByteArray(44 + n * 2)
        fun s(at: Int, v: String) = v.forEachIndexed { i, ch -> out[at + i] = ch.code.toByte() }
        fun i32(at: Int, v: Int) { for (k in 0..3) out[at + k] = (v shr (8 * k)).toByte() }
        fun i16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        s(0, "RIFF"); i32(4, 36 + n * 2); s(8, "WAVE"); s(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1)
        i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16); s(36, "data"); i32(40, n * 2)
        for (k in 0 until n) i16(44 + 2 * k, pcm[k].toInt())
        return out
    }
}
