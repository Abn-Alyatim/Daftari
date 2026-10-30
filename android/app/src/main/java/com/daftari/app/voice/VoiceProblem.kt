/*
 * دفتري · voice/VoiceProblem.kt
 * ما قد يُعرض للمستخدم أثناء الإدخال الصوتي (لا إذن، لم يُسمع كلام، الميكروفون مشغول، النموذج غير منزّل أو تالف، جهاز غير مدعوم)
 * برسالة فصحى، مع زر مناسب لكلٍّ منها. الكتابة اليدوية متاحة دائماً.
 * What the user can be shown during voice entry (1.1.7, offline Whisper only), each in فصحى with its one action
 * (app settings for the permission, the model download for a missing/broken model). Pure Kotlin.
 */
package com.daftari.app.voice

enum class VoiceProblem(val title: String, val body: String, val offerSettings: Boolean = false, val offerDownload: Boolean = false) {
    NO_PERMISSION(
        "لم يُمنح إذن الميكروفون",
        "يحتاج الإدخال الصوتي إلى إذن الميكروفون. يمكنك منحه من إعدادات التطبيق، أو متابعة الكتابة يدوياً.",
        offerSettings = true,
    ),
    NOTHING_HEARD("لم أسمع كلاماً واضحاً", "تكلّم بعد ظهور «أستمع إليك…» بصوت واضح وقريب من الجوال، مثل: «سجّل على محمد صالح ألفين وخمسمئة»."),
    MIC_ERROR("تعذّر استخدام الميكروفون", "قد يكون تطبيق آخر يستخدم الميكروفون الآن (مكالمة أو تسجيل). أغلقه ثم حاول مرة أخرى، أو اكتب يدوياً."),
    MODEL_MISSING(
        "نموذج الصوت غير منزّل",
        "يعمل الإدخال الصوتي دون إنترنت بعد تنزيل نموذج الصوت مرة واحدة. يمكنك متابعة الكتابة يدوياً.",
        offerDownload = true,
    ),
    MODEL_BROKEN(
        "تعذّر فتح نموذج الصوت",
        "يبدو أن ملف نموذج الصوت تالف. احذفه ونزّله من جديد، أو تابع الكتابة يدوياً.",
        offerDownload = true,
    ),
    UNSUPPORTED("الإدخال الصوتي غير مدعوم في هذا الجهاز", "معالج هذا الجوال لا يدعم نموذج الصوت دون إنترنت. يمكنك متابعة الكتابة يدوياً."),
}

object VoiceErrors {
    /** Of several alternatives, the first that the parser can get an amount from; else the first. */
    fun pickBest(alternatives: List<String>): String {
        val clean = alternatives.map { it.trim() }.filter { it.isNotEmpty() }
        return clean.firstOrNull { VoiceEntryParser.parse(it, emptyList()).amount != null } ?: clean.firstOrNull().orEmpty()
    }
}
