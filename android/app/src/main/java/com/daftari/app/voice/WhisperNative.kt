/*
 * دفتري · voice/WhisperNative.kt
 * الربط مع مكتبة whisper.cpp الأصلية (libdaftari_whisper.so): تحميل النموذج مرة واحدة وتحويل الصوت إلى نص.
 * Kotlin side of the JNI bridge (cpp/whisper_jni.cpp). The library is loaded lazily and guarded: on a JVM test or an
 * unsupported CPU [available] is false and the UI says so instead of crashing. One model stays loaded while the app
 * lives (loading «أساسي» takes about a second); [unload] frees it (model deleted / changed). Not thread-safe by itself:
 * [VoiceRecorderEngine] calls it from its single worker thread. 1.1.8: an optional hint (customer names + phrases) is passed
 * to whisper.cpp as initial_prompt.
 */
package com.daftari.app.voice

object WhisperNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("daftari_whisper") }.isSuccess }

    private var handle = 0L
    private var loadedPath: String? = null

    /** Loads [path] (once); false when the file is not a usable model. */
    @Synchronized fun ensure(path: String): Boolean {
        if (!available) return false
        if (handle != 0L && loadedPath == path) return true
        unload()
        handle = runCatching { nativeLoad(path) }.getOrDefault(0L)
        loadedPath = path.takeIf { handle != 0L }
        return handle != 0L
    }

    /** 16 kHz mono samples in -1…1 → text ("" = nothing recognised). [prompt] (1.1.8) = whisper's initial_prompt, "" = none. */
    @Synchronized fun transcribe(pcm: FloatArray, language: String = "ar", threads: Int = defaultThreads(), prompt: String = ""): String =
        if (handle == 0L) "" else runCatching { nativeTranscribe(handle, pcm, language, threads, prompt) }.getOrDefault("")

    @Synchronized fun unload() {
        if (handle != 0L && available) runCatching { nativeFree(handle) }
        handle = 0L; loadedPath = null
    }

    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private external fun nativeLoad(path: String): Long
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: String, threads: Int, prompt: String): String
    private external fun nativeFree(handle: Long)
}
