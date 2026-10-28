package com.daftari.app.export

import com.daftari.app.core.AppInfo
import com.daftari.app.core.Currency
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.PartySummary
import com.daftari.app.domain.Role
import com.daftari.app.domain.Statement
import java.time.LocalDate

/** A ready-to-render table: header block (business, owner, year), columns, rows, a totals row. Columns are listed right→left. */
data class Report(
    val fileStem: String,
    val title: String,
    val businessName: String,
    val ownerName: String,
    val year: String,
    val subtitle: String,
    val columns: List<String>,
    /** relative widths, same size as columns */
    val weights: List<Float>,
    val rows: List<List<String>>,
    val totals: List<String>?,
    /** column indexes rendered red when the value is not zero */
    val alertColumns: Set<Int> = emptySet(),
    val footer: String = "صدر من تطبيق ${AppInfo.NAME} · ${AppInfo.NAME_EN}",
    val summary: List<Pair<String, String>> = emptyList(),
    val landscape: Boolean = false,
)

object ReportBuilder {
    fun partyStatement(st: Statement, role: Role, business: String, owner: String, currency: Currency): Report {
        val p = st.party
        val who = if (p.kind == PartyKind.SHOP) "محل ${p.shopNumber} · ${p.name}" else p.name
        val rows = ArrayList<List<String>>()
        rows += listOf(Dates.numeric(st.from), "رصيد سابق", "", "", Money.plain(st.opening))
        st.lines.forEach { l ->
            rows += listOf(Dates.numeric(l.date), l.description, if (l.debit > 0) Money.plain(l.debit) else "", if (l.credit > 0) Money.plain(l.credit) else "", Money.plain(l.balance))
        }
        return Report(
            fileStem = "statement_" + (p.shopNumber.ifBlank { p.id.toString() }),
            title = "كشف حساب: $who",
            businessName = business.ifBlank { AppInfo.NAME },
            ownerName = owner,
            year = st.to.year.toString(),
            subtitle = "من ${Dates.full(st.from)} إلى ${Dates.full(st.to)} · المبالغ بـ ${currency.code}" + (if (p.activity.isNotBlank()) " · ${p.activity}" else ""),
            columns = listOf("التاريخ", "البيان", "عليه", "دفع", "الرصيد"),
            weights = listOf(1.25f, 2.6f, 1.2f, 1.2f, 1.3f),
            rows = rows,
            totals = listOf("", "الإجمالي", Money.plain(st.totalDebit), Money.plain(st.totalCredit), Money.plain(st.closing)),
            summary = listOf(
                (if (role == Role.LANDLORD) "المستأجر" else "الزبون") to p.name,
                "الجوال" to p.phone.ifBlank { "—" },
                "المتبقي" to Money.plain(st.closing, currency),
            ),
        )
    }

    fun allParties(
        summaries: List<PartySummary>, entries: List<com.daftari.app.domain.Entry>, role: Role, business: String, owner: String,
        currency: Currency, from: LocalDate, to: LocalDate,
    ): Report {
        val landlord = role == Role.LANDLORD
        val sorted = summaries.sortedWith(compareBy({ it.party.shopNumber.toIntOrNull() ?: Int.MAX_VALUE }, { it.party.shopNumber }, { it.party.name }))
        val byParty = entries.groupBy { it.partyId }
        var collected = 0L
        var arrears = 0L
        var units = 0
        val rows = sorted.mapIndexed { i, s ->
            val es = byParty[s.party.id].orEmpty()
            val pays = es.filter { it.type == com.daftari.app.domain.EntryType.PAYMENT && !it.date.isAfter(to) }
            val inPeriod = pays.filter { !it.date.isBefore(from) }.sumOf { it.amount }
            val last = pays.maxWithOrNull(compareBy({ it.date }, { it.createdAt }))
            val bal = com.daftari.app.domain.Ledger.balance(s.party, es, to).coerceAtLeast(0)
            collected += inPeriod; arrears += bal; units += s.party.unitsCount.coerceAtLeast(1)
            if (landlord) listOf(
                s.party.shopNumber, s.party.name, s.party.unitsCount.coerceAtLeast(1).toString(), s.party.activity,
                if (s.party.periodicAmount > 0) Money.plain(s.party.periodicAmount) + " " + s.party.period.labelAr else "",
                if (inPeriod > 0) Money.plain(inPeriod) else "", if (bal > 0) Money.plain(bal) else "0",
                last?.let { Dates.numeric(it.date) } ?: "", last?.collector.orEmpty(), last?.receiptNo.orEmpty(), s.party.notes.lineSequence().firstOrNull().orEmpty(),
            ) else listOf(
                (i + 1).toString(), s.party.name, s.party.phone, Money.plain(s.totalDue), if (inPeriod > 0) Money.plain(inPeriod) else "",
                if (bal > 0) Money.plain(bal) else "0", last?.let { Dates.numeric(it.date) } ?: "", s.status.labelAr,
            )
        }
        val period = "من ${Dates.full(from)} إلى ${Dates.full(to)}"
        return if (landlord) Report(
            fileStem = "market_statement_${to}",
            title = "كشف تحصيل المستأجرين",
            businessName = business.ifBlank { AppInfo.NAME },
            ownerName = owner,
            year = to.year.toString(),
            subtitle = "$period · المبالغ بـ ${currency.code}",
            columns = listOf("رقم المحل", "المستأجر", "عدد المحلات", "النشاط", "الإيجار", "المدفوع للفترة", "المتأخرات", "آخر دفعة", "المحصل", "رقم السند", "ملاحظة"),
            weights = listOf(0.75f, 1.9f, 0.7f, 1.3f, 1.25f, 1.1f, 1.1f, 1.05f, 1.1f, 0.8f, 1.6f),
            rows = rows,
            totals = listOf("", "الإجمالي (${summaries.size})", units.toString(), "", "", Money.plain(collected), Money.plain(arrears), "", "", "", ""),
            alertColumns = setOf(6),
            summary = listOf("عدد المحلات" to units.toString(), "المحصّل للفترة" to Money.plain(collected, currency), "إجمالي المتأخرات" to Money.plain(arrears, currency)),
            landscape = true,
        ) else Report(
            fileStem = "customers_statement_${to}",
            title = "كشف حساب جميع الزبائن",
            businessName = business.ifBlank { AppInfo.NAME },
            ownerName = owner,
            year = to.year.toString(),
            subtitle = "$period · المبالغ بـ ${currency.code}",
            columns = listOf("م", "الزبون", "الجوال", "إجمالي الدين", "دفع للفترة", "المتبقي", "آخر دفعة", "الحالة"),
            weights = listOf(0.45f, 2.0f, 1.6f, 1.15f, 1.1f, 1.15f, 1.1f, 0.8f),
            rows = rows,
            totals = listOf("", "الإجمالي (${summaries.size})", "", Money.plain(summaries.sumOf { it.totalDue }), Money.plain(collected), Money.plain(arrears), "", ""),
            alertColumns = setOf(5),
            summary = listOf("عدد الزبائن" to summaries.size.toString(), "المحصّل للفترة" to Money.plain(collected, currency), "إجمالي المتبقي" to Money.plain(arrears, currency)),
        )
    }

    /** «تفاصيل جميع المبالغ»: every entry in the range, newest first. */
    fun allEntries(parties: List<com.daftari.app.domain.Party>, entries: List<com.daftari.app.domain.Entry>, role: Role, business: String, owner: String, currency: Currency, from: LocalDate, to: LocalDate): Report {
        val byId = parties.associateBy { it.id }
        val list = entries.filter { !it.date.isBefore(from) && !it.date.isAfter(to) && byId.containsKey(it.partyId) }
            .sortedWith(compareByDescending<com.daftari.app.domain.Entry> { it.date }.thenByDescending { it.createdAt })
        var debit = 0L; var credit = 0L
        val rows = list.mapIndexed { i, e ->
            val p = byId.getValue(e.partyId)
            val isPay = e.type == com.daftari.app.domain.EntryType.PAYMENT
            if (isPay) credit += e.amount else debit += e.amount
            listOf((i + 1).toString(), Dates.numeric(e.date), p.title, listOf(e.note, e.items.replace('\n', '،')).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { if (isPay) "تسديد" else "دين" },
                if (!isPay) Money.plain(e.amount) else "", if (isPay) Money.plain(e.amount) else "", p.category)
        }
        return Report(
            fileStem = "all_entries_$to", title = "تفاصيل جميع المبالغ", businessName = business.ifBlank { AppInfo.NAME }, ownerName = owner, year = to.year.toString(),
            subtitle = "من ${Dates.full(from)} إلى ${Dates.full(to)} · المبالغ بـ ${currency.code}",
            columns = listOf("م", "التاريخ", if (role == Role.LANDLORD) "المحل" else "الزبون", "البيان", "عليه", "له/دفع", "التصنيف"),
            weights = listOf(0.45f, 1.1f, 1.7f, 2.4f, 1.1f, 1.1f, 1.1f), rows = rows,
            totals = listOf("", "", "الإجمالي (${rows.size})", "", Money.plain(debit), Money.plain(credit), ""),
            summary = listOf("عدد العمليات" to rows.size.toString(), "الصافي" to Money.plain(debit - credit, currency)),
        )
    }

    /** «إجمالي شهري»: debts vs payments per month. */
    fun monthly(entries: List<com.daftari.app.domain.Entry>, business: String, owner: String, currency: Currency, from: LocalDate, to: LocalDate): Report {
        val inRange = entries.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val byMonth = inRange.groupBy { java.time.YearMonth.from(it.date) }.toSortedMap()
        var d = 0L; var c = 0L
        val rows = byMonth.map { (ym, es) ->
            val debit = es.filter { it.type != com.daftari.app.domain.EntryType.PAYMENT }.sumOf { it.amount }
            val credit = es.filter { it.type == com.daftari.app.domain.EntryType.PAYMENT }.sumOf { it.amount }
            d += debit; c += credit
            listOf("${Dates.months[ym.monthValue - 1]} ${ym.year}", Money.plain(debit), Money.plain(credit), Money.plain(debit - credit), es.size.toString())
        }
        return Report(
            fileStem = "monthly_$to", title = "الإجمالي الشهري", businessName = business.ifBlank { AppInfo.NAME }, ownerName = owner, year = to.year.toString(),
            subtitle = "من ${Dates.full(from)} إلى ${Dates.full(to)} · المبالغ بـ ${currency.code}",
            columns = listOf("الشهر", "ديون جديدة", "تحصيل", "الصافي", "عدد العمليات"), weights = listOf(1.5f, 1.2f, 1.2f, 1.2f, 0.9f), rows = rows,
            totals = listOf("الإجمالي", Money.plain(d), Money.plain(c), Money.plain(d - c), inRange.size.toString()),
        )
    }

    /** «إجمالي التصنيفات»: balance per category. */
    fun byCategory(summaries: List<PartySummary>, business: String, owner: String, currency: Currency, today: LocalDate): Report {
        val groups = summaries.groupBy { it.party.category.ifBlank { "عام" } }.toSortedMap()
        val rows = groups.map { (cat, list) -> listOf(cat, list.size.toString(), Money.plain(list.sumOf { it.totalDue }), Money.plain(list.sumOf { it.totalPaid }), Money.plain(list.sumOf { it.balance.coerceAtLeast(0) })) }
        return Report(
            fileStem = "categories_$today", title = "إجمالي التصنيفات", businessName = business.ifBlank { AppInfo.NAME }, ownerName = owner, year = today.year.toString(),
            subtitle = "حتى ${Dates.full(today)} · المبالغ بـ ${currency.code}",
            columns = listOf("التصنيف", "العدد", "إجمالي الدين", "المسدد", "المتبقي"), weights = listOf(1.6f, 0.7f, 1.2f, 1.2f, 1.2f), rows = rows,
            totals = listOf("الإجمالي", summaries.size.toString(), Money.plain(summaries.sumOf { it.totalDue }), Money.plain(summaries.sumOf { it.totalPaid }), Money.plain(summaries.sumOf { it.balance.coerceAtLeast(0) })),
        )
    }

    /** A custom table the «مدين» chat designed. */
    fun custom(title: String, columns: List<String>, rows: List<List<String>>, business: String, owner: String, today: LocalDate): Report {
        val n = columns.size.coerceAtLeast(1)
        return Report(
            fileStem = "table_${today}", title = title.ifBlank { "جدول" }, businessName = business.ifBlank { AppInfo.NAME }, ownerName = owner,
            year = today.year.toString(), subtitle = Dates.full(today), columns = columns, weights = List(n) { 1f },
            rows = rows.map { r -> List(n) { i -> r.getOrElse(i) { "" } } }, totals = null,
        )
    }
}
