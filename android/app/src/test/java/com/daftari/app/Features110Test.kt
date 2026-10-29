package com.daftari.app

import androidx.test.core.app.ApplicationProvider
import com.daftari.app.audit.Action
import com.daftari.app.audit.AuditDb
import com.daftari.app.audit.AuditLog
import com.daftari.app.audit.HeadAnchor
import com.daftari.app.backup.BackupData
import com.daftari.app.backup.DaftariFile
import com.daftari.app.backup.ReadResult
import com.daftari.app.backup.Reminder
import com.daftari.app.core.Rates
import com.daftari.app.data.DaftariDb
import com.daftari.app.data.LedgerRepository
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.importer.Layout
import com.daftari.app.importer.StatementImporter
import com.daftari.app.importer.XlsReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Features110Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val day = LocalDate.of(2026, 10, 29)

    private class MemAnchor : HeadAnchor { var v: Pair<Long, String>? = null; override fun read() = v; override fun write(seq: Long, hash: String) { v = seq to hash } }

    private fun setup(): Triple<LedgerRepository, AuditLog, AuditDb> {
        val adb = AuditDb.create(ctx, inMemory = true)
        val log = AuditLog(adb.dao(), MemAnchor())
        val repo = LedgerRepository(DaftariDb.create(ctx, inMemory = true), ctx, log)
        return Triple(repo, log, adb)
    }

    private fun cust(name: String, cur: String = "YER") = Party(kind = PartyKind.CUSTOMER, name = name, currency = cur, startDate = day)

    // ------------------------------------------------------------ «السجل»
    @Test fun logChainsEveryWrite() = runBlocking {
        val (repo, log, _) = setup()
        val id = repo.saveParty(cust("محمد"))
        repo.addCharge(id, 500_000, day, "رز")
        repo.addPayment(id, 200_000, day, "")
        val all = log.all()
        assertEquals(3, all.size)
        assertEquals(AuditLog.GENESIS, all[0].prevHash)
        assertEquals(all[0].hash, all[1].prevHash)
        assertTrue(log.verify().ok)
    }

    @Test fun logDetectsTamperAndDeletion() {
        val rows = runBlocking { val (repo, log, _) = setup(); val id = repo.saveParty(cust("علي")); repo.addCharge(id, 1000, day, ""); repo.addCharge(id, 2000, day, ""); log.all() }
        assertTrue(AuditLog.verifyChain(rows, rows.last().seq to rows.last().hash).ok)
        val edited = rows.toMutableList().also { it[1] = it[1].copy(title = "مزوّر") }
        assertFalse(AuditLog.verifyChain(edited, null).ok)
        val cut = rows.filterIndexed { i, _ -> i != 1 }
        assertFalse(AuditLog.verifyChain(cut, null).ok)
        // the newest row dropped: the anchor catches it
        assertFalse(AuditLog.verifyChain(rows.dropLast(1), rows.last().seq to rows.last().hash).ok)
    }

    @Test fun logTableRefusesUpdateAndDelete() {
        val (repo, _, adb) = setup()
        runBlocking { repo.saveParty(cust("سالم")) }
        val w = adb.openHelper.writableDatabase
        assertTrue(runCatching { w.execSQL("DELETE FROM audit_log") }.isFailure)
        assertTrue(runCatching { w.execSQL("UPDATE audit_log SET title='x'") }.isFailure)
        assertEquals(1L, runBlocking { adb.dao().count() })
    }

    @Test fun deletedCustomerRestoresWithItsEntries() = runBlocking {
        val (repo, log, _) = setup()
        val id = repo.saveParty(cust("أحمد"))
        repo.addCharge(id, 300_000, day, "سكر"); repo.addPayment(id, 100_000, day, "")
        repo.deleteParty(id)
        assertTrue(repo.allParties().none { it.name == "أحمد" })
        val del = log.all().last { it.action == Action.DELETE }
        assertTrue(del.restorable)
        val r = repo.restoreFromLog(del)
        assertTrue(r is LedgerRepository.RestoreResult.Done)
        val p = repo.allParties().single { it.name == "أحمد" }
        assertEquals(200_000L, Ledger.balance(p, repo.allEntries().filter { it.partyId == p.id }, day))
        assertEquals(Action.RESTORE, log.all().last().action)
        assertTrue(log.verify().ok)
    }

    @Test fun editedEntryRestoresOldValues() = runBlocking {
        val (repo, log, _) = setup()
        val id = repo.saveParty(cust("خالد"))
        val eid = repo.addCharge(id, 100_000, day, "قديم")
        repo.updateEntry(repo.findEntry(eid)!!.copy(amount = 999_900, note = "جديد"))
        repo.restoreFromLog(log.all().last { it.action == Action.UPDATE })
        val e = repo.findEntry(eid)!!
        assertEquals(100_000L, e.amount); assertEquals("قديم", e.note)
    }

    @Test fun logSurvivesWipeAndWipeIsRestorable() = runBlocking {
        val (repo, log, _) = setup()
        val id = repo.saveParty(cust("ياسر")); repo.addCharge(id, 50_000, day, "")
        val before = log.count()
        repo.wipe()
        assertTrue(repo.allParties().isEmpty())
        assertEquals(before + 1, log.count())
        repo.restoreFromLog(log.all().last { it.action == Action.WIPE })
        assertEquals(1, repo.allParties().size)
        assertEquals(1, repo.allEntries().size)
    }

    @Test fun undoRemovesTheLastAdd() = runBlocking {
        val (repo, _, _) = setup()
        val id = repo.saveParty(cust("فهد")); repo.addCharge(id, 70_000, day, "")
        assertTrue(repo.undoLast() is LedgerRepository.RestoreResult.Done)
        assertTrue(repo.allEntries().isEmpty())
        assertEquals(1, repo.allParties().size)
    }

    // ------------------------------------------------------------ .daftari
    private fun sample(log: List<com.daftari.app.audit.AuditRecord> = emptyList()) = BackupData(
        "book-1", "1.1.0", 1_793_300_000_000, "YER", "بقالة الأمل", "عبدالله", "SHOP_OWNER",
        listOf(cust("محمد").copy(id = 1, phone = "777123456"), cust("سالم", "SAR").copy(id = 2)),
        listOf(
            Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 500_000, date = day, items = "رز"),
            Entry(id = 2, partyId = 1, type = EntryType.PAYMENT, amount = 1_395_000, date = day, currency = "SAR", origAmount = 10_000, rate = "139.5", method = "transfer", transferNo = "123456", transferCo = "الكريمي"),
            Entry(id = 3, partyId = 2, type = EntryType.CHARGE, amount = 20_000, date = day),
        ),
        listOf(Reminder(1, 1, 1_793_400_000_000, "طالب محمد")), log,
    )

    private fun entries(zip: ByteArray): Map<String, ByteArray> = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.associate { it.name to z.readBytes() }
    }

    @Test fun daftariRoundTripKeepsEveryField() {
        val bytes = DaftariFile.write(sample())
        assertTrue(DaftariFile.isZip(bytes))
        assertEquals(setOf("manifest.json", "data.json"), entries(bytes).keys)
        val r = DaftariFile.read(bytes) as ReadResult.Ok
        assertEquals(2, r.data.parties.size); assertEquals(3, r.data.entries.size); assertEquals(1, r.data.reminders.size)
        val pay = r.data.entries.single { it.id == 2L }
        assertEquals("SAR", pay.currency); assertEquals(10_000L, pay.origAmount); assertEquals("139.5", pay.rate)
        assertEquals("transfer", pay.method); assertEquals("123456", pay.transferNo); assertEquals("الكريمي", pay.transferCo)
        assertEquals("SAR", r.data.parties.single { it.id == 2L }.currency)
        assertEquals(3, r.manifest!!.entries)
    }

    @Test fun daftariEncryptedNeedsThePassword() {
        val key = DaftariFile.deriveKey("سر123", iterations = 1000)
        val bytes = DaftariFile.write(sample(), key)
        assertTrue("data.json.enc" in entries(bytes).keys)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("777123456"))
        assertTrue(DaftariFile.read(bytes) is ReadResult.NeedsPassword)
        assertTrue(DaftariFile.read(bytes, "غلط") is ReadResult.Bad)
        assertEquals(3, (DaftariFile.read(bytes, "سر123") as ReadResult.Ok).data.entries.size)
    }

    @Test fun daftariRejectsATamperedFile() {
        val files = entries(DaftariFile.write(sample())).toMutableMap()
        assertTrue(String(files["data.json"]!!, Charsets.UTF_8).contains("777123456"))
        files["data.json"] = String(files["data.json"]!!, Charsets.UTF_8).replace("777123456", "777000000").toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        assertTrue(DaftariFile.read(out.toByteArray()) is ReadResult.Bad)
    }

    @Test fun legacyJsonBackupStillOpens() {
        val json = com.daftari.app.data.Backup.toJson(listOf(cust("قديم").copy(id = 5)), listOf(Entry(id = 9, partyId = 5, type = EntryType.CHARGE, amount = 1234, date = day)), "ب", "م", "SHOP_OWNER")
        val r = DaftariFile.read(json.toByteArray()) as ReadResult.Ok
        assertEquals(1, r.data.migratedFrom); assertEquals(1, r.data.parties.size); assertEquals(1234L, r.data.entries.single().amount)
    }

    @Test fun fileNameFormat() {
        val n = DaftariFile.fileName(java.time.ZonedDateTime.of(2026, 10, 29, 9, 5, 0, 0, java.time.ZoneId.of("Asia/Aden")).toInstant().toEpochMilli(), java.time.ZoneId.of("Asia/Aden"))
        assertEquals("Daftari-2026-10-29-0905.daftari", n)
    }

    @Test fun mergingTheSameBackupTwiceNeverDuplicates() = runBlocking {
        val (repo, log, _) = setup()
        val b = sample()
        repo.mergeBackup(b, sameBook = false, title = "x")
        val n1 = repo.allEntries().size
        val s2 = repo.mergeBackup(b, sameBook = false, title = "x")
        assertEquals(n1, repo.allEntries().size)
        assertEquals(2, repo.allParties().size)
        assertEquals(3, s2.entriesSkipped)
        assertTrue(log.verify().ok)
    }

    @Test fun backupCarriesTheLogAndAdoptsItOnAFreshInstall() = runBlocking {
        val (repo, log, _) = setup()
        val id = repo.saveParty(cust("محمد")); repo.addCharge(id, 1000, day, "")
        val data = sample(log.all()).copy(parties = repo.allParties(), entries = repo.allEntries())
        val bytes = DaftariFile.write(data)
        val back = DaftariFile.read(bytes) as ReadResult.Ok
        assertEquals(2, back.data.log.size)
        val (fresh, freshLog, _) = setup()
        val st = fresh.mergeBackup(back.data, sameBook = true, title = "استعادة")
        assertTrue(st.logAdopted)
        assertTrue(freshLog.count() >= 2)
        assertTrue(freshLog.verify().ok)
    }

    // ------------------------------------------------------------ demo .xls (monthly pivot, made-up names)
    private fun fixture(n: String) = javaClass.classLoader!!.getResourceAsStream("fixtures/$n")!!.readBytes()

    @Test fun xlsSampleMonthlyPivot() {
        val bytes = fixture("import_sample_monthly_demo.xls")
        val sheets = XlsReader.read(bytes)
        assertTrue(sheets.any { it.name.contains("تفصيلي") })
        val t = StatementImporter.read("import_sample_monthly_demo.xls", bytes).first()
        val m = StatementImporter.guess(t)
        assertEquals(Layout.MONTHLY, m.layout)
        val plan = StatementImporter.build(t, m, emptyList(), emptyList(), day)
        val byName = plan.customers.associate { it.name.trim() to it.net }
        assertEquals(350_000L, byName["أحمد سالم"])
        assertEquals(1_275_000L, byName["خالد ناصر"])
        assertEquals(125_000L, byName["سعيد علي"])
        assertEquals(3, plan.customers.size)
        assertEquals(1_750_000L, plan.fileTotal)
        assertEquals(true, plan.totalMatches)
        assertTrue(plan.txns.all { it.date.monthValue == 10 })
    }

    @Test fun csvTransactionsDedupe() {
        val csv = "التاريخ,الاسم,المبلغ,النوع\n2026-10-01,محمد,5000,دين\n2026-10-01,محمد,5000,دين\n2026-10-02,علي,2000,سداد\n".toByteArray()
        val t = StatementImporter.read("x.csv", csv).first()
        val plan = StatementImporter.build(t, StatementImporter.guess(t), emptyList(), emptyList(), day)
        assertEquals(3, plan.txns.size); assertEquals(2, plan.toImport.size)
        assertEquals(EntryType.PAYMENT, plan.toImport.single { it.name == "علي" }.type)
    }

    @Test fun balancesLayoutSkipsTotalRow() {
        val csv = "الاسم,الرصيد\nمحمد,1500\nسالم,500\nالإجمالي,2000\n".toByteArray()
        val t = StatementImporter.read("b.csv", csv).first()
        val plan = StatementImporter.build(t, StatementImporter.guess(t), emptyList(), emptyList(), day)
        assertEquals(2, plan.customers.size); assertEquals(200_000L, plan.fileNet); assertEquals(true, plan.totalMatches)
    }

    @Test fun importMatchesExistingCustomersAndEntries() {
        val existing = listOf(cust("محمد").copy(id = 7))
        val old = listOf(Entry(id = 1, partyId = 7, type = EntryType.CHARGE, amount = 500_000, date = LocalDate.of(2026, 10, 1)))
        val csv = "التاريخ,الاسم,المبلغ,النوع\n2026-10-01,محمد,5000,دين\n2026-10-03,محمد,1000,دين\n".toByteArray()
        val t = StatementImporter.read("x.csv", csv).first()
        val plan = StatementImporter.build(t, StatementImporter.guess(t), existing, old, day)
        assertEquals(7L, plan.customers.single().existingId)
        assertEquals(1, plan.toImport.size)
    }

    // ------------------------------------------------------------ multi-currency
    @Test fun ratesSeededForSanaaAndAden() {
        val r = Rates()
        assertEquals("139.5", Rates.plain(r.rate("SAR", "YER")!!))
        assertEquals("140.2", r.current.sarSell)
        assertEquals("533", Rates.plain(r.rate("USD", "YER")!!))
        val aden = r.copy(city = Rates.ADEN)
        assertEquals("411", Rates.plain(aden.rate("SAR", "YER")!!)); assertEquals("1579", aden.current.usdSell)
        assertEquals("2026-10-30", r.current.date)
    }

    @Test fun convertUsesTheStoredRate() {
        assertEquals(1_395_000L, Rates.convert(10_000, "139.5"))
        assertEquals(53_300L, Rates.convert(100, "533"))
    }

    @Test fun mixedCurrencyBalance() = runBlocking {
        val (repo, _, _) = setup()
        val id = repo.saveParty(cust("محمد"))
        repo.addCharge(id, 2_000_000, day, "")                                 // 20,000 ريال
        repo.addEntry(Entry(partyId = id, type = EntryType.PAYMENT, amount = Rates.convert(10_000, "139.5"), date = day, currency = "SAR", origAmount = 10_000, rate = "139.5"))
        repo.addEntry(Entry(partyId = id, type = EntryType.PAYMENT, amount = Rates.convert(1_000, "533"), date = day, currency = "USD", origAmount = 1_000, rate = "533"))
        val p = repo.findParty(id)!!
        assertEquals(2_000_000L - 1_395_000L - 533_000L, Ledger.balance(p, repo.allEntries(), day))
    }

    @Test fun changingDefaultCurrencyNeverRewritesHistory() = runBlocking {
        val (repo, _, _) = setup()
        val id = repo.saveParty(cust("قديم", ""))
        repo.addEntry(Entry(partyId = id, type = EntryType.PAYMENT, amount = 1_395_000, date = day, currency = "SAR", origAmount = 10_000, rate = "139.5"))
        repo.fillLegacyCurrency("YER")                                         // first run of 1.1.0: old accounts pinned to the old default
        val snapshot = repo.allEntries()
        // the user switches the default to SAR and edits the rate table: nothing stored may change
        repo.fillLegacyCurrency("SAR")
        assertEquals("YER", repo.findParty(id)!!.currency)
        assertEquals(snapshot, repo.allEntries())
        val e = repo.allEntries().single()
        assertEquals("139.5", e.rate); assertEquals(1_395_000L, e.amount)
    }

    @Test fun yeRialParsesAndFailsSafely() {
        val html = String(fixture("ye_rial.html"), Charsets.UTF_8)
        val got = Rates.parseYeRial(html, "2026-10-30")
        assertNotNull(got); assertTrue(got!!.any { it.city == Rates.SANAA }); assertTrue(got.any { it.city == Rates.ADEN })
        assertNull(Rates.parseYeRial("<html>maintenance</html>", "2026-10-30"))
    }

    @Test fun duplicateTransferNumberIsFound() = runBlocking {
        val (repo, _, _) = setup()
        val id = repo.saveParty(cust("محمد"))
        repo.addEntry(Entry(partyId = id, type = EntryType.PAYMENT, amount = 1000, date = day, method = "transfer", transferNo = "123456"))
        assertNotNull(repo.entryByTransfer("123456")); assertNull(repo.entryByTransfer("999"))
    }

    // ------------------------------------------------------------ offline «مدين» → confirmation cards
    private fun ax(): com.daftari.app.chat.OfflineAssistant.Ctx {
        val ps = listOf(cust("محمد الصنعاني").copy(id = 1), cust("محمد علي").copy(id = 2), cust("سالم").copy(id = 3))
        val es = listOf(Entry(id = 1, partyId = 3, type = EntryType.CHARGE, amount = 500_000, date = day))
        val sums = ps.map { Ledger.summarize(it, es.filter { e -> e.partyId == it.id }, day) }
        return com.daftari.app.chat.OfflineAssistant.Ctx(LedgerRepository.buildDashboard(sums, es, day), es, com.daftari.app.domain.Role.SHOP_OWNER, com.daftari.app.core.Currency.YER, Rates(), day)
    }

    @Test fun assistantSarTransferCardConverts() {
        val m = com.daftari.app.chat.OfflineAssistant.respond("سالم سدد 100 سعودي حوالة رقم 123456", ax())!!
        val a = m.action!!
        assertEquals("add_payment", a.type); assertEquals(3L, a.partyId)
        assertEquals("SAR", a.origCurrency); assertEquals(10_000L, a.origMinor); assertEquals("139.5", a.rate); assertEquals(1_395_000L, a.minor)
        assertEquals("transfer", a.method); assertEquals("123456", a.transferNo)
        assertTrue(a.lines.any { it.contains("= ") })
    }

    @Test fun assistantAmbiguousNameOffersChoices() {
        val m = com.daftari.app.chat.OfflineAssistant.respond("محمد سدد 2000", ax())!!
        assertNull(m.action); assertEquals(2, m.choices.size)
        assertTrue(m.choices.all { it.type == "add_payment" && it.minor == 200_000L })
    }

    @Test fun assistantNeverWritesWithoutACard() {
        listOf("سجل على سالم 5000", "ضيف زبون جديد اسمه ياسر 771234567", "تراجع", "ذكرني أطالب سالم بكرة الساعة 10").forEach { q ->
            val m = com.daftari.app.chat.OfflineAssistant.respond(q, ax().copy(lastLog = null))
            assertNotNull(q, m)
        }
        val bal = com.daftari.app.chat.OfflineAssistant.respond("كم على سالم؟", ax())!!
        assertNull(bal.action); assertTrue(bal.text.contains("5,000"))
    }
}
