package com.daftari.app.chat

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * «مدين» بدون إنترنت: فهم الأوامر العربية (فصحى + يمني/خليجي) على الجهاز نفسه.
 * Rules + keyword intents + entity extraction (amount, currency, customer name, items, dates). No network, no model.
 */
object ArabicText {
    private val diacritics = Regex("[\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            sb.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    'ک' -> 'ك'
                    'ی' -> 'ي'
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    '\u066B' -> '.'
                    '\u066C', '،', '؛', '؟', '!', '?', ':', '«', '»', '"', '(', ')' -> ' '
                    else -> ch
                },
            )
        }
        return diacritics.replace(sb.toString(), "")
            // split letters from digits: «بـ5000» → «ب 5000», «5الاف» → «5 الاف»
            .replace(Regex("(?<=[\\p{IsArabic}])(?=[0-9])"), " ")
            .replace(Regex("(?<=[0-9])(?=[\\p{IsArabic}kK$])"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotBlank() }

    /** «محمد» ≈ «لمحمد» / «ومحمد» / «بمحمد»; «الرز» ≈ «رز». */
    fun variants(w: String): List<String> {
        val out = linkedSetOf(w)
        if (w.length > 3 && w[0] in "لبوف") out += w.substring(1)
        if (w.startsWith("لل") && w.length > 4) out += w.substring(2)
        if (w.startsWith("ال") && w.length > 4) out += w.substring(2)
        if ((w.startsWith("وال") || w.startsWith("بال")) && w.length > 5) out += w.substring(3)
        return out.toList()
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val t = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return dp[b.length]
    }

    /** Same word allowing a typo on longer words (محمد/محمّد/مخمد). */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val n = minOf(a.length, b.length)
        return when {
            n >= 7 -> lev(a, b) <= 2
            n >= 4 -> lev(a, b) <= 1
            else -> false
        }
    }
}

enum class Cur { YER, SAR, USD }

data class AmountHit(val value: BigDecimal, val start: Int, val end: Int, val currency: Cur?) {
    /** minor units (×100) like the rest of the app. */
    val minor: Long get() = value.movePointRight(2).toLong()
}

object ArabicNumbers {
    private val units = mapOf(
        "صفر" to 0, "واحد" to 1, "واحده" to 1, "وحده" to 1, "اثنين" to 2, "اثنان" to 2, "اثنتين" to 2, "ثنين" to 2, "اتنين" to 2,
        "ثلاث" to 3, "ثلاثه" to 3, "تلاته" to 3, "تلات" to 3, "اربع" to 4, "اربعه" to 4, "خمس" to 5, "خمسه" to 5,
        "ست" to 6, "سته" to 6, "سبع" to 7, "سبعه" to 7, "ثمان" to 8, "ثماني" to 8, "ثمانيه" to 8, "ثمنيه" to 8, "تمانيه" to 8,
        "تسع" to 9, "تسعه" to 9, "عشر" to 10, "عشره" to 10,
        "احدعش" to 11, "حداش" to 11, "حدعش" to 11, "اثنعش" to 12, "اطنعش" to 12, "اثنا" to 2,
        "عشرين" to 20, "عشرون" to 20, "ثلاثين" to 30, "تلاتين" to 30, "اربعين" to 40, "خمسين" to 50, "ستين" to 60,
        "سبعين" to 70, "ثمانين" to 80, "تمانين" to 80, "تسعين" to 90,
        "ميه" to 100, "مايه" to 100, "مائه" to 100, "مئه" to 100, "ميت" to 100, "ميتين" to 200, "مئتين" to 200, "مائتين" to 200, "ميتان" to 200,
        "الفين" to 2000, "الفان" to 2000,
    )
    private val hundredsSuffix = listOf("ميه", "مايه", "مائه", "مئه")
    private val unitStems = mapOf("ثلاث" to 3, "ثلث" to 3, "تلت" to 3, "اربع" to 4, "خمس" to 5, "ست" to 6, "سبع" to 7, "ثمان" to 8, "ثمن" to 8, "تمن" to 8, "تسع" to 9)
    private val multipliers = mapOf("الف" to 1000L, "الاف" to 1000L, "الوف" to 1000L, "ك" to 1000L, "k" to 1000L, "مليون" to 1_000_000L, "ملايين" to 1_000_000L, "مليونين" to 2_000_000L)
    private val fractions = mapOf("نص" to BigDecimal("0.5"), "نصف" to BigDecimal("0.5"), "ربع" to BigDecimal("0.25"))

    private fun wordValue(w: String): Int? {
        units[w]?.let { return it }
        if (w.length > 3 && w[0] == 'ب') units[w.substring(1)]?.let { return it }
        for (suf in hundredsSuffix) if (w.endsWith(suf) && w.length > suf.length) {
            unitStems[w.removeSuffix(suf)]?.let { return it * 100 }
        }
        return null
    }

    private fun digits(w: String): BigDecimal? {
        val t = w.replace(",", "").replace("٬", "")
        if (!Regex("[0-9]+(\\.[0-9]+)?").matches(t)) return null
        return BigDecimal(t)
    }

    /** Phones are not amounts: 9+ digits starting 7 / 05 / 00 / 967 / 966. */
    fun looksLikePhone(w: String): Boolean {
        val t = w.replace(Regex("[^0-9]"), "")
        return t.length >= 9 && (t.startsWith("7") || t.startsWith("05") || t.startsWith("00") || t.startsWith("967") || t.startsWith("966") || t.startsWith("1"))
    }

    fun currencyOf(words: List<String>, i: Int): Cur? {
        val w = words.getOrNull(i) ?: return null
        val n = words.getOrNull(i + 1)
        return when {
            w in setOf("سعودي", "سعوديه", "sar", "رس") -> Cur.SAR
            w in setOf("ريال", "ريالات", "رial") && n in setOf("سعودي", "سعوديه") -> Cur.SAR
            w in setOf("ريال", "ريالات") && n == "يمني" -> Cur.YER
            w in setOf("يمني", "yer") -> Cur.YER
            w in setOf("دولار", "دولارات", "usd", "$", "دولارا") -> Cur.USD
            w in setOf("ريال", "ريالات") -> Cur.YER
            else -> null
        }
    }

    /** All number phrases in [words] (already normalized). */
    fun find(words: List<String>): List<AmountHit> {
        val out = ArrayList<AmountHit>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val startVal = digits(w) ?: wordValue(w)?.toBigDecimal() ?: multipliers[w]?.takeIf { w == "الف" || w == "مليون" }?.let { BigDecimal.ONE }
            if (startVal == null || (digits(w) != null && looksLikePhone(w))) { i++; continue }
            var total = BigDecimal.ZERO
            var current = BigDecimal.ZERO
            var lastMult = 1L
            var j = i
            var any = false
            while (j < words.size) {
                val t = words[j]
                val d = if (looksLikePhone(t)) null else digits(t)
                val wv = wordValue(t)
                val mult = multipliers[t]
                val frac = fractions[t] ?: fractions[t.removePrefix("و")]
                when {
                    d != null -> { if (any && current.signum() != 0 && words[j - 1] != "و") break; current += d; any = true }
                    wv != null -> {
                        if (wv == 2000) { total += BigDecimal(2000); lastMult = 1000 } else current += wv.toBigDecimal()
                        any = true
                    }
                    t.startsWith("و") && t.length > 2 && wordValue(t.substring(1)) != null -> {
                        val v = wordValue(t.substring(1))!!
                        if (v == 2000) { total += BigDecimal(2000) } else current += v.toBigDecimal(); any = true
                    }
                    mult != null && (any || t == "الف" || t == "مليون") -> {
                        val base = if (current.signum() == 0) BigDecimal.ONE else current
                        total += base * BigDecimal(mult); current = BigDecimal.ZERO; lastMult = mult; any = true
                    }
                    frac != null && any -> { total += frac * BigDecimal(lastMult); any = true }
                    t == "و" && any && j + 1 < words.size -> Unit
                    else -> break
                }
                j++
            }
            if (any) {
                var end = j
                if (end > i && words[end - 1] == "و") end--
                val value = total + current
                val cur = currencyOf(words, end)
                if (value.signum() > 0) out += AmountHit(value, i, end, cur)
                i = maxOf(end, i + 1)
            } else i++
        }
        return out
    }
}

data class Customer(val id: Long, val name: String, val phone: String = "")

sealed interface NameMatch {
    data class Found(val c: Customer, val start: Int, val end: Int) : NameMatch
    data class Ambiguous(val options: List<Customer>, val start: Int, val end: Int) : NameMatch
    data class NotFound(val guess: String) : NameMatch
}

object NameMatcher {
    /** Best customer named in [words]; fuzzy on typos, prefixes (ل/ب/و) and «ال». */
    fun match(words: List<String>, customers: List<Customer>, skip: Set<Int> = emptySet()): NameMatch? {
        data class Hit(val c: Customer, val score: Int, val start: Int, val end: Int)
        val hits = ArrayList<Hit>()
        for (c in customers) {
            val nt = ArabicText.words(c.name)
            if (nt.isEmpty()) continue
            var best: Hit? = null
            for (s in words.indices) {
                if (s in skip) continue
                var k = 0; var exact = 0
                while (k < nt.size && s + k < words.size && (s + k) !in skip) {
                    val vs = ArabicText.variants(words[s + k])
                    val ok = vs.any { it == nt[k] } || (k > 0 && vs.any { it == "ال" + nt[k] || "ال$it" == nt[k] })
                    val fuzzy = !ok && vs.any { ArabicText.similar(it, nt[k]) }
                    if (!ok && !fuzzy) break
                    if (ok) exact++
                    k++
                }
                if (k == 0) continue
                // compound first names need two words: «عبد الله», «ابو علي»
                if (k == 1 && nt.size > 1 && nt[0] in setOf("عبد", "ابو", "ام", "بن", "بنت")) continue
                // «علي» is also «على» typed without the dot: as a lone word it is more likely the preposition
                val prepPenalty = if (k == 1 && words[s] in setOf("علي", "من", "عند")) 6 else 0
                val score = k * 10 + exact * 3 + (if (k == nt.size) 15 else 0) - prepPenalty
                if (best == null || score > best.score) best = Hit(c, score, s, s + k)
            }
            best?.let { hits += it }
        }
        if (hits.isEmpty()) return null
        val top = hits.maxOf { it.score }
        val tops = hits.filter { it.score == top }
        val h = tops.first()
        return if (tops.size == 1) NameMatch.Found(h.c, h.start, h.end)
        else NameMatch.Ambiguous(tops.map { it.c }.distinctBy { it.id }, h.start, h.end)
    }
}

sealed interface Command {
    data class AddDebt(val who: NameMatch?, val amount: AmountHit?, val items: String, val date: LocalDate) : Command
    data class AddPayment(val who: NameMatch?, val amount: AmountHit?, val note: String, val date: LocalDate, val method: String = "", val transferNo: String = "", val transferCo: String = "", val senderPhone: String = "") : Command
    data class Balance(val who: NameMatch?) : Command
    data class Statement(val who: NameMatch?) : Command
    data class TopDebtors(val n: Int) : Command
    data object Today : Command
    data object TotalOwed : Command
    data class CreateCustomer(val name: String, val phone: String, val existing: Customer?) : Command
    data class Reminder(val who: NameMatch?, val at: LocalDateTime, val text: String) : Command
    data object Undo : Command
    data object Help : Command
    data object Unknown : Command
}

object Nlu {
    private fun ns(vararg w: String): Set<String> = w.map { ArabicText.normalize(it) }.toSet()

    private val paymentVerbs = ns(
        "سدد", "سددت", "سدده", "سددلي", "تسديد", "سداد", "دفع", "دفعت", "دفعه", "دفعو", "دفعوا", "سلم", "سلمني", "سلملي", "عطاني", "اعطاني", "جاب",
        "جابلي", "قضى", "قضا", "ورد", "رجع", "رجعلي", "خلص", "استلمت", "قبضت", "اخذت", "خذيت", "حصلت", "استلم",
    )
    private val payNouns = ns("دفعه", "سداد", "تسديد", "قسط")
    private val debtVerbs = ns(
        "سجل", "سجلي", "سجله", "قيد", "قيدي", "اكتب", "اكتبي", "اكتبه", "حط", "حطه", "ضيف", "اضف", "زيد", "دين", "دينه", "ديون", "اخذ", "خذ",
        "شل", "شال", "اشترى", "شرى", "استلف", "تسلف", "سلفه", "بالدين", "اجل", "بالاجل", "عليه", "على", "طلب",
    )
    private val createVerbs = ns("ضيف", "اضف", "افتح", "سجل", "انشي", "انشئ", "سوي", "اعمل", "زيد", "جديد")
    private val customerNouns = ns("زبون", "الزبون", "زبونا", "عميل", "العميل", "عميلا", "حساب", "حسابا", "مستاجر", "المستاجر", "مستاجرا", "محل")
    private val reminderVerbs = ns("ذكرني", "ذكرنا", "ذكر", "تذكير", "نبهني", "نبهنا", "فكرني", "ذكريني", "ذكرنيي")
    private val undoWords = ns("تراجع", "تراجعي", "undo")
    private val stop = ns(
        "على", "علي", "عليه", "لي", "له", "من", "عند", "مبلغ", "بمبلغ", "قيمه", "بقيمه", "حق", "ب", "بـ", "في", "الى", "الي", "ل", "يا", "مدين",
        "ريال", "ريالات", "سعودي", "يمني", "دولار", "اليوم", "امس", "البارحه", "حساب", "حسابه", "الحساب", "دين", "الدين", "دينه", "و", "واحد", "بس",
        "لو", "سمحت", "فضلك", "اخ", "الاخ", "الزبون", "زبون", "عميل", "العميل", "عن", "ثمن", "قيمة", "بسعر", "سعر",
    )

    fun parse(text: String, customers: List<Customer>, today: LocalDate, now: LocalDateTime = LocalDateTime.now()): Command {
        val w = ArabicText.words(text)
        if (w.isEmpty()) return Command.Unknown
        val has = { set: Set<String> -> w.any { it in set } }
        val joined = " " + w.joinToString(" ") + " "
        // «حوالة رقم 123456 (الكريمي)» / «جوالي رقم 778899»: the transfer / transaction number is not an amount
        val skipNum = HashSet<Int>()
        val pay = payMethod(w)
        val transfer = pay.method == "transfer"
        val transferNo = pay.ref
        skipNum += pay.used
        val company = pay.company
        val amounts = ArabicNumbers.find(w).filter { h -> (h.start until h.end).none { it in skipNum } }
        val date = when { w.contains("امس") || w.contains("البارحه") -> today.minusDays(1); else -> today }

        // ---- undo
        if (has(undoWords) || Regex(" (الغ|الغي|امسح|احذف|رجع|ارجع|شيل|كنسل) (اخر|اخرعمليه)").containsMatchIn(joined) ||
            Regex(" اخر عمليه ").containsMatchIn(joined) && Regex(" (الغ|الغي|امسح|احذف|رجع|ارجع|شيل|كنسل) ").containsMatchIn(joined)) return Command.Undo

        // ---- reminder
        if (has(reminderVerbs) || w.firstOrNull() == "ذكرني") {
            val who = NameMatcher.match(w, customers)
            return Command.Reminder(who, whenOf(w, now), text.trim())
        }

        // ---- help
        if (w.size <= 4 && (has(ns("مساعده", "ساعدني", "تقدر", "اوامر")) || joined.contains(" ايش تسوي ") || joined.contains(" وش تسوي "))) return Command.Help

        // ---- create customer
        val ci = w.indexOfFirst { it in customerNouns }
        val createVerb = w.take(maxOf(ci, 0) + 2).any { it in createVerbs }
        val realAmounts = amounts
        if (ci >= 0 && createVerb && (realAmounts.isEmpty() || w.contains("جديد")) && !has(paymentVerbs)) {
            val phone = w.firstOrNull { ArabicNumbers.looksLikePhone(it) }?.replace(Regex("[^0-9+]"), "") ?: ""
            val nameWords = w.drop(ci + 1).filter { it != "جديد" && it != "جديدا" && it != "اسمه" && it != "باسم" && it != "رقمه" && it != "جواله" && it != "رقم" && it != "جوال" && !ArabicNumbers.looksLikePhone(it) && !Regex("[0-9]+").matches(it) }
            val name = nameWords.joinToString(" ").trim()
            val existing = (NameMatcher.match(nameWords, customers) as? NameMatch.Found)?.c?.takeIf { ArabicText.normalize(it.name) == ArabicText.normalize(name) }
            return Command.CreateCustomer(name, phone, existing)
        }

        val amount = pickAmount(amounts)
        val amountIdx = amount?.let { (it.start until it.end + (if (it.currency != null) 2 else 0)).toSet() }.orEmpty()

        // ---- payment
        val receivedFrom = Regex(" (اخذت|خذيت|استلمت|قبضت|حصلت) من ").containsMatchIn(joined)
        val payVerb = w.any { it in paymentVerbs && it !in ns("اخذت", "خذيت", "استلمت", "قبضت", "حصلت") } || receivedFrom || has(payNouns)
        if (payVerb && (amount != null || has(ns("سدد", "دفع", "سلم")))) {
            val skip = amountIdx + skipNum
            val who = NameMatcher.match(w, customers, skip) ?: NameMatch.NotFound(guessName(w, skip, paymentVerbs))
            return Command.AddPayment(who, amount, "", date, pay.method, transferNo, company, pay.phone)
        }

        // ---- debt
        val debtVerb = has(debtVerbs)
        if (amount != null && (debtVerb || NameMatcher.match(w, customers, amountIdx) != null) && !isQuestion(w)) {
            val who = NameMatcher.match(w, customers, amountIdx) ?: NameMatch.NotFound(guessName(w, amountIdx, debtVerbs))
            val used = HashSet(amountIdx)
            if (who is NameMatch.Found) used += (who.start until who.end)
            if (who is NameMatch.Ambiguous) used += (who.start until who.end)
            if (who is NameMatch.NotFound) w.forEachIndexed { i, t -> if (ArabicText.variants(t).any { it == who.guess } || t == who.guess) used += i }
            val items = w.filterIndexed { i, t -> i !in used && t !in debtVerbs && t !in stop && t !in createVerbs && !ArabicNumbers.looksLikePhone(t) }
                .joinToString(" ").replace(Regex("^و "), "").trim()
            return Command.AddDebt(who, amount, items, date)
        }

        // ---- statement / balance
        val who = NameMatcher.match(w, customers)
        if (w.any { it == "كشف" || it == "كشفه" || it == "تفاصيل" || it == "حركات" || it == "عمليات" } && who != null) return Command.Statement(who)
        val balanceAsk = has(ns("كم", "قديش", "كام", "رصيد", "رصيده", "باقي", "متبقي", "حساب", "حسابه", "عليه", "بقي"))
        if (who != null && (balanceAsk || w.size <= 3)) return Command.Balance(who)

        // ---- top debtors
        if (has(ns("اكثر", "الاكثر", "اعلى", "اكبر", "المديونين", "المتاخرين", "متاخرين", "اكثرهم", "المتاخر"))) {
            val n = amounts.firstOrNull()?.value?.toInt()?.coerceIn(1, 50) ?: 10
            return Command.TopDebtors(n)
        }
        // ---- today
        if (w.contains("اليوم") && has(ns("تحصيل", "حصلت", "حصلنا", "دخل", "جمعت", "جمعنا", "سددوا", "مجموع", "اجمالي", "كم", "عمليات", "ملخص", "حركه", "ديون", "مبيعات"))) return Command.Today
        if (has(ns("اجمالي", "مجموع")) || joined.contains(" كم الديون ") || joined.contains(" كم عليهم ") || joined.contains(" كم لي ")) return Command.TotalOwed
        if (who != null && amount == null) return Command.Balance(who)
        if (amount != null && debtVerb) return Command.AddDebt(null, amount, "", date)
        return Command.Unknown
    }

    private fun isQuestion(w: List<String>) = w.firstOrNull() in ns("كم", "قديش", "كام", "هل", "مين", "ايش", "وش")

    /** The money amount: one tied to «ب/بمبلغ/currency», else the largest. */

    /** What the words say about how a payment came: method, company / wallet, reference, sender phone, and which words that used. */
    data class PayHit(val method: String = "", val company: String = "", val ref: String = "", val phone: String = "", val used: Set<Int> = emptySet())

    private val transferWords = ns("حواله", "بحواله", "حوالة", "تحويل", "بتحويل")
    private val walletWords = ns("محفظه", "بمحفظه", "المحفظه", "بالمحفظه", "محفظته")
    private val refLead = ns("رقم", "برقم", "رقمها", "مرجع", "المرجع", "بمرجع", "عمليه", "العمليه")
    private val refNoise = ns("العمليه", "عمليه", "الحواله", "حواله", "المرجع", "مرجع", "السند", "الاشعار", "اشعار")
    private val viaWords = ns("عبر", "طريق", "عن", "من", "ب", "على")
    private val companies = mapOf("الكريمي" to listOf("كريمي", "الكريمي"), "النجم" to listOf("النجم", "نجم"), "الحزمي" to listOf("الحزمي", "حزمي"))
    private val digitsRx = Regex("[0-9]{3,}")

    fun payMethod(w: List<String>): PayHit {
        val used = HashSet<Int>()
        var method = ""; var company = ""; var ref = ""; var phone = ""
        // wallets first (two-word «ون كاش» / «one cash»)
        var walletAt = -1
        for (i in w.indices) {
            val two = if (i + 1 < w.size) w[i] + " " + w[i + 1] else null
            val hit = com.daftari.app.core.PayMethods.WALLETS.firstOrNull { wl ->
                wl.aliases.any { a -> val n = ArabicText.normalize(a); (two != null && n.contains(' ') && ArabicText.variants(w[i]).any { v -> "$v ${w[i + 1]}" == n }) }
            }?.also { used += i; used += i + 1 }
                ?: com.daftari.app.core.PayMethods.WALLETS.firstOrNull { wl -> wl.aliases.any { a -> val n = ArabicText.normalize(a); !n.contains(' ') && ArabicText.variants(w[i]).any { it == n } } }?.also { used += i }
            if (hit != null) { method = "wallet"; company = hit.label; walletAt = used.max(); break }
        }
        val walletWord = w.indexOfFirst { it in walletWords }
        if (walletWord >= 0) { used += walletWord; if (method.isEmpty()) { method = "wallet"; walletAt = walletWord } }
        val transferAt = w.indexOfFirst { it in transferWords }
        if (method.isEmpty() && transferAt >= 0) {
            method = "transfer"; used += transferAt
            for (i in w.indices) companies.entries.firstOrNull { (_, al) -> ArabicText.variants(w[i]).any { it in al } }?.let { company = it.key; used += i }
            if (company.isEmpty()) w.indexOfFirst { it in ns("شركه", "صرافه", "الصرافه") }.takeIf { it >= 0 && it + 1 < w.size && !digitsRx.matches(w[it + 1]) }?.let { used += it; used += it + 1; company = w[it + 1] }
        }
        if (method.isNotEmpty()) for (i in w.indices.reversed()) if (w[i] in viaWords && (i + 1) in used) used += i
        // reference: «رقم [العملية] 778899» (a phone after «من رقم» is the sender, not the reference)
        for (i in w.indices) if (w[i] in refLead) {
            var j = i + 1
            while (j < w.size && w[j] in refNoise) j++
            if (j < w.size && w[j].isNotEmpty() && w[j].all { it.isDigit() }) {
                if (method == "wallet" && i > 0 && w[i - 1] == "من" && ArabicNumbers.looksLikePhone(w[j])) continue
                used += (i..j); if (method.isNotEmpty()) ref = w[j]; break
            }
        }
        // or the number right after «حوالة» / the wallet name, when another number is there to be the amount
        if (ref.isEmpty() && method.isNotEmpty()) {
            val at = if (method == "transfer") transferAt else walletAt
            if (at >= 0 && at + 1 < w.size && w[at + 1].all { it.isDigit() } && w[at + 1].length >= 4 && !ArabicNumbers.looksLikePhone(w[at + 1]) &&
                ArabicNumbers.find(w).count { h -> (at + 1) !in (h.start until h.end) } >= 1) { ref = w[at + 1]; used += at + 1 }
        }
        // sender phone for wallets: «من 777123456»
        if (method == "wallet") w.forEachIndexed { i, t -> if (i !in used && phone.isEmpty() && ArabicNumbers.looksLikePhone(t) && t.all { it.isDigit() || it == '+' }) { phone = t; used += i; if (i > 0 && w[i - 1] in ns("من", "جوال", "رقم", "المرسل", "مرسل")) used += i - 1 } }
        return PayHit(method, company, ref, phone, used)
    }

    private fun pickAmount(a: List<AmountHit>): AmountHit? = a.firstOrNull { it.currency != null } ?: a.maxByOrNull { it.value }

    /** Name of a customer not in the book: the word after «على/ل/من», or the first word before the verb. */
    private fun guessName(w: List<String>, skip: Set<Int>, verbs: Set<String>): String {
        val preps = ns("على", "علي", "من", "ل", "باسم", "لـ", "عند")
        for (i in w.indices) if (w[i] in preps && i + 1 < w.size && (i + 1) !in skip && w[i + 1] !in stop) return compound(w, i + 1)
        for (i in w.indices) {
            val t = w[i]
            if (i in skip || t in verbs || t in stop || t in payNouns) continue
            if (t.length > 3 && t[0] == 'ل' && w.getOrNull(i - 1) in verbs) return t.substring(1)
            if (w.getOrNull(i + 1) in verbs || w.getOrNull(i - 1) in verbs) return compound(w, i)
        }
        return ""
    }

    private fun compound(w: List<String>, i: Int): String =
        if (w[i] in ns("عبد", "ابو", "ام", "بن") && i + 1 < w.size) w[i] + " " + w[i + 1] else w[i]

    private val days = mapOf(
        "السبت" to DayOfWeek.SATURDAY, "الاحد" to DayOfWeek.SUNDAY, "الاثنين" to DayOfWeek.MONDAY, "الثلاثاء" to DayOfWeek.TUESDAY,
        "الاربعاء" to DayOfWeek.WEDNESDAY, "الخميس" to DayOfWeek.THURSDAY, "الجمعه" to DayOfWeek.FRIDAY,
    )

    fun whenOf(w: List<String>, now: LocalDateTime): LocalDateTime {
        val j = " " + w.joinToString(" ") + " "
        // «بعد ساعه / ساعتين / 3 ساعات / نص ساعه / 20 دقيقه»
        Regex(" بعد (\\d+) (ساعه|ساعات|دقيقه|دقايق|دقائق|يوم|ايام|اسبوع|اسابيع) ").find(j)?.let { m ->
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) {
                "ساعه", "ساعات" -> now.plusHours(n)
                "دقيقه", "دقايق", "دقائق" -> now.plusMinutes(n)
                "يوم", "ايام" -> now.plusDays(n)
                else -> now.plusWeeks(n)
            }
        }
        when {
            j.contains(" بعد نص ساعه ") -> return now.plusMinutes(30)
            j.contains(" بعد ساعتين ") -> return now.plusHours(2)
            j.contains(" بعد ساعه ") -> return now.plusHours(1)
            j.contains(" بعد يومين ") -> return now.plusDays(2).withHour(10).withMinute(0)
            j.contains(" بعد اسبوع ") -> return now.plusWeeks(1).withHour(10).withMinute(0)
        }
        var day: LocalDate = now.toLocalDate()
        var dayGiven = false
        when {
            j.contains(" بعد بكره ") || j.contains(" بعد بكرا ") || j.contains(" بعد غد ") || j.contains(" بعد غدا ") -> { day = day.plusDays(2); dayGiven = true }
            j.contains(" بكره ") || j.contains(" بكرا ") || j.contains(" غدا ") || j.contains(" باكر ") || j.contains(" الصبح بكره ") -> { day = day.plusDays(1); dayGiven = true }
        }
        days.entries.firstOrNull { j.contains(" " + it.key + " ") || j.contains(" يوم " + it.key.removePrefix("ال") + " ") }?.let {
            day = now.toLocalDate().with(TemporalAdjusters.next(it.value)); dayGiven = true
        }
        val hm = Regex(" (?:الساعه|ساعه|الساعة) (\\d{1,2})(?:[:.](\\d{2}))? ").find(j)
        var time: LocalTime? = hm?.let {
            var h = it.groupValues[1].toInt().coerceIn(0, 23)
            val pm = listOf(" العصر ", " عصر ", " المغرب ", " مساء ", " بالليل ", " الليل ", " المسا ", " مساءا ", " بعد الظهر ", " الظهر ").any { k -> j.contains(k) }
            if (pm && h in 1..11) h += 12
            LocalTime.of(h, it.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0)
        }
        if (time == null) time = when {
            j.contains(" الصبح ") || j.contains(" الصباح ") -> LocalTime.of(9, 0)
            j.contains(" الظهر ") -> LocalTime.of(12, 30)
            j.contains(" العصر ") -> LocalTime.of(16, 0)
            j.contains(" المغرب ") -> LocalTime.of(18, 0)
            j.contains(" الليل ") || j.contains(" بالليل ") -> LocalTime.of(20, 0)
            else -> null
        }
        if (!dayGiven && time == null) return now.toLocalDate().plusDays(1).atTime(10, 0)
        val at = day.atTime(time ?: LocalTime.of(10, 0))
        return if (!dayGiven && !at.isAfter(now)) at.plusDays(1) else at
    }
}
