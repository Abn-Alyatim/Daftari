/*
 * دفتري · voice/SpokenNumbers.kt
 * قراءة المبالغ المنطوقة بالعربية (فصحى + يمني/خليجي) من نصّ التعرّف الصوتي، على الجهاز ودون إنترنت.
 * Reads spoken Arabic amounts from a speech transcript: digits (Latin / Arabic-Indic), number words
 * (واحد…عشرة، أحد عشر/حداش/خمسطعش، عشرين…تسعين، مية/مائة/ميتين/ثلاثمية، ألف/ألفين/آلاف/خمستالاف، مليون),
 * the joiner «و», and «ونص / وربع» relative to the last scale (ألف ونص = 1500، مية ونص = 150، نص مليون = 500000).
 * Pure Kotlin, deterministic, no Android imports — unit-tested in VoiceEntryParserTest.
 */
package com.daftari.app.voice

import java.math.BigDecimal

/** One number phrase found in the normalized words: its value and the word span [start, end). */
data class SpokenAmount(val value: BigDecimal, val start: Int, val end: Int)

object SpokenNumbers {
    private sealed interface Tok {
        data class Unit(val v: BigDecimal, val digits: Boolean = false) : Tok
        data class Hundred(val v: Int) : Tok
        data class Mult(val m: Long, val dual: Boolean = false) : Tok
        data class Thousands(val v: Int) : Tok
        data class Fraction(val f: BigDecimal) : Tok
    }

    // every key is already in ArabicText.normalize form (أ/إ/آ→ا، ة→ه، ى→ي، ئ→ي)
    private val units: Map<String, Int> = mapOf(
        "صفر" to 0, "واحد" to 1, "واحده" to 1, "وحده" to 1, "احد" to 1,
        "اثنين" to 2, "اثنان" to 2, "اثنتين" to 2, "اثنتان" to 2, "ثنين" to 2, "اتنين" to 2, "اثنا" to 2, "اثنتا" to 2,
        "ثلاث" to 3, "ثلاثه" to 3, "تلاته" to 3, "تلات" to 3, "تلت" to 3, "تلاث" to 3, "ثلاثا" to 3,
        "اربع" to 4, "اربعه" to 4, "خمس" to 5, "خمسه" to 5, "ست" to 6, "سته" to 6, "سبع" to 7, "سبعه" to 7,
        "ثمان" to 8, "ثماني" to 8, "ثمانيه" to 8, "ثمنيه" to 8, "تمانيه" to 8, "تمنيه" to 8,
        "تسع" to 9, "تسعه" to 9, "عشر" to 10, "عشره" to 10,
        "حداش" to 11, "حدعش" to 11, "احدعش" to 11, "احدعشر" to 11, "اثنعش" to 12, "اطنعش" to 12, "اثنعشر" to 12, "ثنعش" to 12, "اتناشر" to 12,
        "عشرين" to 20, "عشرون" to 20, "ثلاثين" to 30, "ثلاثون" to 30, "تلاتين" to 30, "اربعين" to 40, "اربعون" to 40,
        "خمسين" to 50, "خمسون" to 50, "ستين" to 60, "ستون" to 60, "سبعين" to 70, "سبعون" to 70,
        "ثمانين" to 80, "ثمانون" to 80, "تمانين" to 80, "تسعين" to 90, "تسعون" to 90,
    )
    private val hundredWords = mapOf(
        "ميه" to 100, "مايه" to 100, "ميتين" to 200, "مايتين" to 200, "ميتان" to 200, "مايتان" to 200,
    )
    private val stems = mapOf(
        "ثلاث" to 3, "ثلث" to 3, "تلت" to 3, "تلات" to 3, "اربع" to 4, "ربع" to 4, "خمس" to 5, "ست" to 6, "سبع" to 7,
        "ثمان" to 8, "ثمن" to 8, "تمن" to 8, "تسع" to 9,
    )
    private val hundredSuffixes = listOf("ميه", "مايه")
    private val thousandSuffixes = listOf("تالاف", "تلاف", "طلاف", "تالف", "الاف")
    private val teenSuffixes = listOf("طعشر", "تعشر", "طعش", "تعش", "تاشر", "طاشر", "دعش")
    private val teenStems = stems + mapOf("س" to 6, "ثلاث" to 3)
    private val multipliers = mapOf(
        "الف" to Tok.Mult(1000), "الاف" to Tok.Mult(1000), "الوف" to Tok.Mult(1000), "تلاف" to Tok.Mult(1000), "تالاف" to Tok.Mult(1000),
        "الفين" to Tok.Mult(1000, dual = true), "الفان" to Tok.Mult(1000, dual = true),
        "مليون" to Tok.Mult(1_000_000), "ملايين" to Tok.Mult(1_000_000), "مليونين" to Tok.Mult(1_000_000, dual = true), "مليونان" to Tok.Mult(1_000_000, dual = true),
    )
    private val fractions = mapOf("نص" to BigDecimal("0.5"), "نصف" to BigDecimal("0.5"), "ربع" to BigDecimal("0.25"))
    private val digitsRx = Regex("[0-9]+(\\.[0-9]+)?")

    private fun tok(w: String): Tok? {
        val t = w.replace(",", "").replace("٬", "")
        if (digitsRx.matches(t)) return Tok.Unit(BigDecimal(t), digits = true)
        units[w]?.let { return Tok.Unit(BigDecimal(it)) }
        hundredWords[w]?.let { return Tok.Hundred(it) }
        multipliers[w]?.let { return it }
        fractions[w]?.let { return Tok.Fraction(it) }
        for (suf in hundredSuffixes) if (w.length > suf.length && w.endsWith(suf)) stems[w.removeSuffix(suf)]?.let { return Tok.Hundred(it * 100) }
        for (suf in thousandSuffixes) if (w.length > suf.length && w.endsWith(suf)) {
            val st = w.removeSuffix(suf)
            (stems[st] ?: if (st == "عشر" || st == "عشره") 10 else null)?.let { return Tok.Thousands(it) }
        }
        for (suf in teenSuffixes) if (w.length > suf.length && w.endsWith(suf)) teenStems[w.removeSuffix(suf)]?.let { return Tok.Unit(BigDecimal(it + 10)) }
        return null
    }

    /** True for a 9+ digit run that reads like a phone number (7…، 05…، 00…، 967…، 966…): never an amount. */
    fun looksLikePhone(w: String): Boolean {
        val t = w.replace(Regex("[^0-9]"), "")
        return t.length >= 9 && (t.startsWith("7") || t.startsWith("05") || t.startsWith("00") || t.startsWith("967") || t.startsWith("966"))
    }

    private fun isNumberWord(w: String): Boolean = tok(w) != null || (w.length > 1 && w[0] == 'و' && tok(w.substring(1)) != null)

    /** All number phrases in [words] (already normalized with ArabicText.normalize), left to right. */
    fun find(words: List<String>): List<SpokenAmount> {
        val out = ArrayList<SpokenAmount>()
        var i = 0
        while (i < words.size) {
            val hit = parseAt(words, i)
            if (hit != null) { out += hit; i = hit.end } else i++
        }
        return out
    }

    private fun parseAt(w: List<String>, start: Int): SpokenAmount? {
        var total = BigDecimal.ZERO
        var current = BigDecimal.ZERO
        var lastScale = 1L
        var any = false
        var lastWasUnit = false
        var j = start
        loop@ while (j < w.size) {
            val raw = w[j]
            if (looksLikePhone(raw)) break
            if (raw == "و" && any && j + 1 < w.size && isNumberWord(w[j + 1])) { j++; continue }
            var core = raw
            if (tok(core) == null && core.length > 1) {
                if (any && core[0] == 'و' && tok(core.substring(1)) != null) core = core.substring(1)
                else if (!any && core[0] == 'ب' && tok(core.substring(1)) != null) core = core.substring(1)
            }
            val k = tok(core) ?: break
            when (k) {
                is Tok.Unit -> {
                    // a second digit run, or a unit after a finished unit («خمسه ثلاثه»), starts a new number
                    if (any && (k.digits || (lastWasUnit && current.signum() > 0 && current < BigDecimal.TEN && k.v < BigDecimal.TEN))) break@loop
                    current += k.v; lastScale = 1; lastWasUnit = true
                }
                is Tok.Hundred -> {
                    current = if (k.v == 100 && lastWasUnit && current.signum() > 0 && current < BigDecimal.TEN) current * BigDecimal(100) else current + BigDecimal(k.v)
                    lastScale = 100; lastWasUnit = false
                }
                is Tok.Mult -> {
                    if (k.dual) {
                        if (current.signum() > 0) break@loop
                        total += BigDecimal(k.m * 2)
                    } else {
                        val c = if (current.signum() == 0) BigDecimal.ONE else current
                        total += c * BigDecimal(k.m)
                    }
                    current = BigDecimal.ZERO; lastScale = k.m; lastWasUnit = false
                }
                is Tok.Thousands -> {
                    if (current.signum() > 0) break@loop
                    total += BigDecimal(k.v * 1000L); lastScale = 1000; lastWasUnit = false
                }
                is Tok.Fraction -> {
                    if (!any) {
                        // «نص مليون» / «ربع مليون»: a leading fraction only counts before a multiplier
                        val next = w.getOrNull(j + 1)?.let { tok(it) }
                        if (next is Tok.Mult && !next.dual) { total += k.f * BigDecimal(next.m); j += 2; any = true; lastScale = next.m; continue@loop }
                        break@loop
                    }
                    if (lastScale >= 1000) total += k.f * BigDecimal(lastScale)
                    else current += k.f * BigDecimal(lastScale)
                    j++; any = true
                    break@loop
                }
            }
            any = true
            j++
        }
        if (!any) return null
        val v = (total + current).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        return SpokenAmount(v, start, j)
    }
}
