/*
 * دفتري · voice/VoiceEntryParser.kt
 * فهم جملة منطوقة وتحويلها إلى مسودة قيد: الزبون، المبلغ، النوع (دين / سداد)، العملة، الملاحظة.
 * Turns one speech transcript into a [VoiceDraft] for the money sheet. Pure Kotlin, offline, deterministic:
 * no model, no network, no randomness. Anything it is not sure of is left empty for the user — it never invents
 * a value. The draft is only a pre-fill: the user always reviews and taps «حفظ» himself (never auto-saved).
 *
 * Understands فصحى + Yemeni/Gulf phrasing, e.g.
 *   «علي أحمد عليه خمسة آلاف ريال»      → دين، علي أحمد، 5000
 *   «سجل على محمد صالح ألفين وخمسمية»   → دين، محمد صالح، 2500
 *   «سدد خالد ثلاثة آلاف»               → سداد، خالد، 3000
 *   «أخذ مني عبدالله مية دولار»          → دين، عبدالله، 100، USD
 * A bare «ريال» leaves the currency empty (the account's own currency is used): only «سعودي / يمني / دولار» set it.
 * Unit-tested in VoiceEntryParserTest (no Android needed).
 */
package com.daftari.app.voice

import com.daftari.app.chat.ArabicText
import com.daftari.app.core.Currency
import java.math.BigDecimal

/** What the sentence says the entry is; null in [VoiceDraft.kind] = not clear, the user picks. */
enum class VoiceKind { DEBT, PAYMENT }

/** A customer the parser can match against (an existing account): its id and every label it is known by. */
data class VoiceCustomer(val id: Long, val name: String)

/**
 * The parsed pre-fill. Every field may be empty:
 * - [customer] set + [customerSure] = the spoken name is this account exactly; [customerSure] false = «هل تقصد …؟» (close match, user confirms)
 * - [options] = several accounts fit (or close suggestions), the user picks one
 * - [newName] = the spoken name as said, when no account is it (offered as a new customer)
 */
data class VoiceDraft(
    val transcript: String,
    val kind: VoiceKind? = null,
    val amount: BigDecimal? = null,
    val currency: Currency? = null,
    val customer: VoiceCustomer? = null,
    val customerSure: Boolean = false,
    val options: List<VoiceCustomer> = emptyList(),
    val newName: String = "",
    val note: String = "",
) {
    /** The amount as the sheet's amount field wants it («2500», «1500.5»), or "" when none was understood. */
    val amountText: String get() = amount?.stripTrailingZeros()?.toPlainString().orEmpty()
    val isEmpty: Boolean get() = kind == null && amount == null && customer == null && options.isEmpty() && newName.isBlank() && note.isBlank()
}

object VoiceEntryParser {
    private fun ns(vararg w: String): Set<String> = w.map { ArabicText.normalize(it) }.toSet()

    /** «سدد / دفع / عطاني …»: money came in. */
    private val payVerbs = ns(
        "سدد", "سددت", "سدده", "سددها", "سددلي", "سددنا", "يسدد", "تسديد", "سداد", "دفع", "دفعت", "دفعه", "دفعها", "دفعلي", "دفعوا", "دفعو", "يدفع",
        "سلم", "سلمني", "سلملي", "سلمنا", "رجع", "رجعلي", "ارجع", "رد", "ردلي", "قضى", "قضا", "قضالي", "ورد", "وصل", "وصلني", "وصلنا", "واصل",
        "عطاني", "اعطاني", "اداني", "حول", "حولي", "حوللي", "حولنا", "جاب", "جابلي", "قسط", "دفعة", "سدد له",
    )
    /** «أخذتُ / استلمتُ / قبضتُ» + «من / منه»: money came in. */
    private val receivedVerbs = ns("اخذت", "خذيت", "استلمت", "قبضت", "حصلت", "تسلمت", "استلمنا", "قبضنا", "اخذنا")
    /** «عليه / دين / اشترى / استلف …»: a new debt. */
    private val debtVerbs = ns(
        "عليه", "عليها", "عليهم", "دين", "دينه", "ديون", "مديون", "بالدين", "اجل", "بالاجل", "استلف", "تسلف", "سلف", "سلفت", "سلفه", "سلفته", "تدين", "داين", "اداين",
        "اشترى", "اشتري", "شرى", "شل", "شال", "اعطيت", "عطيت", "اعطيته", "عطيته", "اديت", "اديته", "قرضته", "اقرضته", "اقترض", "تقرض", "سجل عليه",
    )
    /** «أخذ / خذ» (he took) — a debt; «أخذتُ» is in [receivedVerbs]. */
    private val tookVerbs = ns("اخذ", "خذ", "ياخذ")
    /** «سجل / اكتب / قيد …»: says write it, not which way. */
    private val genericVerbs = ns("سجل", "سجلي", "سجله", "سجلها", "قيد", "قيدي", "قيده", "اكتب", "اكتبي", "اكتبه", "حط", "حطي", "حطه", "ضيف", "اضف", "سجللي", "قيدلي")
    private val currencyWords = ns("ريال", "ريالات", "سعودي", "سعوديه", "يمني", "دولار", "دولارات", "بالدولار", "بالسعودي", "باليمني", "بالريال", "دولارا", "امريكي")
    private val preps = ns("من", "عند", "باسم", "لـ", "الى", "لصالح")
    private val pronouns = ns("منه", "منها", "مني", "منا", "مننا", "له", "لها", "لي", "لنا", "عنده", "عليه", "عليها", "عليهم")
    private val noteMarkers = ns("حق", "قيمه", "بقيمه", "ثمن", "سعر", "عشان", "علشان", "لاجل", "مقابل", "ملاحظه", "عن")
    private val fillers = ns(
        "يا", "دفتري", "لو", "سمحت", "فضلك", "الاخ", "الاخت", "اخوي", "اخونا", "الزبون", "زبون", "العميل", "عميل", "حساب", "حسابه", "مبلغ", "بمبلغ",
        "اليوم", "امس", "البارحه", "الحين", "الان", "هذا", "هذي", "بس", "و", "يعني", "طيب", "تمام", "ايوه", "خلاص", "جديد", "جديده", "اسمه", "اللي", "الي",
        "ما", "كل", "كامل", "كاملا", "المبلغ", "فلوس", "مصاري", "زلط", "قد", "لقد", "انه", "ان", "انو", "عاد", "كمان", "برضو", "ايضا", "منك", "ب", "في", "هو", "هي",
        "عندي", "عندنا", "لديه", "كم", "سجلت", "تم", "الدين", "السداد", "التسديد",
    )
    private val nameJoiners = ns("عبد", "ابو", "ام", "بن", "بنت", "ال")

    private enum class Role { AMOUNT, CURRENCY, VERB, PREP, PRONOUN, NOTE, FILLER, NAME }

    private class Tk(val raw: String, val n: String) {
        var role = Role.NAME
        var nameValue = raw
    }

    private val allVerbs by lazy { payVerbs + receivedVerbs + debtVerbs + tookVerbs + genericVerbs }

    /** «وأخذ» / «وسدد»: the joiner «و» glued to a verb. */
    private fun verbCore(n: String): String = if (n.length > 3 && n[0] == 'و' && n !in allVerbs && n.substring(1) in allVerbs) n.substring(1) else n

    private val diacritics = Regex("[\u064B-\u065F\u0670\u0640]")

    /** Raw pieces next to their normalized words (one raw piece can give two words: «بـ5000» → «ب»، «5000»). */
    private fun tokens(text: String): List<Tk> {
        val out = ArrayList<Tk>()
        val pieces = text.replace(Regex("[،؛؟!?:«»\"()\\[\\].,](?![0-9])"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        for (p in pieces) {
            val ws = ArabicText.words(p)
            if (ws.size == 1) out += Tk(diacritics.replace(p, "").trim('-', 'ـ'), verbCore(ws[0]))
            else ws.forEach { out += Tk(it, it) }
        }
        return out
    }

    /** «على» typed with the final ى is the preposition; «علي» is the name. */
    private fun isAlaPrep(t: Tk) = t.n == "علي" && t.raw.endsWith("ى")

    fun parse(transcript: String, customers: List<VoiceCustomer>): VoiceDraft {
        val tk = tokens(transcript)
        if (tk.isEmpty()) return VoiceDraft(transcript)
        val words = tk.map { it.n }

        // ---- «محل 12»: a shop number is the account, not the amount
        val shopIdx = HashSet<Int>()
        for (i in 0 until tk.size - 1) if (words[i] in ns("محل", "دكان", "المحل") && Regex("[0-9]{1,4}").matches(words[i + 1]) && customers.any { ArabicText.normalize(it.name) == "محل " + words[i + 1] }) {
            shopIdx += i; shopIdx += i + 1
        }

        // ---- amounts
        val spans = SpokenNumbers.find(words).filter { s -> (s.start until s.end).none { it in shopIdx } && s.value.signum() > 0 }
        val amount = pickAmount(spans, words)
        spans.forEach { s -> for (i in s.start until s.end) tk[i].role = Role.AMOUNT }
        // stray phone numbers are neither name nor amount
        tk.forEachIndexed { i, t -> if (SpokenNumbers.looksLikePhone(t.n)) tk[i].role = Role.FILLER }

        // ---- roles
        tk.forEachIndexed { i, t ->
            if (t.role == Role.AMOUNT || i in shopIdx) return@forEachIndexed
            val n = t.n
            t.role = when {
                n in currencyWords -> Role.CURRENCY
                isAlaPrep(t) -> Role.PREP
                n in noteMarkers -> Role.NOTE
                n in payVerbs || n in receivedVerbs || n in debtVerbs || n in tookVerbs || n in genericVerbs -> Role.VERB
                n in pronouns -> Role.PRONOUN
                n in preps -> Role.PREP
                n in fillers -> Role.FILLER
                Regex("[0-9]+").matches(n) -> Role.FILLER
                n.length > 3 && n[0] == 'ل' && (i > 0 && (tk[i - 1].role == Role.VERB)) && n.substring(1) !in fillers -> { t.nameValue = t.raw.drop(1); Role.NAME }
                else -> Role.NAME
            }
        }
        val kind = kindOf(tk)

        // ---- currency
        val currency = amount?.let { a -> currencyAfter(words, a.end) } ?: currencyAnywhere(words)

        // ---- name: the first run of name words that is not inside the note
        val noteAt = tk.indexOfFirst { it.role == Role.NOTE }
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < tk.size) {
            if ((tk[i].role == Role.NAME || i in shopIdx) && (noteAt < 0 || i < noteAt)) {
                var j = i
                while (j < tk.size && (tk[j].role == Role.NAME || j in shopIdx) && (noteAt < 0 || j < noteAt)) j++
                runs += i until j
                i = j
            } else i++
        }
        val preferred = runs.firstOrNull { r -> r.first > 0 && tk[r.first - 1].role in setOf(Role.PREP, Role.VERB, Role.PRONOUN) } ?: runs.firstOrNull()
        val nameTokens = preferred?.let { r -> tk.subList(r.first, minOf(r.last + 1, r.first + 4)) }.orEmpty()
            .dropLastWhile { it.n in nameJoiners }
        val spokenName = nameTokens.joinToString(" ") { it.nameValue }.trim()
        val spokenNorm = nameTokens.map { ArabicText.normalize(it.nameValue) }.filter { it.isNotBlank() }

        // ---- note: what follows a marker («حق غاز»، «قيمة كيس رز»)
        val note = if (noteAt < 0) "" else tk.drop(noteAt + 1).filter { it.role == Role.NAME }.joinToString(" ") { it.raw }.trim()

        val m = matchName(spokenNorm, customers)
        return VoiceDraft(
            transcript = transcript, kind = kind, amount = amount?.value, currency = currency,
            customer = m.customer, customerSure = m.sure, options = m.options,
            newName = if (m.customer != null && m.sure) "" else spokenName, note = note,
        )
    }

    private fun pickAmount(spans: List<SpokenAmount>, words: List<String>): SpokenAmount? {
        if (spans.isEmpty()) return null
        if (spans.size == 1) return spans[0]
        spans.firstOrNull { s -> words.getOrNull(s.end) in currencyWords }?.let { return it }
        spans.firstOrNull { s -> words.getOrNull(s.start - 1) in ns("بمبلغ", "مبلغ", "المبلغ") || words[s.start].startsWith("ب") }?.let { return it }
        return spans.maxByOrNull { it.value }
    }

    private fun currencyAfter(w: List<String>, end: Int): Currency? {
        val a = w.getOrNull(end); val b = w.getOrNull(end + 1)
        return when {
            a in ns("دولار", "دولارات", "دولارا", "بالدولار") -> Currency.USD
            a in ns("سعودي", "سعوديه", "بالسعودي") || (a in ns("ريال", "ريالات") && b in ns("سعودي", "سعوديه")) -> Currency.SAR
            a in ns("يمني", "باليمني") || (a in ns("ريال", "ريالات") && b == "يمني") -> Currency.YER
            else -> null
        }
    }

    private fun currencyAnywhere(w: List<String>): Currency? {
        val usd = w.any { it in ns("دولار", "دولارات", "دولارا", "بالدولار") }
        val sar = w.any { it in ns("سعودي", "سعوديه", "بالسعودي") }
        val yer = w.any { it in ns("يمني", "باليمني") }
        return when { usd && !sar && !yer -> Currency.USD; sar && !usd && !yer -> Currency.SAR; yer && !usd && !sar -> Currency.YER; else -> null }
    }

    /** Payment words beat «عليه / على» («سدد اللي عليه»); a real debt verb next to a payment verb is unclear. */
    private fun kindOf(tk: List<Tk>): VoiceKind? {
        val ns = tk.map { it.n }
        var pay = false; var debtVerb = false; var debtHint = false
        tk.forEachIndexed { i, t ->
            val n = t.n
            val next = ns.getOrNull(i + 1)
            when {
                n in payVerbs -> pay = true
                n in receivedVerbs && (next in ArabicSetHolder.fromWords || ns.drop(i + 1).any { it in ArabicSetHolder.fromWords }) -> pay = true
                n in tookVerbs -> debtVerb = true
                n in ArabicSetHolder.debtPronouns -> debtHint = true
                n in debtVerbs -> debtVerb = true
                // «سجل على محمد» / «… على خالد سعيد»: «على» + a name is a debt
                isAlaPrep(t) && (tk.getOrNull(i + 1)?.role == Role.NAME || (i > 0 && ns[i - 1] in genericVerbs)) -> debtHint = true
            }
        }
        return when {
            pay && debtVerb -> null
            pay -> VoiceKind.PAYMENT
            debtVerb || debtHint -> VoiceKind.DEBT
            else -> null
        }
    }

    private object ArabicSetHolder {
        val fromWords = setOf("من", "منه", "منها", "منهم")
        val debtPronouns = setOf("عليه", "عليها", "عليهم")
    }

    // ------------------------------------------------------------------ name matching

    data class NameResult(val customer: VoiceCustomer?, val sure: Boolean, val options: List<VoiceCustomer>)

    private fun compact(ws: List<String>) = ws.joinToString("").replace(" ", "")

    private fun tokenEq(a: String, b: String) = a == b || ArabicText.variants(a).any { it == b }
    private fun tokenNear(a: String, b: String) = tokenEq(a, b) || ArabicText.similar(a, b)

    /**
     * The spoken name against the book: exact (after normalizing ا/أ/إ/آ، ة/ه، ى/ي، «عبد الله»=«عبدالله») → sure;
     * a close spelling or the first name of exactly one account → suggested («هل تقصد …؟»); several → options.
     */
    fun matchName(spoken: List<String>, customers: List<VoiceCustomer>): NameResult {
        if (spoken.isEmpty() || customers.isEmpty()) return NameResult(null, false, emptyList())
        val sc = compact(spoken)
        val exact = ArrayList<VoiceCustomer>(); val near = ArrayList<VoiceCustomer>()
        val prefix = ArrayList<VoiceCustomer>(); val loose = ArrayList<VoiceCustomer>()
        for (c in customers) {
            val ct = ArabicText.words(c.name)
            if (ct.isEmpty()) continue
            val cc = compact(ct)
            when {
                cc == sc -> exact += c
                (ct.size == spoken.size && ct.indices.all { tokenNear(spoken[it], ct[it]) }) ||
                    (cc.length >= 5 && sc.length >= 5 && ArabicText.lev(cc, sc) <= (if (minOf(cc.length, sc.length) >= 8) 2 else 1)) -> near += c
                spoken.size < ct.size && spoken.indices.all { tokenEq(spoken[it], ct[it]) } && !(spoken.size == 1 && spoken[0] in nameJoiners) -> prefix += c
                spoken.size < ct.size && spoken.indices.all { tokenNear(spoken[it], ct[it]) } && spoken.last().length >= 4 -> loose += c
                ct.size < spoken.size && ct.indices.all { tokenEq(spoken[it], ct[it]) } -> loose += c
            }
        }
        fun uniq(l: List<VoiceCustomer>) = l.distinctBy { it.id }
        val ex = uniq(exact); val nr = uniq(near - exact.toSet()); val pr = uniq(prefix); val lo = uniq(loose)
        return when {
            ex.size == 1 -> NameResult(ex[0], true, emptyList())
            ex.size > 1 -> NameResult(null, false, ex)
            nr.size == 1 && pr.isEmpty() -> NameResult(nr[0], false, emptyList())
            nr.isNotEmpty() -> NameResult(null, false, uniq(nr + pr).take(6))
            pr.size == 1 -> NameResult(pr[0], false, emptyList())
            pr.isNotEmpty() -> NameResult(null, false, pr.take(6))
            lo.isNotEmpty() -> NameResult(null, false, lo.take(6))
            else -> NameResult(null, false, emptyList())
        }
    }
}
