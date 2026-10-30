/*
 * دفتري · voice/VoiceModel.kt
 * نماذج الصوت دون إنترنت (Whisper بصيغة ggml من whisper.cpp): الحجم، الرابط، بصمة sha256، وأين تُحفظ في الجوال.
 * The offline speech models (OpenAI Whisper, MIT, converted to ggml by whisper.cpp — Hugging Face ggerganov/whisper.cpp).
 * Not bundled in the APK: downloaded once inside the app on the first mic tap, checked against the sha256 below, kept in
 * the app's private files (filesDir/voice). «أساسي» (base, ~57 MB) is the default; «خفيف» (tiny, ~31 MB) is faster but
 * less accurate. [VoiceModels] holds the user's choice and the file checks; the catalog itself is pure Kotlin.
 */
package com.daftari.app.voice

import android.content.Context
import java.io.File

enum class VoiceModel(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    /** the name shown in the app */
    val label: String,
    val note: String,
) {
    BASE(
        "ggml-base-q5_1.bin", 59_707_625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        "أساسي", "أدقّ في فهم العربية، والتعرّف أبطأ قليلاً.",
    ),
    TINY(
        "ggml-tiny-q5_1.bin", 32_152_673L, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        "خفيف", "أسرع وأصغر، لكنه أقلّ دقة في الأسماء والمبالغ.",
    );

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    /** «57 م.ب» — mebibytes as Android's file manager shows them, Western digits like the rest of the app's amounts */
    val sizeText: String get() = "${(bytes + 524_288) / 1_048_576} م.ب"

    companion object {
        val DEFAULT = BASE
        fun of(name: String?): VoiceModel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** What the mic does on a tap — pure, unit-tested. */
enum class VoiceStart { OFFER_DOWNLOAD, DOWNLOADING, ASK_PERMISSION, LISTEN, UNSUPPORTED }

object VoiceStarts {
    /**
     * First something to listen with — the offline model, or online voice ([online]: connected, «تلقائي», server allows) —
     * then the permission. No point asking for the mic before either exists.
     */
    fun decide(nativeOk: Boolean, modelReady: Boolean, downloading: Boolean, micGranted: Boolean, online: Boolean = false): VoiceStart {
        val usable = (nativeOk && modelReady) || online
        return when {
            usable && micGranted -> VoiceStart.LISTEN
            usable -> VoiceStart.ASK_PERMISSION
            !nativeOk -> VoiceStart.UNSUPPORTED
            downloading -> VoiceStart.DOWNLOADING
            else -> VoiceStart.OFFER_DOWNLOAD
        }
    }
}

/** The chosen model and its files in the app's private storage. */
object VoiceModels {
    private const val PREFS = "daftari_voice"
    private const val KEY = "model"

    fun dir(ctx: Context): File = File(ctx.filesDir, "voice").apply { mkdirs() }
    fun file(ctx: Context, m: VoiceModel): File = File(dir(ctx), m.fileName)
    fun partFile(ctx: Context, m: VoiceModel): File = File(dir(ctx), m.fileName + ".part")

    fun chosen(ctx: Context): VoiceModel = VoiceModel.of(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    fun choose(ctx: Context, m: VoiceModel) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply() }

    /** Downloaded and complete (its sha256 was checked before the file got its final name). */
    fun isReady(ctx: Context, m: VoiceModel = chosen(ctx)): Boolean = file(ctx, m).let { it.isFile && it.length() == m.bytes }

    /** The model that is on the phone, preferring the chosen one (a downloaded «خفيف» still works if «أساسي» was chosen later). */
    fun usable(ctx: Context): VoiceModel? = (listOf(chosen(ctx)) + VoiceModel.entries).firstOrNull { isReady(ctx, it) }

    fun delete(ctx: Context) {
        WhisperNative.unload()
        VoiceModel.entries.forEach { file(ctx, it).delete(); partFile(ctx, it).delete() }
    }

    /** Bytes used by the voice models on the phone (finished + partial files). */
    fun usedBytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
}
