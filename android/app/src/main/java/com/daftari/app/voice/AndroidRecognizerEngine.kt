/*
 * دفتري · voice/AndroidRecognizerEngine.kt
 * غلاف محرّك الكلام المدمج في أندرويد (android.speech.SpeechRecognizer) للعربية مع تفضيل العمل دون إنترنت.
 * Wraps Android's built-in SpeechRecognizer: EXTRA_PREFER_OFFLINE = true, language ar-YE then ar-SA, partial results
 * for the live transcript. Attempts, in order: the phone's on-device recognizer (Android 12+, when the phone has one),
 * then the default recognizer — each with ar-YE then ar-SA (plus the Arabic locale the pack check found, on-device). No cloud API of ours, no library, nothing sent by the app.
 * Must be used from the main thread (SpeechRecognizer's own rule).
 */
package com.daftari.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class AndroidRecognizerEngine(private val ctx: Context) : SpeechEngine {
    override val id: String = SpeechEngine.ANDROID
    private var listener: SpeechEngine.Listener = SpeechEngine.Listener.NONE

    private data class Attempt(val onDevice: Boolean, val language: String)

    private var recognizer: SpeechRecognizer? = null
    private var attempts: List<Attempt> = emptyList()
    private var index = 0
    private var stopped = false

    override fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false) ||
            (Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false))

    /** Starts listening (asks for nothing: the caller has the RECORD_AUDIO permission already). */
    override fun start(listener: SpeechEngine.Listener) {
        this.listener = listener
        stopped = false
        val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
        val default = runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)
        val langs = (VoiceErrors.LANGUAGES + listOfNotNull(SpeechPack.onDeviceLanguage)).distinct()
        attempts = buildList {
            if (onDevice) langs.forEach { add(Attempt(true, it)) }
            if (default) VoiceErrors.LANGUAGES.forEach { add(Attempt(false, it)) }
        }
        index = 0
        if (attempts.isEmpty()) { listener.onProblem(VoiceProblem.NO_RECOGNIZER); return }
        run()
    }

    /** «تم»: stop listening and deliver what was heard. */
    override fun stop() { runCatching { recognizer?.stopListening() } }

    /** Close without a result (sheet dismissed). */
    override fun cancel() {
        stopped = true
        runCatching { recognizer?.cancel() }
        release()
    }

    private fun release() { runCatching { recognizer?.destroy() }; recognizer = null }

    private fun run() {
        release()
        val a = attempts[index]
        val r = runCatching {
            if (a.onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        }.getOrNull()
        if (r == null) { failed(VoiceErrors.CLIENT); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (!stopped) listener.onListening() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (!stopped && t.isNotBlank()) listener.onPartial(t)
            }
            override fun onResults(results: Bundle?) {
                if (stopped) return
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val best = VoiceErrors.pickBest(all)
                release()
                if (best.isBlank()) listener.onProblem(VoiceProblem.NOTHING_HEARD) else { SpeechPack.used(); listener.onFinal(best) }
            }
            override fun onError(error: Int) { if (!stopped) failed(error) }
        })
        runCatching { r.startListening(intentFor(a.language)) }.onFailure { failed(VoiceErrors.CLIENT) }
    }

    private fun failed(code: Int) {
        when (val n = VoiceErrors.next(code, index + 1 < attempts.size)) {
            VoiceNext.TryNext -> { index++; run() }
            is VoiceNext.Show -> { release(); listener.onProblem(n.problem) }
        }
    }

    companion object {
        fun intentFor(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // a short pause ends the sentence; a long sentence is still heard to its end
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
    }
}
