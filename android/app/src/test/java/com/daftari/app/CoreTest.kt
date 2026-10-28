package com.daftari.app

import com.daftari.app.core.Calc
import com.daftari.app.core.Currency
import com.daftari.app.core.Money
import com.daftari.app.core.Plan
import com.daftari.app.data.AppSettings
import com.daftari.app.data.Demo
import com.daftari.app.data.LedgerRepository
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Role
import com.daftari.app.export.DocxWriter
import com.daftari.app.export.ReportBuilder
import com.daftari.app.export.XlsxWriter
import com.daftari.app.importer.SheetImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

class CoreTest {
    private val today = LocalDate.of(2026, 10, 28)

    private fun seeded(role: Role): Pair<List<com.daftari.app.domain.Party>, List<com.daftari.app.domain.Entry>> {
        val seed = Demo.forRole(role, today)
        val parties = seed.parties.mapIndexed { i, p -> p.copy(id = i + 1L) }
        return parties to seed.entries(parties.map { it.id })
    }

    @Test fun amountFieldIsACalculator() {
        assertEquals(400_000L, Money.parse("1500+2500"))
        assertEquals(360_000L, Money.parse("3*1200"))
        assertEquals(150_000L, Money.parse("(500+250)*2"))
        assertEquals(900_000L, Money.parse("10000-10%"))
        assertEquals(250_000L, Money.parse("٢٥٠٠"))
        assertEquals(125L, Money.parse("1.25"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("5+"))
        assertTrue(Calc.isExpression("5+3")); assertFalse(Calc.isExpression("-5"))
    }

    @Test fun moneyFormatsWithCode() {
        assertTrue(Money.format(1_250_000, Currency.YER).contains("12,500"))
        assertTrue(Money.format(1_250_000, Currency.YER).contains("YER"))
    }

    @Test fun freeTrialThenPaywall() {
        val day = 24 * 3600_000L
        val fresh = AppSettings(trialStartedAt = 1_000_000L)
        assertTrue(Plan.isPro(fresh, 1_000_000L + 2 * day))
        assertEquals(5, Plan.trialDaysLeft(fresh, 1_000_000L + 2 * day))
        val expired = fresh
        val after = 1_000_000L + 8 * day
        assertFalse(Plan.isPro(expired, after))
        assertTrue(Plan.allows(expired, Plan.Feature.UNLIMITED, Plan.FREE_PARTIES - 1, after))
        assertFalse(Plan.allows(expired, Plan.Feature.UNLIMITED, Plan.FREE_PARTIES, after))
        assertFalse(Plan.allows(expired, Plan.Feature.AI_CHAT, 0, after))
        assertTrue(Plan.isPro(expired.copy(proUntil = after + day), after))
    }

    @Test fun landlordDashboardAndStatement() {
        val (parties, entries) = seeded(Role.LANDLORD)
        assertEquals(27, parties.size)
        val byParty = entries.groupBy { it.partyId }
        val sums = parties.map { Ledger.summarize(it, byParty[it.id].orEmpty(), today) }
        val d = LedgerRepository.buildDashboard(sums, entries, today)
        assertEquals(sums.sumOf { it.balance.coerceAtLeast(0) }, d.totalOwed)
        val r = ReportBuilder.allParties(sums, entries, Role.LANDLORD, "سوق الكحلاني", "سامي الهلباء", Currency.YER, today.withDayOfMonth(1), today)
        assertTrue(r.landscape)
        assertEquals(listOf("رقم المحل", "المستأجر", "عدد المحلات", "النشاط", "الإيجار", "المدفوع للفترة", "المتأخرات", "آخر دفعة", "المحصل", "رقم السند", "ملاحظة"), r.columns)
        assertEquals(27, r.rows.size)
        assertTrue(r.rows.all { it.size == r.columns.size })
    }

    @Test fun groceryModeHasDueAndLimits() {
        val (parties, entries) = seeded(Role.SHOP_OWNER)
        val byParty = entries.groupBy { it.partyId }
        val sums = parties.map { Ledger.summarize(it, byParty[it.id].orEmpty(), today) }
        val d = LedgerRepository.buildDashboard(sums, entries, today)
        assertTrue(parties.any { it.creditLimit > 0 })
        assertTrue(d.totalOwed > 0)
        val r = ReportBuilder.allParties(sums, entries, Role.SHOP_OWNER, "بقالة الأمانة", "عبدالله أحمد", Currency.YER, today.withDayOfMonth(1), today)
        assertEquals("الزبون", r.columns[1])
        val st = Ledger.statement(parties[0], entries, today.minusMonths(2), today)
        val pr = ReportBuilder.partyStatement(st, Role.SHOP_OWNER, "بقالة الأمانة", "عبدالله أحمد", Currency.YER)
        assertEquals(5, pr.columns.size)
    }

    @Test fun extraReports() {
        val (parties, entries) = seeded(Role.SHOP_OWNER)
        val byParty = entries.groupBy { it.partyId }
        val sums = parties.map { Ledger.summarize(it, byParty[it.id].orEmpty(), today) }
        val det = ReportBuilder.allEntries(parties, entries, Role.SHOP_OWNER, "ب", "ع", Currency.YER, LocalDate.of(2000, 1, 1), today)
        assertEquals(entries.size, det.rows.size)
        val m = ReportBuilder.monthly(entries, "ب", "ع", Currency.YER, LocalDate.of(2000, 1, 1), today)
        assertTrue(m.rows.isNotEmpty())
        val cat = ReportBuilder.byCategory(sums.map { it.copy(party = it.party.copy(category = if (it.party.id % 2 == 0L) "الحارة" else "")) }, "ب", "ع", Currency.YER, today)
        assertEquals(setOf("الحارة", "عام"), cat.rows.map { it[0] }.toSet())
    }

    private fun unzip(b: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return out
    }

    @Test fun excelAndWordAreValidRtlFiles() {
        val r = ReportBuilder.custom("جدول", listOf("الاسم", "المبلغ", "الجوال"), listOf(listOf("أحمد", "12,500", "0777123456")), "سوق", "مالك", today)
        val x = unzip(XlsxWriter.build(r))
        val sheet = x.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("rightToLeft=\"1\""))
        assertTrue(sheet.contains("<v>12500</v>"))          // numbers stay numbers
        assertTrue(sheet.contains("0777123456"))            // phones stay text
        assertTrue(x.containsKey("[Content_Types].xml"))
        val w = unzip(DocxWriter.build(r))
        assertTrue(w.getValue("word/document.xml").contains("أحمد"))
    }

    @Test fun importsTheKahlaniSheet() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("kahlani.docx")!!.readBytes()
        val res = SheetImporter.parse("kahlani.docx", bytes)
        assertEquals(27, res.rows.size)
        assertEquals("أيوب العبدلي", res.rows.first().name)
    }

    @Test fun importsCsv() {
        val res = SheetImporter.parse("x.csv", "الاسم,الجوال,المبلغ\nسالم,777111222,5000\nعلي,,\n".toByteArray())
        assertEquals(listOf("سالم", "علي"), res.rows.map { it.name })
    }
}
