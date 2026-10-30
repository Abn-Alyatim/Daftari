/*
 * دفتري · test/VoiceEntryParserTest.kt
 * اختبارات الإدخال الصوتي (1.1.6): قراءة الأرقام المنطوقة وفهم الجملة وتعبئة الحقول ومطابقة اسم الزبون.
 * Unit tests for voice entry: spoken numbers, phrase → fields, fuzzy customer matching. Pure JVM (no Android).
 * CASES is also the phrase → fields table quoted in the 1.1.6 notes.
 */
package com.daftari.app

import com.daftari.app.core.Currency
import com.daftari.app.chat.ArabicText
import com.daftari.app.voice.SpokenNumbers
import com.daftari.app.voice.VoiceCustomer
import com.daftari.app.voice.VoiceEntryParser
import com.daftari.app.voice.VoiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class VoiceEntryParserTest {
    companion object {
        val BOOK = listOf(
            VoiceCustomer(1, "علي أحمد"), VoiceCustomer(2, "محمد صالح"), VoiceCustomer(3, "خالد سعيد"), VoiceCustomer(4, "عبدالله"),
            VoiceCustomer(5, "فاطمة الحميري"), VoiceCustomer(6, "يحيى"), VoiceCustomer(7, "محمد علي"), VoiceCustomer(8, "أبو علي"),
            VoiceCustomer(9, "محل 12"), VoiceCustomer(10, "إبراهيم ناصر"), VoiceCustomer(11, "سعيد"),
        )

        /** expected: kind, amount ("" = none), currency, customer id (null = none), sure, new name, note, option ids */
        data class Case(
            val text: String, val kind: VoiceKind?, val amount: String, val cur: Currency? = null, val id: Long? = null, val sure: Boolean = false,
            val newName: String = "", val note: String = "", val options: List<Long> = emptyList(),
        )

        private val D = VoiceKind.DEBT
        private val P = VoiceKind.PAYMENT

        val CASES = listOf(
            Case("علي أحمد عليه خمسة آلاف ريال", D, "5000", id = 1, sure = true),
            Case("سجل على محمد صالح ألفين وخمسمية", D, "2500", id = 2, sure = true),
            Case("سدد خالد ثلاثة آلاف", P, "3000", id = 3, newName = "خالد"),
            Case("أخذ مني عبدالله مية دولار", D, "100", Currency.USD, id = 4, sure = true),
            Case("أخذ مني عبد الله مية دولار", D, "100", Currency.USD, id = 4, sure = true),
            Case("سدد علي ألفين", P, "2000", id = 1, newName = "علي"),
            Case("دفع خالد سعيد ثلاثة آلاف", P, "3000", id = 3, sure = true),
            Case("محمد عليه عشرين ألف", D, "20000", newName = "محمد", options = listOf(2, 7)),
            Case("سجل على يحيى خمسمية ريال سعودي", D, "500", Currency.SAR, id = 6, sure = true),
            Case("يحيي سدد ألف ونص", P, "1500", id = 6, sure = true),
            Case("فاطمه الحميري عليها 3000", D, "3000", id = 5, sure = true),
            Case("سجل على فاطمة الحميدي ألفين", D, "2000", id = 5, newName = "فاطمة الحميدي"),
            Case("استلمت من سعيد خمسة آلاف", P, "5000", id = 11, sure = true),
            Case("وصلني من إبراهيم ناصر 300 سعودي", P, "300", Currency.SAR, id = 10, sure = true),
            Case("اكتب على أبو علي خمسة وعشرين ألف", D, "25000", id = 8, sure = true),
            Case("سجل على حسن مراد عشرة آلاف", D, "10000", newName = "حسن مراد"),
            Case("عمر الشامي عليه 7500", D, "7500", newName = "عمر الشامي"),
            Case("سدد ناصر الحداد مية وخمسين", P, "150", newName = "ناصر الحداد"),
            Case("اشترى محمد صالح بألفين", D, "2000", id = 2, sure = true),
            Case("تسلف خالد سعيد مليون", D, "1000000", id = 3, sure = true),
            Case("محل 12 دفع خمسين ألف", P, "50000", id = 9, sure = true),
            Case("سجل على محمد صالح ثلاثمية حق غاز", D, "300", id = 2, sure = true, note = "غاز"),
            Case("علي أحمد عليه ألفين قيمة كيس رز", D, "2000", id = 1, sure = true, note = "كيس رز"),
            Case("سجل محمد صالح خمسة آلاف", null, "5000", id = 2, sure = true),
            Case("خمسة آلاف", null, "5000"),
            Case("سدد خالد", P, "", id = 3, newName = "خالد"),
            Case("علي أحمد", null, "", id = 1, sure = true),
            Case("عطاني علي أحمد ٢٠٠٠", P, "2000", id = 1, sure = true),
            Case("اعطيت محمد صالح ميتين دولار", D, "200", Currency.USD, id = 2, sure = true),
            Case("قبضت من عبدالله ألف ريال يمني", P, "1000", Currency.YER, id = 4, sure = true),
            Case("سجل دين على عمر 2500 ريال", D, "2500", newName = "عمر"),
            Case("علي أحمد عليه نص مليون", D, "500000", id = 1, sure = true),
            Case("سدد محمد علي ثلاثة آلاف وخمسمية", P, "3500", id = 7, sure = true),
            Case("يا دفتري سجل على خالد سعيد أربعة آلاف لو سمحت", D, "4000", id = 3, sure = true),
            Case("خالد سعيد سدد خمستالاف", P, "5000", id = 3, sure = true),
            Case("ابراهيم ناصر عليه حداش ألف", D, "11000", id = 10, sure = true),
            Case("سدد سعيد كامل المبلغ", P, "", id = 11, sure = true),
            Case("دفعة من محمد صالح 4000 دولار", P, "4000", Currency.USD, id = 2, sure = true),
            Case("سجل على محمد صالح 3 أكياس بخمسة آلاف", D, "5000", id = 2, sure = true),
            Case("سدد خالد سعيد ألفين وأخذ مني ألف", null, "2000", id = 3, sure = true),
            Case("علي أحمد عليه خمسة آلاف رقمه 777123456", D, "5000", id = 1, sure = true),
            Case("سجل على صالح 1,500", D, "1500", newName = "صالح"),
            Case("فاطمه عليها مية ونص", D, "150", id = 5, newName = "فاطمه"),
            Case("تلت تلاف على خالد سعيد", D, "3000", id = 3, sure = true),
            Case("محمد صالح عليه ٥٠٠٠ ريال سعودي", D, "5000", Currency.SAR, id = 2, sure = true),
            Case("سجل لمحمد صالح دفعة ألفين", P, "2000", id = 2, sure = true),
            Case("رجع لي عبدالله خمسمية", P, "500", id = 4, sure = true),
            Case("حول لي خالد سعيد عشرة آلاف بالسعودي", P, "10000", Currency.SAR, id = 3, sure = true),
            Case("سلفت يحيى ثلاثة آلاف دولار", D, "3000", Currency.USD, id = 6, sure = true),
            Case("أعطيته لعلي أحمد 600", D, "600", id = 1, sure = true),
            Case("", null, ""),
        )
    }

    @Test fun spokenNumbers() {
        fun v(s: String) = SpokenNumbers.find(ArabicText.words(s)).firstOrNull()?.value?.toPlainString()
        val table = mapOf(
            "خمسة آلاف" to "5000", "ألفين وخمسمية" to "2500", "مية" to "100", "مائة" to "100", "ميتين" to "200", "مئتين" to "200",
            "ثلاثمية" to "300", "ثلاثمائة وخمسين" to "350", "سبعمية" to "700", "ألف" to "1000", "ألفين" to "2000", "عشرة آلاف" to "10000",
            "خمسة وعشرين ألف" to "25000", "مية وخمسين" to "150", "ألف ونص" to "1500", "ألفين ونص" to "2500", "مية ونص" to "150",
            "خمسة ونص" to "5.5", "مليون" to "1000000", "مليون ونص" to "1500000", "نص مليون" to "500000", "ربع مليون" to "250000",
            "مليونين" to "2000000", "عشرين ألف" to "20000", "أحد عشر ألف" to "11000", "حداش ألف" to "11000", "خمسطعشر ألف" to "15000",
            "خمستالاف" to "5000", "تلت تلاف" to "3000", "اربعتالاف" to "4000", "مئتين وخمسين ألف" to "250000", "5000" to "5000",
            "٥٠٠٠" to "5000", "5,000" to "5000", "2.5 مليون" to "2500000", "ألف وربع" to "1250", "ثلاث مية" to "300", "تسعين" to "90",
            "ألف ومية" to "1100", "مليون وخمسمية ألف" to "1500000", "بألفين" to "2000", "اثنا عشر" to "12",
        )
        for ((s, want) in table) assertEquals(s, want, v(s))
        // phones and stray words are not amounts
        assertEquals(null, v("777123456"))
        assertEquals(null, v("نص"))
        assertEquals(null, v("خالد"))
        // two separate numbers stay separate
        assertEquals(2, SpokenNumbers.find(ArabicText.words("3 أكياس 5000")).size)
    }

    @Test fun phrasesToFields() {
        assertTrue("at least 40 phrases", CASES.size >= 40)
        for (c in CASES) {
            val d = VoiceEntryParser.parse(c.text, BOOK)
            val msg = "«${c.text}» → $d"
            assertEquals(msg, c.kind, d.kind)
            assertEquals(msg, c.amount, d.amountText)
            assertEquals(msg, c.cur, d.currency)
            assertEquals(msg, c.id, d.customer?.id)
            if (c.id != null) assertEquals(msg, c.sure, d.customerSure)
            assertEquals(msg, c.newName, d.newName)
            assertEquals(msg, c.note, d.note)
            assertEquals(msg, c.options, d.options.map { it.id })
        }
    }

    @Test fun nameNormalization() {
        fun m(spoken: String, book: List<VoiceCustomer>) = VoiceEntryParser.matchName(ArabicText.words(spoken), book)
        val book = listOf(VoiceCustomer(1, "إبراهيم"), VoiceCustomer(2, "آمنة"), VoiceCustomer(3, "مصطفى العزي"), VoiceCustomer(4, "عبد الرحمن"))
        assertEquals(1L, m("ابراهيم", book).customer?.id); assertTrue(m("ابراهيم", book).sure)
        assertEquals(2L, m("امنه", book).customer?.id); assertTrue(m("امنه", book).sure)
        assertEquals(3L, m("مصطفي العزي", book).customer?.id); assertTrue(m("مصطفي العزي", book).sure)
        assertEquals(4L, m("عبدالرحمن", book).customer?.id); assertTrue(m("عبدالرحمن", book).sure)
        // a typo is only suggested, never taken as sure
        val typo = m("مصطفى العزى", listOf(VoiceCustomer(3, "مصطفى العزب")))
        assertEquals(3L, typo.customer?.id); assertEquals(false, typo.sure)
        // nobody close: nothing is guessed
        assertEquals(null, m("زيد", book).customer); assertTrue(m("زيد", book).options.isEmpty())
        // empty book: the spoken name is offered as new
        val d = VoiceEntryParser.parse("سجل على زيد الحداد ألف", emptyList())
        assertEquals("زيد الحداد", d.newName); assertEquals(null, d.customer); assertEquals(VoiceKind.DEBT, d.kind)
    }

    @Test fun neverInventsValues() {
        val d = VoiceEntryParser.parse("مرحبا كيف الحال", BOOK)
        assertEquals(null, d.kind); assertEquals("", d.amountText); assertEquals(null, d.currency); assertEquals(null, d.customer)
        assertEquals("", d.note)
        val e = VoiceEntryParser.parse("سجل خمسة آلاف", BOOK)
        assertEquals(null, e.kind); assertEquals("5000", e.amountText); assertEquals("", e.newName); assertEquals(null, e.customer)
        assertEquals(BigDecimal("5000"), e.amount)
    }
}
