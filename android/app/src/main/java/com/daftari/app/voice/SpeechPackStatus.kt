/*
 * دفتري · voice/SpeechPackStatus.kt
 * حالة حزمة العربية للتعرّف على الكلام دون إنترنت (فحص، تنزيل في الخلفية، جاهزة، تعذّر) ونصّها بالفصحى،
 * واختيار رمز اللغة العربية الذي يدعمه الجهاز.
 * The offline Arabic speech-pack state shown while it downloads in the background, its فصحى line, and the pure choice
 * of which Arabic locale to ask the phone for (ar-YE, then ar-SA, then any ar-*). Pure Kotlin, unit-tested.
 */
package com.daftari.app.voice

sealed interface PackStatus {
    data object Idle : PackStatus
    data object Checking : PackStatus
    /** [percent] null = the phone gives no progress (Android 13) */
    data class Downloading(val percent: Int?) : PackStatus
    data object Scheduled : PackStatus
    data object Ready : PackStatus
    data object Failed : PackStatus

    /** The small status line (null = nothing to show). */
    val line: String?
        get() = when (this) {
            Idle -> null
            Checking -> "جارٍ التحقق من حزمة العربية…"
            is Downloading -> "جارٍ تنزيل حزمة العربية في الخلفية…" + (percent?.let { " \u2066$it%\u2069" } ?: "") + " يمكنك متابعة الكتابة."
            Scheduled -> "جُدوِل تنزيل حزمة العربية، وسيكتمل تلقائياً عند توفّر الإنترنت. يمكنك متابعة الكتابة."
            Ready -> "حزمة العربية جاهزة ✅ اضغط على الميكروفون وتكلّم."
            Failed -> "تعذّر تنزيل حزمة العربية في الخلفية. افتح إعدادات الكلام من Google لتنزيلها."
        }

    val busy: Boolean get() = this is Checking || this is Downloading || this is Scheduled
}

object ArabicLocales {
    /** The Arabic locale to ask for, from what the phone lists: ar-YE, then ar-SA, then any other ar-*; null if none. */
    fun pick(languages: Collection<String>): String? {
        val norm = languages.map { it.replace('_', '-') }
        fun has(tag: String) = norm.firstOrNull { it.equals(tag, ignoreCase = true) }
        return VoiceErrors.LANGUAGES.firstNotNullOfOrNull { has(it) }
            ?: norm.firstOrNull { it.lowercase() == "ar" || it.lowercase().startsWith("ar-") }
    }
}
