package com.daftari.app

import androidx.test.core.app.ApplicationProvider
import com.daftari.app.audit.AuditDb
import com.daftari.app.audit.AuditLog
import com.daftari.app.audit.HeadAnchor
import com.daftari.app.backup.BackupData
import com.daftari.app.backup.DaftariFile
import com.daftari.app.backup.ReadResult
import com.daftari.app.chat.ChatMessage
import com.daftari.app.chat.Command
import com.daftari.app.chat.Customer
import com.daftari.app.chat.NameMatch
import com.daftari.app.chat.Nlu
import com.daftari.app.chat.OfflineAssistant
import com.daftari.app.core.Currency
import com.daftari.app.core.PayMethods
import com.daftari.app.core.PinThrottle
import com.daftari.app.core.Rates
import com.daftari.app.data.DaftariDb
import com.daftari.app.data.LedgerRepository
import com.daftari.app.data.Snap
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** 1.1.1: حوالة (3 companies + أخرى), «محفظة» wallets, optional numbers, receipts per payment, persistent PIN lockout, فصحى everywhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Features111Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val day = LocalDate.of(2026, 10, 30)
    private val book = listOf(Customer(1, "محمد علي الضبيبي", "777123456"), Customer(2, "سعيد المنصري"), Customer(3, "فوزي سعيد"))
    private fun p(t: String) = Nlu.parse(t, book, day, LocalDateTime.of(2026, 10, 30, 1, 0))
    private fun pay(t: String): Command.AddPayment = p(t).also { assertTrue("$t → $it", it is Command.AddPayment) } as Command.AddPayment

    // ------------------------------------------------------------ «مدين» parsing
    @Test fun walletWithNumber() {
        val c = pay("محمد سدد 5000 جوالي رقم 778899")
        assertEquals(1L, (c.who as NameMatch.Found).c.id); assertEquals(5000L, c.amount!!.value.toLong())
        assertEquals("wallet", c.method); assertEquals("جوالي", c.transferCo); assertEquals("778899", c.transferNo)
    }
    @Test fun walletTwoWordsNoNumber() {
        val c = pay("سدد عبر ون كاش")
        assertEquals("wallet", c.method); assertEquals("ون كاش", c.transferCo); assertEquals("", c.transferNo)
        assertEquals("", (c.who as NameMatch.NotFound).guess)
    }
    @Test fun walletEnglishName() { val c = pay("سعيد دفع 3000 one cash"); assertEquals("ون كاش", c.transferCo); assertEquals(2L, (c.who as NameMatch.Found).c.id) }
    @Test fun walletWithPrefixAndSenderPhone() {
        val c = pay("فوزي سدد عشرة آلاف بفلوسك رقم العملية 55443322 من 771234567")
        assertEquals(3L, (c.who as NameMatch.Found).c.id); assertEquals(10000L, c.amount!!.value.toLong())
        assertEquals("فلوسك", c.transferCo); assertEquals("55443322", c.transferNo); assertEquals("771234567", c.senderPhone)
    }
    @Test fun walletAfterAnTareeq() { val c = pay("محمد دفع ٢٠٠٠ عن طريق جيب"); assertEquals("جيب", c.transferCo); assertEquals(2000L, c.amount!!.value.toLong()); assertEquals(1L, (c.who as NameMatch.Found).c.id) }
    @Test fun walletNumberRightAfterName() { val c = pay("محمد سدد 7000 حاسب 9988776"); assertEquals("حاسب", c.transferCo); assertEquals("9988776", c.transferNo); assertEquals(7000L, c.amount!!.value.toLong()) }
    @Test fun walletAmountRightAfterNameIsTheAmount() { val c = pay("محمد سدد جوالي 5000"); assertEquals(5000L, c.amount!!.value.toLong()); assertEquals("", c.transferNo) }
    @Test fun walletWordOnly() { val c = pay("محمد سدد 4000 محفظة"); assertEquals("wallet", c.method); assertEquals("", c.transferCo) }
    @Test fun transferNewCompanies() {
        assertEquals("الحزمي", pay("محمد سدد 100 سعودي حوالة الحزمي رقم 445566").transferCo)
        assertEquals("النجم", pay("سعيد سدد 5000 بحوالة النجم").transferCo)
        val k = pay("فوزي دفع 20 ألف بحوالة الكريمي رقم 99887766"); assertEquals("الكريمي", k.transferCo); assertEquals("99887766", k.transferNo); assertEquals(20000L, k.amount!!.value.toLong())
    }
    @Test fun transferNumberOptional() { val c = pay("محمد سدد 3000 حوالة الكريمي"); assertEquals("transfer", c.method); assertEquals("", c.transferNo); assertEquals(3000L, c.amount!!.value.toLong()) }
    @Test fun cashStaysCash() { val c = pay("محمد سدد 2000"); assertEquals("", c.method); assertEquals("", c.transferCo) }

    // ------------------------------------------------------------ the confirm card
    private fun ctxX(): OfflineAssistant.Ctx {
        val ps = listOf(Party(id = 1, kind = PartyKind.CUSTOMER, name = "محمد الصنعاني", currency = "YER", startDate = day))
        val es = listOf(Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 2_000_000, date = day))
        val sums = ps.map { Ledger.summarize(it, es, day) }
        return OfflineAssistant.Ctx(LedgerRepository.buildDashboard(sums, es, day), es, Role.SHOP_OWNER, Currency.YER, Rates(), day)
    }
    @Test fun confirmCardCarriesTheWallet() {
        val m = OfflineAssistant.respond("محمد سدد 5000 جوالي رقم 778899", ctxX())!!
        val a = m.action!!
        assertEquals("add_payment", a.type); assertEquals("wallet", a.method); assertEquals("جوالي", a.note); assertEquals("778899", a.transferNo)
        assertTrue(a.lines.joinToString("\n"), a.lines.any { it.contains("محفظة") && it.contains("جوالي") && it.contains("778899") })
    }

    // ------------------------------------------------------------ text everywhere
    @Test fun describeAndStatementText() {
        val e = Entry(partyId = 1, type = EntryType.PAYMENT, amount = 500_000, date = day, note = "محفظة جوالي", method = "wallet", transferNo = "778899", transferCo = "جوالي", senderPhone = "771234567")
        assertEquals("محفظة جوالي رقم 778899 من 771234567", PayMethods.describePlain(e))
        assertEquals("محفظة جوالي رقم 778899 من 771234567", PayMethods.statementText(e))
        val t = e.copy(method = "transfer", transferCo = "الحزمي", transferNo = "", senderPhone = "", note = "")
        assertEquals("حوالة الحزمي", PayMethods.statementText(t))
        val st = Ledger.statement(Party(id = 1, kind = PartyKind.CUSTOMER, name = "م", startDate = day), listOf(e.copy(id = 9)), day, day)
        assertTrue(st.lines.single().description.contains("جوالي"))
    }
    @Test fun migrateOldRows() {
        assertEquals("transfer", PayMethods.migrate(Entry(partyId = 1, type = EntryType.PAYMENT, amount = 1, date = day, transferNo = "123")).method)
        val w = PayMethods.migrate(Entry(partyId = 1, type = EntryType.PAYMENT, amount = 1, date = day, method = "transfer", transferNo = "1", transferCo = "وان كاش"))
        assertEquals("wallet", w.method); assertEquals("ون كاش", w.transferCo)
        assertEquals("الكريمي", PayMethods.migrate(Entry(partyId = 1, type = EntryType.PAYMENT, amount = 1, date = day, method = "transfer", transferCo = "كريمي")).transferCo)
        assertEquals("العمقي", PayMethods.migrate(Entry(partyId = 1, type = EntryType.PAYMENT, amount = 1, date = day, method = "transfer", transferCo = "العمقي")).transferCo)
    }
    @Test fun importedNotesBecomeMethods() {
        val e = PayMethods.fromNote(Entry(partyId = 1, type = EntryType.PAYMENT, amount = 1, date = day, note = "سداد عبر جوالي رقم 112233"))
        assertEquals("wallet", e.method); assertEquals("جوالي", e.transferCo); assertEquals("112233", e.transferNo)
        assertEquals("", PayMethods.fromNote(Entry(partyId = 1, type = EntryType.PAYMENT, amount = 1, date = day, note = "نقدا")).method)
    }

    // ------------------------------------------------------------ .daftari 2.1
    private fun cust(name: String, id: Long) = Party(id = id, kind = PartyKind.CUSTOMER, name = name, currency = "YER", startDate = day)
    private fun sample() = BackupData(
        "book-1", "1.1.1", 1_793_300_000_000, "YER", "بقالة الأمل", "عبدالله", "SHOP_OWNER", listOf(cust("محمد", 1), cust("سالم", 2)),
        listOf(
            Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 500_000, date = day, items = "رز"),
            Entry(id = 2, partyId = 1, type = EntryType.PAYMENT, amount = 500_000, date = day, method = "wallet", transferNo = "778899", transferCo = "جوالي", senderPhone = "771234567"),
            Entry(id = 3, partyId = 2, type = EntryType.PAYMENT, amount = 100_000, date = day, method = "transfer", transferCo = "الحزمي"),
            Entry(id = 4, partyId = 2, type = EntryType.PAYMENT, amount = 50_000, date = day, method = "wallet", transferCo = "محفظة الأمل"),
        ),
        emptyList(), emptyList(),
    )
    @Test fun backupRoundTripKeepsWalletFields() {
        val bytes = DaftariFile.write(sample())
        val r = DaftariFile.read(bytes) as ReadResult.Ok
        assertEquals(2, r.manifest!!.schemaVersion); assertEquals(2, r.manifest!!.json.optInt("schemaMinor"))
        val w = r.data.entries.single { it.id == 2L }
        assertEquals("wallet", w.method); assertEquals("جوالي", w.transferCo); assertEquals("778899", w.transferNo); assertEquals("771234567", w.senderPhone)
        val t = r.data.entries.single { it.id == 3L }
        assertEquals("transfer", t.method); assertEquals("الحزمي", t.transferCo); assertEquals("", t.transferNo)
        assertEquals("محفظة الأمل", r.data.entries.single { it.id == 4L }.transferCo)
        // encrypted too
        val key = DaftariFile.deriveKey("سر123", iterations = 1000)
        val enc = DaftariFile.read(DaftariFile.write(sample(), key), "سر123") as ReadResult.Ok
        val ref = sample() // one instance: createdAt defaults to the clock, two calls can differ by 1 ms
        assertEquals(ref.entries, enc.data.entries.map { it.copy(createdAt = ref.entries.first { s -> s.id == it.id }.createdAt) })
    }
    @Test fun schema20FileIsMigrated() {
        val o = DaftariFile.dataJson(sample())
        o.put("schemaVersion", 2); o.remove("schemaMinor")
        val es = o.getJSONArray("entries")
        // a 1.1.0 file: wallet typed as a transfer company, a number without a method
        es.getJSONObject(1).put("method", "transfer").put("transferCompany", "جوالي").remove("senderPhone")
        es.getJSONObject(2).remove("method"); es.getJSONObject(2).put("transferNo", "4455")
        val r = DaftariFile.parseData(o, null) as ReadResult.Ok
        assertEquals("wallet", r.data.entries.single { it.id == 2L }.method)
        assertEquals("transfer", r.data.entries.single { it.id == 3L }.method)
    }
    @Test fun logSnapshotKeepsSenderPhone() {
        val e = sample().entries[1]
        assertEquals(e, Snap.entryFrom(JSONObject(Snap.entry(e).toString())))
    }

    // ------------------------------------------------------------ receipt per payment + duplicate number
    private class MemAnchor : HeadAnchor { var v: Pair<Long, String>? = null; override fun read() = v; override fun write(seq: Long, hash: String) { v = seq to hash } }
    @Test fun receiptAttachesToThePayment() = runBlocking {
        val repo = LedgerRepository(DaftariDb.create(ctx, inMemory = true), ctx, AuditLog(AuditDb.create(ctx, inMemory = true).dao(), MemAnchor()))
        val pid = repo.saveParty(cust("محمد", 0))
        val eid = repo.addEntry(Entry(partyId = pid, type = EntryType.PAYMENT, amount = 500_000, date = day, method = "wallet", transferNo = "778899", transferCo = "جوالي"))
        val img = File(ctx.cacheDir, "r.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        repo.attach(pid, android.net.Uri.fromFile(img), "إيصال", "image/jpeg", eid)
        val atts = repo.attachments(pid).first()
        assertEquals(eid, atts.single().entryId)
        assertEquals(eid, repo.entryByTransfer("778899")!!.id)
    }

    // ------------------------------------------------------------ PIN lockout survives reopening
    private class MapStore : PinThrottle.Store { val m = HashMap<String, Long>(); override fun getLong(key: String) = m[key] ?: 0; override fun putLong(key: String, value: Long) { m[key] = value } }
    @Test fun pinLockoutPersists() {
        var now = 1_000_000L
        val store = MapStore()
        val g1 = PinThrottle(store) { now }
        repeat(4) { assertTrue(g1.attempt { false } is PinThrottle.Result.Wrong) }
        assertTrue(g1.attempt { false } is PinThrottle.Result.Locked)
        // the lock screen is closed and opened again: a new throttle over the same stored state
        val g2 = PinThrottle(store) { now }
        assertTrue(g2.remainingMs() > 0)
        assertTrue("even the right PIN waits", g2.attempt { true } is PinThrottle.Result.Locked)
        now += PinThrottle.LOCK_MS - 1_000
        assertTrue(PinThrottle(store) { now }.attempt { true } is PinThrottle.Result.Locked)
        now += 2_000
        val g3 = PinThrottle(store) { now }
        assertEquals(0L, g3.remainingMs()); assertEquals(5, g3.triesLeft())
        assertTrue(g3.attempt { true } is PinThrottle.Result.Ok)
    }

    // ------------------------------------------------------------ فصحى only
    private val dialect = listOf(
        "وش", "ايش", "إيش", "الحين", "ذحين", "دحين", "يبغى", "تبغى", "أبغى", "ابغى", "نبغى", "تبي", "يبي", "عشان", "علشان", "اللي", "هذي", "مو", "مب", "مش",
        "شوي", "شوية", "زي", "بعدين", "وبعدين", "لين", "فاضي", "يشتغل", "تشتغل", "أشتغل", "اشتغل", "مين", "حقك", "حقه", "لقيت", "قدرت", "ينحذف", "انحذف",
        "ينحفظ", "تنحفظ", "تنرجع", "ينرجع", "زباين", "خلّينا", "خلينا", "يتحسب", "بيرجع", "بتحتاجها", "شي", "النت", "غلط", "أقدر", "تقدر", "كذا", "ليش", "وين",
    )
    private val wordRx = Regex("[\\u0621-\\u064A\\u0640\\u064B-\\u0652]+")
    private fun dialectIn(s: String): List<String> {
        val ws = wordRx.findAll(s).map { it.value.replace(Regex("[\\u064B-\\u0652\\u0640]"), "") }.toList()
        val out = ws.filter { it in dialect || (it.length > 3 && it[0] in "وفب" && it.substring(1) in dialect) }.toMutableList()
        Regex("(^|[^\\u0600-\\u06FF])ما (في|فيه|عندي|عندك)([^\\u0600-\\u06FF]|$)").find(s)?.let { out += it.value.trim() }
        return out
    }
    /** Phrases the app READS from the user or from other apps' files (dialect on purpose); never shown as the app's own text. */
    private val inputVocabulary = setOf("ما دفع", "مادفع", "زباين", "مين")

    @Test fun stringsXmlAndAllAppTextAreFusha() {
        val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        val found = ArrayList<String>()
        main.walkTopDown().filter { it.name == "strings.xml" }.forEach { f -> dialectIn(f.readText()).forEach { found += "${f.name}: $it" } }
        val lit = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        // Nlu.kt and voice/VoiceEntryParser.kt (1.1.6) hold the vocabulary the app READS from speech / typing, dialect on purpose
        // 1.1.8: chat/DialectLexicon.kt is that vocabulary too (the Yemeni sales words the parsers read), never shown as app text
        main.walkTopDown().filter { it.extension == "kt" && it.name !in setOf("Nlu.kt", "VoiceEntryParser.kt", "DialectLexicon.kt") }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                lit.findAll(line).map { it.groupValues[1] }.filter { it !in inputVocabulary }.forEach { s -> dialectIn(s).forEach { found += "${f.name}:${i + 1} «$it» in \"$s\"" } }
            }
        }
        assertTrue("dialect in app text:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun assistantRepliesAreFusha() {
        val x = ctxX()
        val asks = listOf("محمد سدد 5000 جوالي رقم 778899", "كم على محمد؟", "كشف حساب محمد", "مين أكثر واحد عليه؟", "ملخص اليوم", "كم الديون كلها",
            "ضيف زبون جديد اسمه ياسر 771234567", "ذكرني أطالب محمد بكرة الساعة 10", "تراجع", "سجل على خالد رز بـ500", "سدد", "ايش تسوي", "سجل على محمد 5000 سعودي",
            "محمد سدد 100 سعودي حوالة الكريمي رقم 1234")
        val bad = ArrayList<String>()
        for (q in asks) {
            val m: ChatMessage = OfflineAssistant.respond(q, x) ?: continue
            val texts = listOf(m.text) + m.action?.lines.orEmpty() + (m.table?.let { listOf(it.title) + it.columns + it.rows.flatten() } ?: emptyList())
            texts.forEach { t -> dialectIn(t).forEach { bad += "«$q» → «$it» in \"$t\"" } }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
