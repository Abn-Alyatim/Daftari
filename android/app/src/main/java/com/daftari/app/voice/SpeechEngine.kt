/*
 * دفتري · voice/SpeechEngine.kt
 * واجهة موحّدة لمحرّك تحويل الكلام إلى نص، حتى لا يهتم المحلّل ولا الواجهة بأي محرّك يعمل.
 * The pluggable speech-to-text seam: the parser and the UI only see text. Since 1.1.7 one engine ships —
 * [VoiceRecorderEngine]: online first (Daftari server → Groq Whisper) when connected, else our own offline Whisper model
 * (whisper.cpp), downloaded once inside the app. Google's recognizer was
 * removed in 1.1.7 at the owner's request. Another engine plugs in by implementing [SpeechEngine] and [SpeechEngines.pick].
 */
package com.daftari.app.voice

import android.content.Context

interface SpeechEngine {
    /** stable id, e.g. for a «force this engine» setting */
    val id: String

    /** True when this engine can run on this phone right now (informational only — the mic is never hidden by it). */
    fun isAvailable(): Boolean

    /** Start listening; results arrive on [listener] on the main thread. The caller holds RECORD_AUDIO already. */
    fun start(listener: Listener)

    /** «تم»: stop recording and deliver the text. */
    fun stop()

    /** Close without a result. */
    fun cancel()

    interface Listener {
        fun onListening()
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onProblem(problem: VoiceProblem)
        /** microphone level 0…1 while recording (for the pulsing mic) */
        fun onLevel(level: Float) {}
        /** recording ended; the model is turning it into text («جارٍ التعرّف…») */
        fun onTranscribing() {}

        companion object {
            val NONE = object : Listener {
                override fun onListening() {}
                override fun onFinal(text: String) {}
                override fun onProblem(problem: VoiceProblem) {}
            }
        }
    }

    companion object {
        const val WHISPER = "whisper"
    }
}

object SpeechEngines {
    fun pick(ctx: Context): VoiceRecorderEngine = VoiceRecorderEngine(ctx)
}
