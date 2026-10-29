package com.daftari.app

import com.daftari.app.backup.BackupData
import com.daftari.app.backup.DaftariFile
import com.daftari.app.backup.ReadResult
import com.daftari.app.chat.OfflineAssistant
import com.daftari.app.core.CityRates
import com.daftari.app.core.Currency
import com.daftari.app.core.Rates
import com.daftari.app.data.LedgerRepository
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Role
import com.daftari.app.ui.parties.companyIcon
import com.daftari.app.ui.parties.walletIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** 1.1.2: transfer-company logos + حاسب logo; rate table شراء | بيع where only شراء is used; the table travels in .daftari 2.2. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Features112Test {
    private val day = LocalDate.of(2026, 10, 30)
    private fun fixture(n: String) = javaClass.classLoader!!.getResourceAsStream("fixtures/$n")!!.readBytes()

    // ------------------------------------------------------------ logos
    @Test fun transferCompaniesHaveLogos() {
        assertEquals(R.drawable.transfer_kuraimi, companyIcon("الكريمي"))
        assertEquals(R.drawable.transfer_kuraimi, companyIcon("كريمي"))
        assertEquals(R.drawable.transfer_najm, companyIcon("النجم"))
        assertEquals(R.drawable.transfer_hazmi, companyIcon("حزمي"))
        assertNull(companyIcon("العمقي للصرافة")); assertNull(companyIcon("")); assertNull(companyIcon("أخرى"))
        // the same lookup the chips, the rows and the confirm card use
        assertEquals(R.drawable.transfer_najm, walletIcon("النجم"))
    }
    @Test fun hasibHasItsLogo() {
        assertEquals(R.drawable.wallet_hasib, walletIcon("حاسب"))
        assertEquals(R.drawable.wallet_hasib, walletIcon("haseb"))
        assertEquals(R.drawable.wallet_jawali, walletIcon("جوالي"))
        assertNull(walletIcon("محفظة الأمل"))
    }

    // ------------------------------------------------------------ rates: شراء | بيع, only شراء counts
    @Test fun bothRatesParsedAndStored() {
        val got = Rates.parseYeRial(String(fixture("ye_rial.html"), Charsets.UTF_8), "2026-10-30")!!
        got.forEach { c ->
            assertTrue("$c", c.sarBuy.toBigDecimal().signum() > 0 && c.sarSell.toBigDecimal().signum() > 0)
            assertTrue("$c", c.usdBuy.toBigDecimal().signum() > 0 && c.usdSell.toBigDecimal().signum() > 0)
        }
        val r = Rates.parse(Rates().with(CityRates(Rates.SANAA, "139.5", "140.2", "533", "535", "2026-10-30")).toJson())
        assertEquals("139.5", r.current.sarBuy); assertEquals("140.2", r.current.sarSell)
        assertEquals("533", r.current.usdBuy); assertEquals("535", r.current.usdSell)
    }
    @Test fun conversionUsesBuyOnly() {
        val r = Rates()
        assertEquals("139.5", Rates.plain(r.rate("SAR", "YER")!!))
        assertEquals("533", Rates.plain(r.rate("USD", "YER")!!))
        assertEquals(0, (1.toBigDecimal().divide("139.5".toBigDecimal(), 6, java.math.RoundingMode.HALF_UP)).compareTo(r.rate("YER", "SAR")!!))
        // changing بيع never changes a conversion
        val odd = r.with(r.current.copy(sarSell = "999", usdSell = "999"))
        assertEquals(r.rate("SAR", "YER"), odd.rate("SAR", "YER")); assertEquals(r.rate("USD", "SAR"), odd.rate("USD", "SAR"))
        assertEquals(0, "533".toBigDecimal().divide("139.5".toBigDecimal(), 6, java.math.RoundingMode.HALF_UP).compareTo(r.rate("USD", "SAR")!!))
    }

    private fun ctx(): OfflineAssistant.Ctx {
        val ps = listOf(Party(id = 1, kind = PartyKind.CUSTOMER, name = "محمد الصنعاني", currency = "YER", startDate = day))
        val es = listOf(Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 2_000_000, date = day))
        return OfflineAssistant.Ctx(LedgerRepository.buildDashboard(ps.map { Ledger.summarize(it, es, day) }, es, day), es, Role.SHOP_OWNER, Currency.YER, Rates(), day)
    }
    @Test fun debtAndPaymentInSarBothUseBuy() {
        val pay = OfflineAssistant.respond("محمد سدد 100 سعودي", ctx())!!.action!!
        assertEquals("139.5", pay.rate); assertEquals(1_395_000L, pay.minor)
        assertTrue(pay.lines.joinToString("\n"), pay.lines.any { it.contains("بسعر الشراء") })
        val debt = OfflineAssistant.respond("سجل على محمد رز بـ100 سعودي", ctx())!!.action!!
        assertEquals("add_debt", debt.type); assertEquals("139.5", debt.rate); assertEquals(1_395_000L, debt.minor)
        assertFalse(debt.lines.joinToString("\n"), debt.lines.any { it.contains("بيع") })
    }

    // ------------------------------------------------------------ .daftari 2.2
    private fun data(rates: String) = BackupData(
        "book-1", "1.1.2", 1_793_300_000_000, "YER", "بقالة الأمل", "عبدالله", "SHOP_OWNER",
        listOf(Party(id = 1, kind = PartyKind.CUSTOMER, name = "محمد", currency = "YER", startDate = day)),
        listOf(Entry(id = 1, partyId = 1, type = EntryType.PAYMENT, amount = 1_395_000, date = day, currency = "SAR", origAmount = 10_000, rate = "139.5", createdAt = 1L)),
        ratesJson = rates,
    )
    @Test fun backupCarriesBothRates() {
        val table = Rates(Rates.ADEN).with(CityRates(Rates.ADEN, "411", "413", "1568", "1579", "2026-10-30"))
        val r = DaftariFile.read(DaftariFile.write(data(table.toJson()))) as ReadResult.Ok
        assertEquals(2, r.manifest!!.json.optInt("schemaMinor"))
        val back = Rates.parse(r.data.ratesJson)
        assertEquals(table, back); assertEquals("413", back.current.sarSell); assertEquals("1579", back.current.usdSell)
        assertEquals("139.5", r.data.entries.single().rate)
    }
    @Test fun olderBackupWithoutRatesStillOpens() {
        val o = DaftariFile.dataJson(data(""))
        assertFalse(o.has("rates"))
        o.put("schemaMinor", 1)
        val r = DaftariFile.parseData(o, null) as ReadResult.Ok
        assertEquals("", r.data.ratesJson); assertNotNull(r.data.entries.single())
    }
}
