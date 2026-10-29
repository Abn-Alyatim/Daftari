package com.daftari.app

import com.daftari.app.chat.ArabicNumbers
import com.daftari.app.chat.ArabicText
import com.daftari.app.chat.Command
import com.daftari.app.chat.Cur
import com.daftari.app.chat.Customer
import com.daftari.app.chat.NameMatch
import com.daftari.app.chat.Nlu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** «مدين» offline: ~35 real Arabic commands (فصحى + يمني + خليجي) → the right operation and entities. */
class NluTest {
    private val book = listOf(
        Customer(1, "محمد علي الضبيبي", "777123456"), Customer(2, "سعيد المنصري"), Customer(3, "فوزي سعيد"),
        Customer(4, "عبدالله الحميري"), Customer(5, "أم خالد"), Customer(6, "صالح حسن"), Customer(7, "أحمد ناصر"), Customer(8, "أحمد يحيى"),
    )
    private val today = LocalDate.of(2026, 10, 30)
    private val now = LocalDateTime.of(2026, 10, 30, 0, 15)
    private fun p(t: String) = Nlu.parse(t, book, today, now)
    private fun whole(c: Command): Long = when (c) {
        is Command.AddDebt -> c.amount!!.value.toLong(); is Command.AddPayment -> c.amount!!.value.toLong(); else -> -1
    }
    private fun who(c: Command): Long = when (c) {
        is Command.AddDebt -> (c.who as NameMatch.Found).c.id
        is Command.AddPayment -> (c.who as NameMatch.Found).c.id
        is Command.Balance -> (c.who as NameMatch.Found).c.id
        is Command.Statement -> (c.who as NameMatch.Found).c.id
        else -> -1
    }
    private inline fun <reified X : Command> debt(t: String, id: Long, amount: Long, items: String? = null): X {
        val c = p(t); assertTrue("$t → $c", c is X); assertEquals(t, id, who(c)); assertEquals(t, amount, whole(c))
        if (items != null) assertEquals(t, items, (c as Command.AddDebt).items)
        return c as X
    }

    @Test fun c01_debt_items_ba() { debt<Command.AddDebt>("سجل على محمد رز وسكر بـ5000", 1, 5000, "رز وسكر") }
    @Test fun c02_debt_hindi_digits() { debt<Command.AddDebt>("سجّل على محمد رز وسكر بـ٥٠٠٠", 1, 5000, "رز وسكر") }
    @Test fun c03_payment_simple() { debt<Command.AddPayment>("محمد سدد 2000", 1, 2000) }
    @Test fun c04_payment_words() { debt<Command.AddPayment>("سعيد دفع خمسة آلاف", 2, 5000) }
    @Test fun c05_debt_alf() { debt<Command.AddDebt>("اكتب على فوزي ٣ الف", 3, 3000) }
    @Test fun c06_debt_yemeni_shal() { debt<Command.AddDebt>("صالح شل كيس دقيق بعشرين ألف", 6, 20000, "كيس دقيق") }
    @Test fun c07_debt_compound_words() { debt<Command.AddDebt>("قيد على سعيد ألفين وخمسمية حق غاز", 2, 2500, "غاز") }
    @Test fun c08_payment_received_from() { debt<Command.AddPayment>("استلمت من فوزي 1500 ريال", 3, 1500) }
    @Test fun c09_payment_gave_me() { debt<Command.AddPayment>("عبدالله الحميري عطاني عشرة آلاف", 4, 10000) }
    @Test fun c10_debt_lam_prefix() { debt<Command.AddDebt>("حط لمحمد 750 زيت", 1, 750, "زيت") }
    @Test fun c11_debt_alf_wa_nus() { debt<Command.AddDebt>("سجل على ام خالد الف ونص", 5, 1500) }
    @Test fun c12_payment_comma() { debt<Command.AddPayment>("سدد سعيد المنصري 12,500", 2, 12500) }
    @Test fun c13_typo_name() { debt<Command.AddPayment>("مخمد سدد 300", 1, 300) }
    @Test fun c14_sar_currency() {
        val c = debt<Command.AddDebt>("سجل على صالح 200 ريال سعودي", 6, 200); assertEquals(Cur.SAR, c.amount!!.currency)
    }
    @Test fun c15_usd_currency() {
        val c = debt<Command.AddPayment>("فوزي دفع 50 دولار", 3, 50); assertEquals(Cur.USD, c.amount!!.currency)
    }
    @Test fun c16_yesterday() { val c = debt<Command.AddDebt>("امس محمد اخذ بيض بـ1200", 1, 1200); assertEquals(today.minusDays(1), c.date) }
    @Test fun c17_ambiguous_ahmad() {
        val c = p("سجل على أحمد 400"); assertTrue(c is Command.AddDebt); assertTrue((c as Command.AddDebt).who is NameMatch.Ambiguous)
        assertEquals(2, (c.who as NameMatch.Ambiguous).options.size)
    }
    @Test fun c18_unknown_customer_debt() {
        val c = p("سجل على ياسر 900 شاهي") as Command.AddDebt
        assertEquals("ياسر", (c.who as NameMatch.NotFound).guess); assertEquals(900L, c.amount!!.value.toLong()); assertEquals("شاهي", c.items)
    }
    @Test fun c19_balance() { assertEquals(1L, who(p("كم على محمد؟")).also { assertTrue(p("كم على محمد؟") is Command.Balance) }) }
    @Test fun c20_balance_gulf() { assertTrue(p("كم باقي على سعيد") is Command.Balance) }
    @Test fun c21_statement() { val c = p("طلع كشف حساب فوزي"); assertTrue("$c", c is Command.Statement); assertEquals(3L, who(c)) }
    @Test fun c22_top() { assertTrue(p("مين أكثر واحد عليه؟") is Command.TopDebtors) }
    @Test fun c23_top_n() { val c = p("أعلى 5 مديونين"); assertTrue(c is Command.TopDebtors); assertEquals(5, (c as Command.TopDebtors).n) }
    @Test fun c24_today() { assertTrue(p("كم حصلت اليوم") is Command.Today) }
    @Test fun c25_today2() { assertTrue(p("ملخص اليوم") is Command.Today) }
    @Test fun c26_create_customer() {
        val c = p("ضيف زبون جديد اسمه ياسر القدسي رقمه 771234567") as Command.CreateCustomer
        assertEquals("ياسر القدسي", c.name); assertEquals("771234567", c.phone)
    }
    @Test fun c27_create_customer_short() { val c = p("افتح حساب لعلي عبده") as Command.CreateCustomer; assertEquals("لعلي عبده", c.name) }
    @Test fun c28_reminder_tomorrow() {
        val c = p("ذكرني أطالب محمد بكرة الساعة 10") as Command.Reminder
        assertEquals(LocalDateTime.of(2026, 10, 31, 10, 0), c.at); assertEquals(1L, (c.who as NameMatch.Found).c.id)
    }
    @Test fun c29_reminder_evening() { val c = p("نبهني الساعة 5 العصر أتصل بسعيد") as Command.Reminder; assertEquals(LocalDateTime.of(2026, 10, 30, 17, 0), c.at) }
    @Test fun c30_reminder_after() { val c = p("ذكرني بعد ساعتين") as Command.Reminder; assertEquals(now.plusHours(2), c.at) }
    @Test fun c31_reminder_weekday() { val c = p("ذكرني يوم الخميس اطالب فوزي") as Command.Reminder; assertEquals(LocalDate.of(2026, 11, 5), c.at.toLocalDate()) }
    @Test fun c32_undo() { assertTrue(p("تراجع") is Command.Undo); assertTrue(p("الغ آخر عملية") is Command.Undo); assertTrue(p("امسح اخر عمليه") is Command.Undo) }
    @Test fun c33_total() { assertTrue(p("كم إجمالي الديون") is Command.TotalOwed) }
    @Test fun c34_payment_dafaa_noun() { debt<Command.AddPayment>("سجل دفعة لسعيد 3000", 2, 3000) }
    @Test fun c35_gulf_payment_jab() { debt<Command.AddPayment>("صالح جاب ٢٠٠٠ ريال يمني", 6, 2000) }
    @Test fun c36_million() { debt<Command.AddDebt>("سجل على عبدالله الحميري مليون ونص", 4, 1_500_000) }
    @Test fun c37_khamsmiya() { debt<Command.AddPayment>("ام خالد سددت خمسمية", 5, 500) }

    @Test fun c38_transfer() {
        val c = debt<Command.AddPayment>("محمد سدد 100 سعودي حوالة رقم 123456", 1, 100)
        assertEquals(Cur.SAR, c.amount!!.currency); assertEquals("transfer", c.method); assertEquals("123456", c.transferNo)
    }
    @Test fun c39_transfer_company() {
        val c = debt<Command.AddPayment>("فوزي دفع 20 ألف بحوالة الكريمي رقم 99887766", 3, 20000); assertEquals("99887766", c.transferNo); assertEquals("الكريمي", c.transferCo)
    }

    @Test fun numbers() {
        fun n(s: String) = ArabicNumbers.find(ArabicText.words(s)).first().value.toLong()
        assertEquals(5500L, n("خمسة آلاف وخمسمائة")); assertEquals(150L, n("مية وخمسين")); assertEquals(2500L, n("2.5 ألف"))
        assertEquals(25000L, n("خمسة وعشرين ألف")); assertEquals(300L, n("ثلاثمية")); assertEquals(5000L, n("5k"))
        assertTrue(ArabicNumbers.find(ArabicText.words("رقمه 777123456")).isEmpty())
    }
}
