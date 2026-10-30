/*
 * دفتري · voice/EndOfSpeech.kt
 * متى نتوقف عن التسجيل: بعد سكوت قصير يلي الكلام، أو 15 ثانية كحد أقصى، أو إن لم يتكلم أحد خلال 7 ثوانٍ.
 * Energy-based end of speech for the recorder (pure Kotlin, unit-tested): the first 0.3 s set the room's noise floor;
 * speech = a frame clearly louder than that floor; after speech, [silenceMs] of quiet ends the take; [maxMs] caps it;
 * no speech within [noSpeechMs] ends it empty. «تم» in the UI ends it at any time.
 */
package com.daftari.app.voice

import kotlin.math.max
import kotlin.math.sqrt

enum class TakeState { WAITING, SPEAKING, DONE, NO_SPEECH, MAX_LENGTH }

class EndOfSpeech(
    private val sampleRate: Int = 16_000,
    private val silenceMs: Int = 1_300,
    private val maxMs: Int = 15_000,
    private val noSpeechMs: Int = 7_000,
    private val calibrateMs: Int = 300,
    /** RMS (0…1) that always counts as speech-loud enough, whatever the floor */
    private val minSpeechRms: Double = 0.02,
) {
    private var elapsedMs = 0.0
    private var floor = 0.0
    private var floorFrames = 0
    private var quietMs = 0.0
    var state = TakeState.WAITING
        private set
    private var spoke = false
    /** some frame was loud enough to be speech */
    val heardSpeech: Boolean get() = spoke

    /** Feeds one frame of 16-bit samples ([n] of them); returns the state after it. */
    fun feed(samples: ShortArray, n: Int = samples.size): TakeState {
        if (state == TakeState.DONE || state == TakeState.NO_SPEECH || state == TakeState.MAX_LENGTH || n <= 0) return state
        val r = rms(samples, n)
        val frameMs = n * 1000.0 / sampleRate
        elapsedMs += frameMs
        if (elapsedMs <= calibrateMs) {
            floor = (floor * floorFrames + r) / (floorFrames + 1); floorFrames++
            return state
        }
        val loud = r > max(minSpeechRms, floor * 3.0)
        if (loud) { spoke = true; quietMs = 0.0; state = TakeState.SPEAKING }
        else if (spoke) quietMs += frameMs
        state = when {
            elapsedMs >= maxMs -> TakeState.MAX_LENGTH
            spoke && quietMs >= silenceMs -> TakeState.DONE
            !spoke && elapsedMs >= noSpeechMs -> TakeState.NO_SPEECH
            spoke -> TakeState.SPEAKING
            else -> TakeState.WAITING
        }
        return state
    }

    companion object {
        fun rms(samples: ShortArray, n: Int): Double {
            var sum = 0.0
            for (i in 0 until n) { val v = samples[i] / 32768.0; sum += v * v }
            return sqrt(sum / n)
        }
    }
}
