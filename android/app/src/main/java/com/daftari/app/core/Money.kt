package com.daftari.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Currencies the user can pick in Settings. Amounts are stored as Long minor units (value × 100). */
enum class Currency(val code: String, val labelAr: String) {
    YER("YER", "ريال يمني"),
    SAR("SAR", "ريال سعودي"),
    USD("USD", "دولار أمريكي");

    companion object {
        fun of(code: String?): Currency = entries.firstOrNull { it.code == code } ?: YER
    }
}

object Money {
    private const val LRI = "\u2066"
    private const val PDI = "\u2069"

    /** 1250000 minor → "12,500" (decimals only when the amount has a fraction). */
    fun number(minor: Long): String {
        val neg = minor < 0
        val abs = kotlin.math.abs(minor)
        val whole = abs / 100
        val frac = (abs % 100).toInt()
        val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        val body = if (frac == 0) grouped else grouped + "." + frac.toString().padStart(2, '0')
        return if (neg) "-$body" else body
    }

    /** "12,500 YER", isolated so it reads correctly inside Arabic sentences. */
    fun format(minor: Long, currency: Currency, withCode: Boolean = true): String =
        LRI + number(minor) + (if (withCode) " " + currency.code else "") + PDI

    /** Plain (no bidi marks) for files/exports. */
    fun plain(minor: Long, currency: Currency? = null): String =
        number(minor) + (currency?.let { " " + it.code } ?: "")

    /** Parses user input: Arabic-Indic or Persian digits, commas, Arabic decimal separator. Null when not a number ≥ 0. */
    fun parse(text: String): Long? {
        val normalized = buildString {
            for (c in text.trim()) {
                when (c) {
                    in '\u0660'..'\u0669' -> append('0' + (c - '\u0660'))
                    in '\u06F0'..'\u06F9' -> append('0' + (c - '\u06F0'))
                    ',', '\u066C', ' ', '\u00A0' -> Unit
                    '\u066B' -> append('.')
                    else -> append(c)
                }
            }
        }
        if (normalized.isEmpty()) return null
        return try {
            val bd = Calc.eval(normalized.replace('×', '*').replace('÷', '/').replace('x', '*'))
            if (bd.signum() < 0) null else bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: Exception) {
            null
        }
    }

    fun ofWhole(value: Long): Long = value * 100
}

/** Normalizes Arabic-Indic digits to ASCII (phone numbers, shop numbers). */
fun String.asciiDigits(): String = buildString {
    for (c in this@asciiDigits) {
        when (c) {
            in '\u0660'..'\u0669' -> append('0' + (c - '\u0660'))
            in '\u06F0'..'\u06F9' -> append('0' + (c - '\u06F0'))
            else -> append(c)
        }
    }
}

/** The amount field doubles as a calculator: «1500+2500», «3*1200», «(500+250)*2», «10000-15%». */
object Calc {
    fun eval(src: String): BigDecimal {
        val p = Parser(src.replace(" ", ""))
        val v = p.expr()
        require(p.pos == p.s.length) { "bad expression" }
        return v
    }

    fun isExpression(text: String): Boolean = text.drop(1).any { it in "+-*/×÷x%" }

    private class Parser(val s: String) {
        var pos = 0
        fun expr(): BigDecimal {
            var v = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val start = pos
                var r = term()
                if (pos < s.length && s[pos] == '%') { pos++; r = v.multiply(r).divide(BigDecimal(100), 4, RoundingMode.HALF_UP) }
                else if (start == pos) throw IllegalArgumentException("bad")
                v = if (op == '+') v.add(r) else v.subtract(r)
            }
            return v
        }
        fun term(): BigDecimal {
            var v = factor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]
                val r = factor()
                v = if (op == '*') v.multiply(r) else v.divide(r, 4, RoundingMode.HALF_UP)
            }
            return v
        }
        fun factor(): BigDecimal {
            if (pos < s.length && s[pos] == '(') { pos++; val v = expr(); require(pos < s.length && s[pos] == ')'); pos++; return v }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(pos > start) { "number expected" }
            return BigDecimal(s.substring(start, pos))
        }
    }
}
