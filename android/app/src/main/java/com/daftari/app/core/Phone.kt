package com.daftari.app.core

/** Yemen-first phone handling: local 7xxxxxxxx numbers get +967. */
object Phone {
    fun normalize(input: String, defaultCountry: String = "967"): String? {
        var d = input.asciiDigits().filter { it.isDigit() || it == '+' }
        if (d.isEmpty()) return null
        if (d.startsWith("+")) d = d.drop(1)
        else if (d.startsWith("00")) d = d.drop(2)
        else if (d.startsWith("0")) d = defaultCountry + d.drop(1)
        else if (d.length <= 9) d = defaultCountry + d
        d = d.filter { it.isDigit() }
        if (d.length < 9 || d.length > 15) return null
        return "+$d"
    }

    /** Digits only, for wa.me links and WhatsApp jids. */
    fun waDigits(e164: String): String = e164.filter { it.isDigit() }

    fun display(e164: String): String {
        val d = e164.removePrefix("+")
        return if (d.startsWith("967") && d.length == 12) "+967 ${d.substring(3, 5)} ${d.substring(5, 8)} ${d.substring(8)}" else e164
    }
}
