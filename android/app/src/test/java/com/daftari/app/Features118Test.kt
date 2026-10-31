/*
 * دفتري · test/Features118Test.kt
 * اختبارات 1.1.8: الاسم الكامل للزبون («محمد اليتيم» لا «محمد» + أغراض)، وعبارات مدين: «سجل عليه»، «سدد الي عليه كامل»،
 * «اخذ بـ500»، «طلع/نزل/اطبع/خرج لي كشف»، ذاكرة المحادثة، إعادة تجربة الصوت عبر الإنترنت بعد دقيقة، وتلميح التعرّف.
 * Pure JVM tests (no Android) for the offline parsers, the chat memory, the voice cooldown and the transcription hint.
 * [PARSES] is also the sentence → result table quoted in the 1.1.8 report.
 */
package com.daftari.app

import com.daftari.app.chat.ChatMemory
import com.daftari.app.chat.Command
import com.daftari.app.chat.Customer
import com.daftari.app.chat.NameMatch
import com.daftari.app.chat.Nlu
import com.daftari.app.chat.OfflineAssistant
import com.daftari.app.core.Currency
import com.daftari.app.core.Rates
import com.daftari.app.data.LedgerRepository
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Role
import com.daftari.app.voice.VoiceCooldown
import com.daftari.app.voice.VoiceCustomer
import com.daftari.app.voice.VoiceEngines
import com.daftari.app.voice.VoiceEntryParser
import com.daftari.app.voice.VoiceHint
import com.daftari.app.voice.VoiceKind
import com.daftari.app.voice.VoiceMode
import com.daftari.app.voice.VoiceVia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class Features118Test {
    companion object {
        val YATIM = Customer(1, "محمد اليتيم")
        val BOOK = listOf(YATIM, Customer(2, "محمد علي الضبيبي"), Customer(3, "سعيد المنصري"), Customer(4, "علي أحمد"), Customer(5, "عبدالله الحميري"))
        /** Madyan's book as it was on Oct 31 (no «محمد اليتيم» yet, only another «محمد»). */
        val BOOK_NO_YATIM = BOOK.drop(1)
        val today: LocalDate = LocalDate.of(2026, 10, 31)
        val now: LocalDateTime = LocalDateTime.of(2026, 10, 31, 14, 0)

        fun p(t: String, book: List<Customer> = BOOK, last: Customer? = null, pending: ChatMemory.Pending? = null) = Nlu.parse(t, book, today, now, last, pending)

        /** One line per sentence: what the offline parser made of it (for the report). */
        fun describe(c: Command): String {
            fun who(m: NameMatch?) = when (m) {
                is NameMatch.Found -> "«${m.c.name}»"
                is NameMatch.Ambiguous -> "أحد: " + m.options.joinToString("/") { it.name }
                is NameMatch.NotFound -> "زبون جديد «${m.guess}»" + (if (m.suggestions.isEmpty()) "" else " (أو: ${m.suggestions.joinToString("/") { it.name }})")
                null -> "؟ (يسأل: على من؟)"
            }
            return when (c) {
                is Command.AddDebt -> "دين ${c.amount?.value?.toPlainString() ?: "؟ (يسأل كم)"} على ${who(c.who)}" + (if (c.items.isNotBlank()) " · الأغراض «${c.items}»" else "")
                is Command.AddPayment -> "سداد ${if (c.full) "كامل الرصيد" else if (c.half) "نصف الرصيد" else c.amount?.value?.toPlainString() ?: "؟"} من ${who(c.who)}"
                is Command.Statement -> "كشف ${who(c.who)}" + if (c.pdf) " → PDF" else ""
                is Command.Balance -> "رصيد ${who(c.who)}"
                else -> c.toString()
            }
        }

        /** The report table: sentence, context, result. */
        val PARSES: List<Triple<String, Customer?, List<Customer>>> = listOf(
            Triple("أضف محمد اليتيم وسط العلي ألف ريال", null, BOOK),
            Triple("أضف محمد اليتيم وسط العلي ألف ريال", null, BOOK_NO_YATIM),
            Triple("أضف محمد اليتيم سجل عليه ألف ريال", null, BOOK_NO_YATIM),
            Triple("سجل على محمد اليتيم 500", null, BOOK),
            Triple("سجل عليه", YATIM, BOOK),
            Triple("سجل عليه 500", YATIM, BOOK),
            Triple("سجل عليه ٥٠٠", YATIM, BOOK),
            Triple("سجل عليه خمسمية", YATIM, BOOK),
            Triple("سجل عليه 500", null, BOOK),
            Triple("محمد اليتيم سدد الي عليه كامل", null, BOOK),
            Triple("محمد اليتيم سدد الي عليه كاملا", null, BOOK),
            Triple("محمد اليتيم سدد الي عليه كاملاً", null, BOOK),
            Triple("سدد الي عليه كاملا", YATIM, BOOK),
            Triple("محمد اليتيم اخذ بـ٥٠٠ رز وسكر", null, BOOK),
            Triple("اخذ ب 500 رز", YATIM, BOOK),
            Triple("اخذ بـ٥٠٠", YATIM, BOOK),
            Triple("محمد اليتيم اخذ بالفين", null, BOOK),
            Triple("سجل على محمد رز وسكر بـ5000", null, BOOK_NO_YATIM),
            Triple("سجل على محمد رز وسكر بـ5000", null, BOOK),
            Triple("طلع لي كشف", YATIM, BOOK),
            Triple("نزل لي كشف", YATIM, BOOK),
            Triple("اطبع لي كشف", YATIM, BOOK),
            Triple("خرج لي كشف", YATIM, BOOK),
            Triple("طلع لي كشف سعيد المنصري", YATIM, BOOK),
            Triple("اطبع لي كشف محمد اليتيم", null, BOOK),
            Triple("طلع لي كشف", null, BOOK),
            Triple("خلّيه عليه 300", YATIM, BOOK),
            Triple("شُكُك 300", YATIM, BOOK),
            Triple("سعيد المنصري شلّ بـ٥٠٠ سكر", null, BOOK),
            Triple("سعيد المنصري قضّى 1000", null, BOOK),
            Triple("محمد اليتيم صفّى حسابه", null, BOOK),
            Triple("محمد اليتيم دفع نصه", null, BOOK),
            Triple("هات لي كشف", YATIM, BOOK),
            Triple("طلع لي حسابه", YATIM, BOOK),
            Triple("كم باقي عليه؟", YATIM, BOOK),
            Triple("سجل على سعيد المنصري خمس تلاف ر.ي", null, BOOK),
        )
    }

    private fun found(c: NameMatch?): Customer = (c as? NameMatch.Found)?.c ?: error("not found: $c")

    // ---------------------------------------------------------------- 1. the whole name, never name + «items»
    @Test fun exactFailingSentenceTakesTheWholeName() {
        val c = p("أضف محمد اليتيم وسط العلي ألف ريال") as Command.AddDebt
        assertEquals(1L, found(c.who).id); assertEquals(1000L, c.amount!!.value.toLong()); assertEquals("", c.items)
    }

    @Test fun unknownLongNameIsANewCustomerNotASplit() {
        val c = p("أضف محمد اليتيم وسط العلي ألف ريال", BOOK_NO_YATIM) as Command.AddDebt
        val nf = c.who as NameMatch.NotFound
        assertEquals("محمد اليتيم وسط العلي", nf.guess); assertEquals("", c.items)
        assertEquals(listOf(2L), nf.suggestions.map { it.id }) // «محمد علي الضبيبي» offered, never assumed
        val d = p("أضف محمد اليتيم سجل عليه ألف ريال", BOOK_NO_YATIM) as Command.AddDebt
        assertEquals("محمد اليتيم", (d.who as NameMatch.NotFound).guess); assertEquals(1000L, d.amount!!.value.toLong()); assertEquals("", d.items)
        // only a single-word «محمد» in the book: still «محمد اليتيم» as new, «محمد» suggested
        val e = p("سجل على محمد اليتيم 300", listOf(Customer(9, "محمد"))) as Command.AddDebt
        assertEquals("محمد اليتيم", (e.who as NameMatch.NotFound).guess); assertEquals(listOf(9L), (e.who as NameMatch.NotFound).suggestions.map { it.id })
    }

    @Test fun normalisedLongestMatchPrefersExistingCustomer() {
        // ة/ه، ى/ي، أ/إ/آ/ا، «ال»
        val book = listOf(Customer(1, "محمد اليتيم"), Customer(2, "محمد"), Customer(3, "فاطمة الأحمدي"), Customer(4, "مصطفى العزي"))
        assertEquals(1L, found((p("سجل على محمد اليتيم 500", book) as Command.AddDebt).who).id)
        assertEquals(1L, found((p("سجل على محمد يتيم 500", book) as Command.AddDebt).who).id)
        assertEquals(3L, found((p("سجل على فاطمه الاحمدي 200", book) as Command.AddDebt).who).id)
        assertEquals(4L, found((p("سجل على مصطفي العزي 200", book) as Command.AddDebt).who).id)
        assertEquals(2L, found((p("سجل على محمد 200", book) as Command.AddDebt).who).id)
    }

    @Test fun itemsOnlyWithBaOrAfterTheAmount() {
        val a = p("سجل على محمد رز وسكر بـ5000", BOOK_NO_YATIM) as Command.AddDebt
        assertEquals(2L, found(a.who).id); assertEquals("رز وسكر", a.items)
        // two «محمد» in the book: «أيّهم تقصد؟», the items still «رز وسكر»
        val two = p("سجل على محمد رز وسكر بـ5000") as Command.AddDebt
        assertEquals(listOf(1L, 2L), (two.who as NameMatch.Ambiguous).options.map { it.id }.sorted()); assertEquals("رز وسكر", two.items)
        val b = p("سجل على محمد اليتيم رز بـ500") as Command.AddDebt
        assertEquals(1L, found(b.who).id); assertEquals("رز", b.items)
        val c = p("سجل على محمد اليتيم 700 شاهي") as Command.AddDebt
        assertEquals(1L, found(c.who).id); assertEquals("شاهي", c.items)
    }

    // ---------------------------------------------------------------- 2. «سجل عليه» + chat memory
    @Test fun sajjilAlayhUsesTheLastCustomerOfTheChat() {
        val mem = ChatMemory()
        val first = p("كم على محمد اليتيم؟")
        assertTrue(first is Command.Balance)
        mem.note(first)
        assertEquals(1L, mem.last(BOOK)?.id)
        val ask = p("سجل عليه", last = mem.last(BOOK)) as Command.AddDebt
        assertEquals(1L, found(ask.who).id); assertNull(ask.amount)
        for (t in listOf("سجل عليه 500", "سجل عليه ٥٠٠", "سجل عليه خمسمية", "سجّل عليه 500 ريال")) {
            val c = p(t, last = mem.last(BOOK)) as Command.AddDebt
            assertEquals(t, 1L, found(c.who).id); assertEquals(t, 500L, c.amount!!.value.toLong())
        }
        // nobody mentioned yet → the assistant asks «على من؟»
        assertNull((p("سجل عليه 500") as Command.AddDebt).who)
        assertNull((p("سجل عليه") as Command.AddDebt).who)
        // a name in the sentence beats the memory
        assertEquals(3L, found((p("سجل على سعيد المنصري 500", last = YATIM) as Command.AddDebt).who).id)
    }

    @Test fun bareAmountAnswersKamAlMablagh() {
        val mem = ChatMemory()
        mem.ask(1, payment = false)
        val c = p("500", pending = mem.pending) as Command.AddDebt
        assertEquals(1L, found(c.who).id); assertEquals(500L, c.amount!!.value.toLong())
        mem.answered(); assertNull(mem.pending); assertEquals(1L, mem.lastId)
        mem.clear(); assertEquals(0L, mem.lastId)
    }

    @Test fun chatMemoryFollowsTheConversation() {
        val x = ctx()
        val r1 = OfflineAssistant.respond("كم على محمد اليتيم؟", x)!!
        assertEquals(1L, r1.about)
        val mem = ChatMemory(); mem.note(r1.about)
        val r2 = OfflineAssistant.respond("سجل عليه 500", x.copy(last = mem.last(OfflineAssistant.customers(x.d))))!!
        assertEquals("add_debt", r2.action!!.type); assertEquals(1L, r2.action!!.partyId); assertEquals(50_000L, r2.action!!.minor)
        val r3 = OfflineAssistant.respond("سجل عليه", x.copy(last = mem.last(OfflineAssistant.customers(x.d))))!!
        assertNull(r3.action); assertEquals(false, r3.askAmountPayment); assertTrue(r3.text.contains("محمد اليتيم"))
        val r4 = OfflineAssistant.respond("سجل عليه 500", x)!!
        assertNull(r4.action); assertTrue(r4.text.startsWith("على من"))
    }

    // ---------------------------------------------------------------- 3. «سدد الي عليه كامل» = his whole balance
    @Test fun fullPaymentPhrases() {
        for (t in listOf("محمد اليتيم سدد الي عليه كامل", "محمد اليتيم سدد الي عليه كاملا", "محمد اليتيم سدد الي عليه كاملاً", "محمد اليتيم سدد اللي عليه كامل")) {
            val c = p(t) as Command.AddPayment
            assertEquals(t, 1L, found(c.who).id); assertTrue(t, c.full); assertNull(t, c.amount)
        }
        val m = p("سدد الي عليه كاملا", last = YATIM) as Command.AddPayment
        assertEquals(1L, found(m.who).id); assertTrue(m.full)
        // a stated amount is never replaced
        val s = p("محمد اليتيم سدد 2000") as Command.AddPayment
        assertFalse(s.full); assertEquals(2000L, s.amount!!.value.toLong())
    }

    @Test fun fullPaymentCardUsesTheBalance() {
        val x = ctx()
        val r = OfflineAssistant.respond("محمد اليتيم سدد الي عليه كاملاً", x)!!
        val a = r.action!!
        assertEquals("add_payment", a.type); assertEquals(1L, a.partyId); assertEquals(1_250_000L, a.minor) // 12,500
        assertTrue(a.lines.any { it.contains("كامل") })
        val zero = OfflineAssistant.respond("سعيد المنصري سدد الي عليه كامل", x)!!
        assertNull(zero.action); assertTrue(zero.text.contains("لا يوجد"))
    }

    // ---------------------------------------------------------------- 4. «اخذ بـ500»
    @Test fun akhadhBiAmount() {
        val a = p("محمد اليتيم اخذ بـ٥٠٠ رز وسكر") as Command.AddDebt
        assertEquals(1L, found(a.who).id); assertEquals(500L, a.amount!!.value.toLong()); assertEquals("رز وسكر", a.items)
        val b = p("اخذ ب 500 رز", last = YATIM) as Command.AddDebt
        assertEquals(1L, found(b.who).id); assertEquals(500L, b.amount!!.value.toLong()); assertEquals("رز", b.items)
        val c = p("اخذ بـ٥٠٠", last = YATIM) as Command.AddDebt
        assertEquals(1L, found(c.who).id); assertEquals(500L, c.amount!!.value.toLong()); assertEquals("", c.items)
        val d = p("محمد اليتيم اخذ بالفين") as Command.AddDebt
        assertEquals(2000L, d.amount!!.value.toLong())
        val e = p("سعيد المنصري اخذ ب ٥٠٠ ......") as Command.AddDebt
        assertEquals(3L, found(e.who).id); assertEquals(500L, e.amount!!.value.toLong())
    }

    // ---------------------------------------------------------------- 5. «طلع / نزل / اطبع / خرج لي كشف»
    @Test fun kashfVerbsOpenThePdf() {
        for (v in listOf("طلع", "نزل", "اطبع", "خرج")) {
            val c = p("$v لي كشف", last = YATIM) as Command.Statement
            assertEquals(v, 1L, found(c.who).id); assertTrue(v, c.pdf)
            val n = p("$v لي كشف سعيد المنصري", last = YATIM) as Command.Statement
            assertEquals(v, 3L, found(n.who).id); assertTrue(n.pdf)
        }
        assertEquals(1L, found((p("اطبع لي كشف محمد اليتيم") as Command.Statement).who).id)
        assertNull((p("طلع لي كشف") as Command.Statement).who) // nobody yet → «كشف من؟»
        assertTrue((p("طلع لي كشف ياسر", last = YATIM) as Command.Statement).who is NameMatch.NotFound)
        assertFalse((p("كشف حساب سعيد المنصري") as Command.Statement).pdf)
        val r = OfflineAssistant.respond("طلع لي كشف", ctx().copy(last = YATIM))!!
        assertEquals(1L, r.openStatement); assertTrue(r.table != null)
    }

    // ---------------------------------------------------------------- the same phrases through the voice-entry parser (home mic)
    @Test fun voiceEntryParserPhrases() {
        val book = BOOK.map { VoiceCustomer(it.id, it.name) }
        val last = VoiceCustomer(1, "محمد اليتيم")
        val a = VoiceEntryParser.parse("أضف محمد اليتيم وسط العلي ألف ريال", book)
        assertEquals(1L, a.customer?.id); assertTrue(a.customerSure); assertEquals("1000", a.amountText)
        val b = VoiceEntryParser.parse("سجل عليه 500", book, last)
        assertEquals(VoiceKind.DEBT, b.kind); assertEquals(1L, b.customer?.id); assertTrue(b.fromContext); assertEquals("500", b.amountText)
        val c = VoiceEntryParser.parse("محمد اليتيم سدد الي عليه كاملاً", book)
        assertEquals(VoiceKind.PAYMENT, c.kind); assertEquals(1L, c.customer?.id); assertTrue(c.fullBalance); assertEquals("", c.amountText)
        val d = VoiceEntryParser.parse("اخذ ب ٥٠٠ رز", book, last)
        assertEquals(VoiceKind.DEBT, d.kind); assertEquals("500", d.amountText); assertEquals("رز", d.note); assertEquals(1L, d.customer?.id)
        for (v in listOf("طلع", "نزل", "اطبع", "خرج")) {
            val k = VoiceEntryParser.parse("$v لي كشف", book, last)
            assertTrue(v, k.statement); assertEquals(v, 1L, k.customer?.id)
            val n = VoiceEntryParser.parse("$v لي كشف سعيد المنصري", book, last)
            assertTrue(v, n.statement); assertEquals(v, 3L, n.customer?.id)
        }
        // no memory and no name: nothing is guessed
        assertNull(VoiceEntryParser.parse("سجل عليه 500", book).customer)
    }

    // ---------------------------------------------------------------- 3 (voice): online again after a minute / when the server says ready
    @Test fun onlineVoiceIsRetriedAfterACooldown() {
        val off = 1_000_000L
        assertEquals(false, VoiceCooldown.state(false, off, off + 10_000))
        assertEquals(listOf(VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.AUTO, true, true, VoiceCooldown.state(false, off, off + 10_000), true))
        assertNull(VoiceCooldown.state(false, off, off + VoiceCooldown.RETRY_MS))
        assertEquals(listOf(VoiceVia.ONLINE, VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.AUTO, true, true, VoiceCooldown.state(false, off, off + 61_000), true))
        assertEquals(true, VoiceCooldown.state(true, 0, off)); assertNull(VoiceCooldown.state(null, 0, off))
        assertNull(VoiceCooldown.state(false, off, off - 5)) // clock went back (reboot): try again
    }

    // ---------------------------------------------------------------- 4 (voice): the transcription hint
    @Test fun transcriptionHint() {
        val names = (1..80).map { "زبون رقم $it" }
        val on = VoiceHint.online(listOf("محمد اليتيم") + names)
        assertTrue(on.length <= VoiceHint.ONLINE_MAX_CHARS)
        assertTrue(on.startsWith("سجل عليه"))
        listOf("سدد الي عليه كامل", "طلع لي كشف", "نزل لي كشف", "اطبع لي كشف", "خرج لي كشف", "اخذ بـ").forEach { assertTrue(it, on.contains(it)) }
        assertTrue(on.contains("محمد اليتيم"))
        val off = VoiceHint.offline(listOf("محمد اليتيم") + names)
        assertTrue(off.length <= VoiceHint.OFFLINE_MAX_CHARS); assertTrue(off.contains("سجل عليه"))
        assertFalse(VoiceHint.build(emptyList(), 400).contains("الزبائن"))
        // most recently active first
        assertEquals(listOf("ب", "ج", "أ"), VoiceHint.byRecent(mapOf(1L to "أ", 2L to "ب", 3L to "ج"), mapOf(2L to 30L, 3L to 20L)))
    }

    // ---------------------------------------------------------------- Yemeni sales dialect (DialectLexicon), a sample of every group
    @Test fun dialectDebt() {
        for (t in listOf("قيّد عليه 300", "اكتب عليه 300", "حطّه عليه 300", "خلّيه عليه 300", "شُكُك 300", "على الحساب 300", "بالدين 300", "آجل 300")) {
            val c = p(t, last = YATIM); assertTrue("$t → $c", c is Command.AddDebt)
            assertEquals(t, 1L, found((c as Command.AddDebt).who).id); assertEquals(t, 300L, c.amount!!.value.toLong())
        }
        for (t in listOf("سعيد المنصري خذا بـ٥٠٠ سكر", "سعيد المنصري شلّ بـ٥٠٠ سكر", "سعيد المنصري اشترى بخمسمية سكر")) {
            val c = p(t) as Command.AddDebt
            assertEquals(t, 3L, found(c.who).id); assertEquals(t, 500L, c.amount!!.value.toLong()); assertEquals(t, "سكر", c.items)
        }
        // «ر.ي» and «خمس تلاف»
        val r = p("سجل على سعيد المنصري خمس تلاف ر.ي") as Command.AddDebt
        assertEquals(5000L, r.amount!!.value.toLong()); assertEquals(com.daftari.app.chat.Cur.YER, r.amount!!.currency)
        assertEquals(500L, (p("سجل عليه نص ألف", last = YATIM) as Command.AddDebt).amount!!.value.toLong())
        // «على الحساب» is not the customer «علي أحمد»; «على علي أحمد» is
        assertEquals(4L, found((p("سجل على علي أحمد 300") as Command.AddDebt).who).id)
    }

    @Test fun dialectPayment() {
        for (t in listOf("سعيد المنصري قضّى 1000", "سعيد المنصري ورّد 1000", "سعيد المنصري سلّم 1000", "سعيد المنصري رجّع 1000", "سعيد المنصري خلّص 1000")) {
            val c = p(t); assertTrue("$t → $c", c is Command.AddPayment)
            assertEquals(t, 3L, found((c as Command.AddPayment).who).id); assertEquals(t, 1000L, c.amount!!.value.toLong())
        }
        for (t in listOf("محمد اليتيم صفّى حسابه", "محمد اليتيم سدد حقه كامل", "محمد اليتيم سدد الكل", "محمد اليتيم قضى اللي عليه كله")) {
            val c = p(t) as Command.AddPayment
            assertEquals(t, 1L, found(c.who).id); assertTrue(t, c.full)
        }
        val h = p("محمد اليتيم دفع نصه") as Command.AddPayment
        assertTrue(h.half); assertFalse(h.full); assertNull(h.amount)
        val card = OfflineAssistant.respond("محمد اليتيم دفع نصه", ctx())!!.action!!
        assertEquals(625_000L, card.minor) // half of 12,500
    }

    @Test fun dialectStatementAndBalance() {
        for (t in listOf("هات لي كشف", "ورّني الكشف", "عطني كشف", "طلع لي حسابه", "طلّع لي فاتورته", "نزّل لي كشف حساب")) {
            val c = p(t, last = YATIM); assertTrue("$t → $c", c is Command.Statement)
            assertEquals(t, 1L, found((c as Command.Statement).who).id)
        }
        assertEquals(3L, found((p("هات كشف سعيد المنصري", last = YATIM) as Command.Statement).who).id)
        for (t in listOf("كم عليه؟", "كم باقي عليه؟", "حسابه كم؟")) {
            val c = p(t, last = YATIM); assertTrue("$t → $c", c is Command.Balance)
            assertEquals(t, 1L, found((c as Command.Balance).who).id)
        }
        assertNull((p("كم عليه؟") as Command.Balance).who) // nobody yet → «من تقصد؟»
        assertTrue(p("مين أكثر واحد عليه؟", last = YATIM) is Command.TopDebtors)
    }

    @Test fun dialectThroughTheMic() {
        val book = BOOK.map { VoiceCustomer(it.id, it.name) }
        val last = VoiceCustomer(1, "محمد اليتيم")
        assertEquals(VoiceKind.DEBT, VoiceEntryParser.parse("خلّيه عليه 300", book, last).kind)
        assertEquals(VoiceKind.DEBT, VoiceEntryParser.parse("شُكُك 300", book, last).kind)
        assertEquals(VoiceKind.PAYMENT, VoiceEntryParser.parse("سعيد المنصري قضّى ألف", book).kind)
        assertTrue(VoiceEntryParser.parse("محمد اليتيم صفّى حسابه", book).fullBalance)
        assertTrue(VoiceEntryParser.parse("هات لي كشف", book, last).statement)
        // every row of the lexicon is understood by its intent's word set (no typo in the table)
        com.daftari.app.chat.DialectLexicon.ROWS.forEach { r -> r.forms.forEach { f -> assertTrue("${r.intent} $f", com.daftari.app.chat.DialectLexicon.says(r.intent, com.daftari.app.chat.ArabicText.words(f))) } }
    }

    @Test fun versionIs118() { assertEquals(11, BuildConfig.VERSION_CODE); assertEquals("1.1.8", BuildConfig.VERSION_NAME) }

    @Test fun parseTableForTheReport() {
        val out = PARSES.joinToString("\n") { (t, last, book) -> "«$t»" + (if (last != null) " [آخر زبون: ${last.name}]" else "") + (if (book === BOOK_NO_YATIM) " [الدفتر بدون محمد اليتيم]" else "") + " → " + describe(p(t, book, last)) }
        println("PARSE-TABLE-BEGIN\n$out\nPARSE-TABLE-END")
        java.io.File("build/parse-table-1.1.8.txt").apply { parentFile?.mkdirs() }.writeText(out)
        assertEquals(PARSES.size, out.lines().size)
    }

    // a small book with balances: محمد اليتيم owes 12,500; سعيد المنصري owes nothing
    private fun ctx(): OfflineAssistant.Ctx {
        val day = today
        val ps = BOOK.map { Party(id = it.id, kind = PartyKind.CUSTOMER, name = it.name, currency = "YER", startDate = day) }
        val es = listOf(Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 1_250_000, date = day))
        val sums = ps.map { Ledger.summarize(it, es.filter { e -> e.partyId == it.id }, day) }
        return OfflineAssistant.Ctx(LedgerRepository.buildDashboard(sums, es, day), es, Role.SHOP_OWNER, Currency.YER, Rates(), day)
    }
}
