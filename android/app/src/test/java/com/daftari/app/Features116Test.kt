/*
 * دفتري · test/Features116Test.kt
 * اختبارات 1.1.6 غير الصوتية-التحليلية: قرار ما بعد أخطاء محرّك الكلام، حفظ التعبئة المسبقة، آخر الزبائن، ومبلغ «سداد كامل».
 * 1.1.6 unit tests (the Google-recognizer ones were removed in 1.1.7 with that engine): the pre-fill round trip,
 * recent customers (last 5, newest first, only live accounts), the «سداد كامل» amount text. Pure JVM.
 */
package com.daftari.app

import com.daftari.app.core.Currency
import com.daftari.app.core.QuickEntry
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.voice.VoiceEntryParser
import com.daftari.app.voice.VoiceErrors
import com.daftari.app.voice.VoiceKind
import com.daftari.app.voice.VoicePrefill
import com.daftari.app.voice.VoiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Features116Test {
    @Test fun permissionProblemOffersSettings() {
        // 1.1.6's Google recognizer / Arabic pack tests were removed with that engine in 1.1.7 (see Features117Test)
        assertTrue(VoiceProblem.NO_PERMISSION.offerSettings)
    }

    @Test fun bestAlternativeIsTheOneWithAnAmount() {
        assertEquals("سجل على محمد ألفين", VoiceErrors.pickBest(listOf("سجل على محمد الفين يا", "سجل على محمد ألفين").reversed()))
        assertEquals("محمد عليه 5000", VoiceErrors.pickBest(listOf("محمد عليه", "محمد عليه 5000")))
        assertEquals("مرحبا", VoiceErrors.pickBest(listOf(" ", "مرحبا", "اهلا")))
        assertEquals("", VoiceErrors.pickBest(emptyList()))
    }

    @Test fun prefillSurvivesRoundTrip() {
        val p = VoicePrefill("2500", Currency.USD, "كيس رز", "سجل على محمد ألفين وخمسمية دولار حق كيس رز", VoiceKind.DEBT)
        assertEquals(p, VoicePrefill.decode(p.encode()))
        val full = VoicePrefill(amount = "12500")
        assertEquals(full, VoicePrefill.decode(full.encode()))
        assertNull(VoicePrefill.decode(""))
        assertNull(VoicePrefill.decode(null))
        assertNull(VoicePrefill.decode("garbage"))
        val fromDraft = VoicePrefill.of(VoiceEntryParser.parse("سدد خالد ثلاثة آلاف سعودي", emptyList()))
        assertEquals("3000", fromDraft.amount); assertEquals(Currency.SAR, fromDraft.currency); assertEquals(VoiceKind.PAYMENT, fromDraft.kind)
    }

    @Test fun recentCustomersAreTheLastFiveLiveAccounts() {
        var t = 1_000L
        fun e(id: Long, party: Long) = Entry(id = id, partyId = party, type = EntryType.CHARGE, amount = 100, date = LocalDate.of(2026, 10, 1), createdAt = t++)
        val entries = listOf(e(1, 10), e(2, 11), e(3, 12), e(4, 10), e(5, 13), e(6, 14), e(7, 15), e(8, 99), e(9, 16))
        // 99 was deleted from the book; 10 appears twice but only once in the list
        assertEquals(listOf(16L, 15L, 14L, 13L, 10L), QuickEntry.recentPartyIds(entries, setOf(10, 11, 12, 13, 14, 15, 16)))
        assertEquals(emptyList<Long>(), QuickEntry.recentPartyIds(emptyList(), setOf(1)))
        assertEquals(listOf(500L, 1000L, 5000L, 10000L), QuickEntry.PAY_CHIPS)
    }

    @Test fun payFullFillsTheWholeBalance() {
        assertEquals("12500", QuickEntry.fullBalanceText(1_250_000))
        assertEquals("99.5", QuickEntry.fullBalanceText(9_950))
        assertEquals("", QuickEntry.fullBalanceText(0))
        assertEquals("", QuickEntry.fullBalanceText(-500))
    }
}
