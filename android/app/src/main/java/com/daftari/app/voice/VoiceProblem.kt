/*
 * دفتري · voice/VoiceProblem.kt
 * ما قد يحدث أثناء الاستماع (لا إذن، لا محرّك كلام، حزمة العربية غير منزّلة، لم يُسمع كلام…) ورسالة فصحى لكلٍّ منها،
 * والقرار بعد كل خطأ من محرّك الكلام: نجرّب لغة/محرّكاً آخر أم نعرض المشكلة.
 * What can go wrong while listening, the فصحى text shown for each, and the pure decision taken after a
 * SpeechRecognizer error code (codes are Android's documented constants, written as numbers so this file
 * stays pure Kotlin and unit-testable on the JVM).
 */
package com.daftari.app.voice

enum class VoiceProblem(val title: String, val body: String, val offerPack: Boolean = false, val offerSettings: Boolean = false, val offerStore: Boolean = false) {
    NO_PERMISSION(
        "لم يُمنح إذن الميكروفون",
        "يحتاج الإدخال الصوتي إلى إذن الميكروفون. يمكنك منحه من إعدادات التطبيق، أو متابعة الكتابة يدوياً.",
        offerSettings = true,
    ),
    NO_RECOGNIZER(
        "لا يوجد محرّك للتعرّف على الكلام",
        "لم نجد في جهازك خدمة لتحويل الكلام إلى نص. ثبّت «خدمات الكلام من Google» من المتجر، ثم حاول مرة أخرى. ويمكنك الكتابة يدوياً.",
        offerStore = true,
    ),
    OFFLINE_PACK_MISSING(
        "حزمة اللغة العربية غير منزّلة",
        "لكي يعمل الإدخال الصوتي دون إنترنت، نزّل حزمة اللغة العربية للتعرّف على الكلام مرة واحدة. يمكنك متابعة الكتابة يدوياً أثناء التنزيل.",
        offerPack = true,
    ),
    NOTHING_HEARD("لم أسمع كلاماً واضحاً", "تكلّم بعد ظهور «أستمع إليك…» بصوت واضح، مثل: «سجّل على محمد صالح ألفين وخمسمئة»."),
    MIC_ERROR("تعذّر استخدام الميكروفون", "قد يكون تطبيق آخر يستخدم الميكروفون الآن. أغلقه ثم حاول مرة أخرى، أو اكتب يدوياً."),
    BUSY("محرّك الكلام مشغول", "انتظر لحظة ثم اضغط على الميكروفون مرة أخرى."),
    FAILED("تعذّر التعرّف على الكلام", "حدث خطأ في محرّك الكلام. حاول مرة أخرى، أو اكتب المبلغ والاسم يدوياً."),
}

/** After an error: try the next (engine, language) attempt, or stop and show a problem. */
sealed interface VoiceNext {
    data object TryNext : VoiceNext
    data class Show(val problem: VoiceProblem) : VoiceNext
}

object VoiceErrors {
    // android.speech.SpeechRecognizer.ERROR_* (API 8…33)
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val BUSY = 8
    const val INSUFFICIENT_PERMISSIONS = 9
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
    const val CANNOT_CHECK_SUPPORT = 14

    /**
     * Network / language errors while offline is preferred mean «no offline Arabic model here»: try the next
     * (engine, language) attempt, and when none is left, ask for the offline pack. Silence is not an engine
     * problem, so it never moves on to another engine.
     */
    fun next(code: Int, hasMoreAttempts: Boolean): VoiceNext = when (code) {
        LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE, NETWORK, NETWORK_TIMEOUT, SERVER, SERVER_DISCONNECTED, CANNOT_CHECK_SUPPORT ->
            if (hasMoreAttempts) VoiceNext.TryNext else VoiceNext.Show(VoiceProblem.OFFLINE_PACK_MISSING)
        SPEECH_TIMEOUT, NO_MATCH -> VoiceNext.Show(VoiceProblem.NOTHING_HEARD)
        AUDIO -> VoiceNext.Show(VoiceProblem.MIC_ERROR)
        INSUFFICIENT_PERMISSIONS -> VoiceNext.Show(VoiceProblem.NO_PERMISSION)
        BUSY, TOO_MANY_REQUESTS -> VoiceNext.Show(VoiceProblem.BUSY)
        else -> if (hasMoreAttempts) VoiceNext.TryNext else VoiceNext.Show(VoiceProblem.FAILED)
    }

    /** Recognizer languages, in order: Yemeni Arabic, then Saudi Arabic (the offline pack most phones carry). */
    val LANGUAGES = listOf("ar-YE", "ar-SA")

    /** Of several alternatives, the first that the parser can get an amount from; else the first. */
    fun pickBest(alternatives: List<String>): String {
        val clean = alternatives.map { it.trim() }.filter { it.isNotEmpty() }
        return clean.firstOrNull { VoiceEntryParser.parse(it, emptyList()).amount != null } ?: clean.firstOrNull().orEmpty()
    }
}
