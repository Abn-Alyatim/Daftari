/*
 * دفتري · voice/SpeechEngine.kt
 * واجهة موحّدة لمحرّك تحويل الكلام إلى نص، حتى لا يهتم المحلّل ولا الواجهة بأي محرّك يعمل.
 * The pluggable speech-to-text seam: the parser and the UI only see text. Today one engine is shipped —
 * [AndroidRecognizerEngine] (the phone's built-in recognizer, offline preferred). A second, own offline engine
 * (planned: Whisper tiny, downloaded once on first use) plugs in here by implementing [SpeechEngine] and being
 * added to [SpeechEngines.pick]; nothing else changes.
 */
package com.daftari.app.voice

import android.content.Context

interface SpeechEngine {
    /** stable id, e.g. for a «force this engine» setting */
    val id: String

    /** True when this engine can run on this phone right now (installed / downloaded). */
    fun isAvailable(): Boolean

    /** Start listening; results arrive on [listener] on the main thread. The caller holds RECORD_AUDIO already. */
    fun start(listener: Listener)

    /** «تم»: stop and deliver the final text. */
    fun stop()

    /** Close without a result. */
    fun cancel()

    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onProblem(problem: VoiceProblem)

        companion object {
            val NONE = object : Listener {
                override fun onListening() {}
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {}
                override fun onProblem(problem: VoiceProblem) {}
            }
        }
    }

    companion object {
        const val ANDROID = "android"
    }
}

object SpeechEngines {
    /** The engine to use: the first available one in preference order; the Android recognizer reports its own problems. */
    fun pick(ctx: Context): SpeechEngine {
        val candidates: List<SpeechEngine> = listOf(AndroidRecognizerEngine(ctx))
        return candidates.firstOrNull { it.isAvailable() } ?: candidates.first()
    }
}
