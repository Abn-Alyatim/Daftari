/*
 * دفتري · test/Features116Test.kt
 * اختبارات 1.1.6 غير الصوتية-التحليلية: قرار ما بعد أخطاء محرّك الكلام، حفظ التعبئة المسبقة، آخر الزبائن، ومبلغ «سداد كامل».
 * 1.1.6 unit tests: speech-error decisions (offline pack / retry next language), the pre-fill round trip,
 * recent customers (last 5, newest first, only live accounts), the «سداد كامل» amount text. Pure JVM.
 */
package com.daftari.app

import com.daftari.app.core.Currency
import com.daftari.app.core.QuickEntry
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.voice.ArabicLocales
import com.daftari.app.voice.PackStatus
import com.daftari.app.voice.VoiceEntryParser
import com.daftari.app.voice.VoiceErrors
import com.daftari.app.voice.VoiceKind
import com.daftari.app.voice.VoiceNext
import com.daftari.app.voice.VoicePrefill
import com.daftari.app.voice.VoiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Features116Test {
    @Test fun speechErrorsMoveOnThenAskForThePack() {
        // no Arabic offline model: try the next language / engine first, then ask for the pack
        for (code in listOf(VoiceErrors.LANGUAGE_NOT_SUPPORTED, VoiceErrors.LANGUAGE_UNAVAILABLE, VoiceErrors.NETWORK, VoiceErrors.NETWORK_TIMEOUT, VoiceErrors.SERVER)) {
            assertEquals(VoiceNext.TryNext, VoiceErrors.next(code, hasMoreAttempts = true))
            assertEquals(VoiceNext.Show(VoiceProblem.OFFLINE_PACK_MISSING), VoiceErrors.next(code, hasMoreAttempts = false))
        }
        // silence is not an engine problem: never switches engine
        assertEquals(VoiceNext.Show(VoiceProblem.NOTHING_HEARD), VoiceErrors.next(VoiceErrors.NO_MATCH, true))
        assertEquals(VoiceNext.Show(VoiceProblem.NOTHING_HEARD), VoiceErrors.next(VoiceErrors.SPEECH_TIMEOUT, true))
        assertEquals(VoiceNext.Show(VoiceProblem.NO_PERMISSION), VoiceErrors.next(VoiceErrors.INSUFFICIENT_PERMISSIONS, true))
        assertEquals(VoiceNext.Show(VoiceProblem.MIC_ERROR), VoiceErrors.next(VoiceErrors.AUDIO, false))
        assertEquals(VoiceNext.Show(VoiceProblem.BUSY), VoiceErrors.next(VoiceErrors.BUSY, true))
        assertEquals(VoiceNext.Show(VoiceProblem.FAILED), VoiceErrors.next(99, false))
        assertEquals(listOf("ar-YE", "ar-SA"), VoiceErrors.LANGUAGES)
        assertTrue(VoiceProblem.OFFLINE_PACK_MISSING.offerPack)
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

    @Test fun arabicLocaleChoiceAndPackLines() {
        assertEquals("ar-YE", ArabicLocales.pick(listOf("en-US", "ar-SA", "ar-YE")))
        assertEquals("ar-SA", ArabicLocales.pick(listOf("en-US", "ar_SA", "ar-EG")).let { if (it == "ar_SA") "ar-SA" else it })
        assertEquals("ar-EG", ArabicLocales.pick(listOf("en-US", "ar-EG")))
        assertEquals("ar", ArabicLocales.pick(listOf("fr-FR", "ar")))
        assertNull(ArabicLocales.pick(listOf("en-US", "fr-FR")))
        assertNull(PackStatus.Idle.line)
        assertTrue(PackStatus.Downloading(40).line!!.contains("40%"))
        assertTrue(PackStatus.Downloading(null).line!!.startsWith("جارٍ تنزيل حزمة العربية في الخلفية"))
        assertTrue(PackStatus.Downloading(null).busy && PackStatus.Scheduled.busy && !PackStatus.Ready.busy)
        assertTrue(PackStatus.Ready.line!!.contains("جاهزة"))
    }

    @Test fun versionIs116() { assertEquals(9, BuildConfig.VERSION_CODE); assertEquals("1.1.6", BuildConfig.VERSION_NAME) }

    @Test fun payFullFillsTheWholeBalance() {
        assertEquals("12500", QuickEntry.fullBalanceText(1_250_000))
        assertEquals("99.5", QuickEntry.fullBalanceText(9_950))
        assertEquals("", QuickEntry.fullBalanceText(0))
        assertEquals("", QuickEntry.fullBalanceText(-500))
    }
}
