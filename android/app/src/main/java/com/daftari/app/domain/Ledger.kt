package com.daftari.app.domain

import com.daftari.app.core.Dates
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure money logic. Periodic rent accrues on the fly (no rows per day), so 200 shops never bloat the database. */
object Ledger {

    /** Dates on which a periodic amount falls due, inside [from, to] (both inclusive). Rent is due at the start of each period. */
    fun dueDates(party: Party, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (party.period == Period.NONE || party.periodicAmount <= 0) return emptyList()
        val start = party.startDate
        val lo = if (from.isBefore(start)) start else from
        if (lo.isAfter(to)) return emptyList()
        val out = ArrayList<LocalDate>()
        when (party.period) {
            Period.DAILY -> {
                var d = lo
                while (!d.isAfter(to)) { out += d; d = d.plusDays(1) }
            }
            Period.WEEKLY -> {
                val offset = ChronoUnit.DAYS.between(start, lo)
                var d = start.plusDays(((offset + 6) / 7) * 7)
                while (!d.isAfter(to)) { out += d; d = d.plusDays(7) }
            }
            Period.MONTHLY -> {
                var k = 0L
                var d = start
                while (d.isBefore(lo)) { k++; d = start.plusMonths(k) }
                while (!d.isAfter(to)) { out += d; k++; d = start.plusMonths(k) }
            }
            Period.NONE -> Unit
        }
        return out
    }

    fun dueCount(party: Party, from: LocalDate, to: LocalDate): Int {
        if (party.period == Period.DAILY && party.periodicAmount > 0) {
            val lo = if (from.isBefore(party.startDate)) party.startDate else from
            return if (lo.isAfter(to)) 0 else (ChronoUnit.DAYS.between(lo, to) + 1).toInt()
        }
        return dueDates(party, from, to).size
    }

    fun accrued(party: Party, from: LocalDate, to: LocalDate): Long = dueCount(party, from, to) * party.periodicAmount

    /** Everything owed up to and including [asOf]. */
    fun balance(party: Party, entries: List<Entry>, asOf: LocalDate): Long {
        val acc = accrued(party, party.startDate, asOf)
        var b = acc + (if (asOf.isBefore(party.startDate)) 0 else party.openingBalance)
        for (e in entries) {
            if (e.date.isAfter(asOf)) continue
            b += if (e.type == EntryType.CHARGE) e.amount else -e.amount
        }
        return b
    }

    fun summarize(party: Party, entries: List<Entry>, today: LocalDate, lateAfterDays: Int = 3): PartySummary {
        val mine = entries.filter { it.partyId == party.id }
        val due = party.openingBalance + accrued(party, party.startDate, today) + mine.filter { it.type == EntryType.CHARGE && !it.date.isAfter(today) }.sumOf { it.amount }
        val paid = mine.filter { it.type == EntryType.PAYMENT && !it.date.isAfter(today) }.sumOf { it.amount }
        val bal = due - paid
        val lastPay = mine.filter { it.type == EntryType.PAYMENT }.maxOfOrNull { it.date }
        val paidToday = mine.filter { it.type == EntryType.PAYMENT && it.date == today }.sumOf { it.amount }
        val daysLate = daysLate(party, mine, bal, today)
        val status = when {
            bal <= 0 -> Status.PAID
            party.period != Period.NONE && party.periodicAmount > 0 -> if (daysLate > lateAfterDays) Status.LATE else Status.PARTIAL
            daysLate > 30 -> Status.LATE
            lastPay != null -> Status.PARTIAL
            else -> Status.OWES
        }
        return PartySummary(party, bal, due, paid, status, daysLate, lastPay, paidToday)
    }

    /** How long the oldest unpaid amount has been waiting (FIFO: payments clear the oldest dues first). */
    fun daysLate(party: Party, entries: List<Entry>, balance: Long, today: LocalDate): Int {
        if (balance <= 0) return 0
        if (party.period != Period.NONE && party.periodicAmount > 0) {
            val perDay = when (party.period) {
                Period.DAILY -> party.periodicAmount.toDouble()
                Period.WEEKLY -> party.periodicAmount / 7.0
                Period.MONTHLY -> party.periodicAmount / 30.0
                Period.NONE -> 1.0
            }
            return kotlin.math.ceil(balance / perDay).toInt()
        }
        // customers: walk charges oldest→newest until the unpaid remainder is reached
        val charges = entries.filter { it.type == EntryType.CHARGE }.sortedBy { it.date }
        val paid = entries.filter { it.type == EntryType.PAYMENT }.sumOf { it.amount }
        var covered = paid
        for (c in charges) {
            if (covered >= c.amount) { covered -= c.amount; continue }
            return ChronoUnit.DAYS.between(c.date, today).toInt().coerceAtLeast(0)
        }
        return 0
    }

    /**
     * A statement for [from]..[to]: opening balance, then periodic dues grouped per month, manual charges and payments by date,
     * with a running balance.
     */
    fun statement(party: Party, entries: List<Entry>, from: LocalDate, to: LocalDate): Statement {
        val mine = entries.filter { it.partyId == party.id }
        val opening = balance(party, mine, from.minusDays(1))
        data class Ev(val date: LocalDate, val order: Int, val desc: String, val debit: Long, val credit: Long)
        val evs = ArrayList<Ev>()
        // periodic dues grouped by calendar month
        val dues = dueDates(party, from, to)
        if (party.period == Period.DAILY && party.periodicAmount > 0) {
            var cursor = if (from.isBefore(party.startDate)) party.startDate else from
            while (!cursor.isAfter(to)) {
                val monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth())
                val end = if (monthEnd.isAfter(to)) to else monthEnd
                val n = ChronoUnit.DAYS.between(cursor, end).toInt() + 1
                evs += Ev(cursor, 0, "إيجار ${Dates.months[cursor.monthValue - 1]} ($n يوم)", n * party.periodicAmount, 0)
                cursor = end.plusDays(1)
            }
        } else {
            for (d in dues) evs += Ev(d, 0, "${party.period.labelAr.let { if (it == "شهري") "إيجار شهر" else "إيجار أسبوع" }} ${Dates.short(d)}", party.periodicAmount, 0)
        }
        for (e in mine) {
            if (e.date.isBefore(from) || e.date.isAfter(to)) continue
            if (e.type == EntryType.CHARGE) evs += Ev(e.date, 1, e.note.ifBlank { "دين" }, e.amount, 0)
            else evs += Ev(e.date, 2, e.note.ifBlank { "دفعة" }, 0, e.amount)
        }
        evs.sortWith(compareBy<Ev>({ it.date }, { it.order }))
        var running = opening
        val lines = evs.map { ev ->
            running += ev.debit - ev.credit
            StatementLine(ev.date, ev.desc, ev.debit, ev.credit, running)
        }
        return Statement(party, from, to, opening, lines, lines.sumOf { it.debit }, lines.sumOf { it.credit }, running)
    }
}
