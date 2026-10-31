/*
 * دفتري · chat/DialectLexicon.kt
 * جدول لهجات البيع اليمنية (صنعاء/تعز/عدن) وما يقابلها: دين، سداد، كامل/نص الرصيد، كشف، رصيد، عملة — ملف واحد سهل التوسيع.
 * 1.1.8 (Madyan: «اضف كل اللهجات اليمنية الاساسية حق البيع»). Data-driven: each row = an intent + its spoken forms + where
 * the form comes from. Both offline parsers (chat [Nlu] and the mic's VoiceEntryParser) read their word sets from here, so a
 * new word is one line. Forms are written as people type / Whisper spells them; matching is on [ArabicText.normalize]d
 * words (ة/ه، ى/ي، أ/إ/آ/ا، tashkeel and shadda removed: «سدّد» = «سدد»).
 * Sources: M = Madyan's own list (Oct 31, 2026); W = seen on the web as Yemeni shop / money speech (شُكُك = بالدين in the
 * proverb «إن نطق الديك، شُكُك أدّيك»؛ زلط = فلوس؛ أدّى/إندى = أعطى؛ شلّ = أخذ — Yemeni word lists on mo3jam / Facebook
 * «250 كلمة يمنية»); C = common Yemeni/Gulf shop speech, not checked against a source (marked so on purpose).
 * Unit-tested (Features118Test.dialect*).
 */
package com.daftari.app.chat

object DialectLexicon {
    enum class Intent {
        /** a new debt: «سجّل عليه», «خلّيه عليه», «شُكُك» */
        DEBT,
        /** he took goods (a debt when someone else is the subject): «شلّ», «خذا» */
        TOOK,
        /** money came in: «سدّد», «قضّى», «ورّد» */
        PAYMENT,
        /** the whole balance: «كامل», «كاملاً», «الكل», «حقه كله» */
        FULL,
        /** half the balance: «دفع نصه» */
        HALF,
        /** «طلّع / نزّل / اطبع / خرّج / هات / ورّني لي …» — give me (the statement) */
        SHOW,
        /** what is shown: «كشف», «فاتورة»; «حساب» only together with a SHOW verb */
        STATEMENT,
        /** «كم عليه؟», «حسابه كم؟» */
        BALANCE,
        /** the Yemeni rial written short: «ر.ي» */
        RIAL,
    }

    data class Row(val intent: Intent, val forms: List<String>, val source: String)

    val ROWS: List<Row> = listOf(
        Row(Intent.DEBT, listOf("سجل عليه", "سجّل عليه", "قيد عليه", "قيّد عليه", "اكتب عليه", "حطه عليه", "حطّه عليه"), "M"),
        Row(Intent.DEBT, listOf("خليه عليه", "خلّيه عليه", "خليها عليه", "خلّي عليه", "دين", "ديّن", "دَين", "آجل", "بالآجل", "بالدين", "على الحساب", "عالحساب"), "M"),
        Row(Intent.DEBT, listOf("شكك", "شُكُك", "بالشكك", "شك", "شكّ", "بالشك"), "W"),
        Row(Intent.DEBT, listOf("اديته", "ادّيته", "اديناه", "انديته", "اندينا له", "عطيته", "اعطيته", "سلفته", "داينته"), "W"),
        Row(Intent.TOOK, listOf("اخذ", "أخذ", "خذا", "خذ", "شل", "شلّ", "شال", "اشترى", "شرى", "ياخذ", "يشل"), "M"),
        Row(Intent.PAYMENT, listOf("سدد", "سدّد", "دفع", "قضى", "قضّى", "قضا", "خلص", "خلّص", "صفى", "صفّى", "ورد", "ورّد", "جاب", "سلم", "سلّم", "رجع", "رجّع"), "M"),
        Row(Intent.PAYMENT, listOf("صفى حسابه", "صفّى حسابه", "خلص حسابه", "خلّص حسابه", "قضى اللي عليه"), "C"),
        Row(Intent.FULL, listOf("كامل", "كاملا", "كاملاً", "كامله", "بالكامل", "كله", "كلها", "الكل", "خالص", "حقه كامل", "اللي عليه كامل", "الي عليه كاملا"), "M"),
        Row(Intent.HALF, listOf("نصه", "نصها", "نص اللي عليه", "نص الي عليه", "النص"), "C"),
        Row(Intent.SHOW, listOf("طلع", "طلّع", "طلعلي", "نزل", "نزّل", "نزللي", "اطبع", "اطبعلي", "خرج", "خرّج", "خرجلي", "اخرج"), "M"),
        Row(Intent.SHOW, listOf("هات", "هاتلي", "ورني", "ورّني", "وريني", "عطني", "اعطني", "ارسل", "ابعث", "جيب", "جيبلي"), "C"),
        Row(Intent.STATEMENT, listOf("كشف", "الكشف", "كشفه", "كشفها", "كشف حساب", "فاتورة", "الفاتورة", "فاتورته", "فاتورتها"), "M"),
        Row(Intent.BALANCE, listOf("كم عليه", "كم باقي عليه", "حسابه كم", "كم حسابه", "كم له", "كم بقي عليه", "قديش عليه"), "M"),
        Row(Intent.RIAL, listOf("ر.ي", "ر.ي.", "ري"), "C"),
    )

    /** Intents whose multi-word forms lead with the word that carries the meaning («خلّيه عليه», «صفّى حسابه», «كشف حساب»). */
    private val leadCounts = setOf(Intent.DEBT, Intent.TOOK, Intent.PAYMENT, Intent.SHOW, Intent.STATEMENT)

    /** The single words of [intent]: one-word forms, plus the first word of multi-word forms for [leadCounts]. */
    fun words(intent: Intent): Set<String> =
        ROWS.filter { it.intent == intent }.flatMap { r -> r.forms.map { ArabicText.normalize(it) } }
            .mapNotNull { f -> if (' ' !in f) f else if (intent in leadCounts) f.substringBefore(' ') else null }.toSet()

    /** Multi-word forms, normalized, padded with spaces for a contains() on a padded sentence. */
    fun phrases(intent: Intent): List<String> =
        ROWS.filter { it.intent == intent }.flatMap { r -> r.forms.map { ArabicText.normalize(it) } }.filter { ' ' in it }.map { " $it " }

    /** The sentence (normalized words) says [intent] — by a single word or a whole phrase. */
    fun says(intent: Intent, w: List<String>): Boolean {
        val ws = words(intent)
        if (w.any { it in ws }) return true
        val j = " " + w.joinToString(" ") + " "
        return phrases(intent).any { j.contains(it) }
    }
}
