package com.daftari.app.core

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object Dates {
    val months = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    private val days = listOf("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")

    fun today(): LocalDate = LocalDate.now()

    fun short(d: LocalDate): String = "${d.dayOfMonth} ${months[d.monthValue - 1]}"
    fun full(d: LocalDate): String = "${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}"
    fun dayName(d: LocalDate): String = days[d.dayOfWeek.value - 1]
    fun monthYear(d: LocalDate): String = "${months[d.monthValue - 1]} ${d.year}"
    fun numeric(d: LocalDate): String = "${d.year}/${d.monthValue.toString().padStart(2, '0')}/${d.dayOfMonth.toString().padStart(2, '0')}"

    fun relative(d: LocalDate, today: LocalDate = today()): String = when (val diff = today.toEpochDay() - d.toEpochDay()) {
        0L -> "اليوم"
        1L -> "أمس"
        else -> if (diff in 2..6) "قبل $diff أيام" else short(d)
    }

    @Suppress("unused")
    fun englishDay(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
}
