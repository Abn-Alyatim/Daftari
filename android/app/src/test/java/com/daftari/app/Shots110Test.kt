package com.daftari.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.daftari.app.audit.AuditDb
import com.daftari.app.audit.AuditLog
import com.daftari.app.audit.AuditRecord
import com.daftari.app.audit.HeadAnchor
import com.daftari.app.backup.DriveFile
import com.daftari.app.chat.ChatMessage
import com.daftari.app.chat.OfflineAssistant
import com.daftari.app.core.Currency
import com.daftari.app.core.Rates
import com.daftari.app.data.AppSettings
import com.daftari.app.data.DaftariDb
import com.daftari.app.data.Demo
import com.daftari.app.data.LedgerRepository
import com.daftari.app.data.ServerInfo
import com.daftari.app.data.ThemeMode
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Role
import com.daftari.app.export.LogReport
import com.daftari.app.export.ReportRenderer
import com.daftari.app.export.XlsxWriter
import com.daftari.app.importer.StatementImporter
import com.daftari.app.ui.backup.BackupActions
import com.daftari.app.ui.backup.BackupPreviewSheet
import com.daftari.app.ui.backup.BackupScreen
import com.daftari.app.ui.backup.BackupUi
import com.daftari.app.ui.chat.ChatScreen
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.importer.MigratePreview
import com.daftari.app.ui.log.Bio
import com.daftari.app.ui.log.LogDetailScreen
import com.daftari.app.ui.log.LogListScreen
import com.daftari.app.ui.log.RestoreConfirm
import com.daftari.app.ui.log.SecureGate
import com.daftari.app.ui.parties.MoneySheet
import com.daftari.app.ui.settings.RatesScreen
import com.daftari.app.ui.settings.SettingsActions
import com.daftari.app.ui.settings.SettingsScreen
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** The 1.1.0 screens → build/outputs/roborazzi/v110_*.png (copied to shots_1.1.0). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class Shots110Test {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 29)
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun noVideo() { ClipConfig.useVideo = false }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v110_$name.png")
    }

    private fun data(): Pair<com.daftari.app.data.Dashboard, List<Entry>> {
        val seed = Demo.forRole(Role.SHOP_OWNER, today)
        val parties0 = seed.parties.mapIndexed { i, p -> p.copy(id = i + 1L, currency = "YER") }
        val entries = seed.entries(parties0.map { it.id })
        val by = entries.groupBy { it.partyId }
        val top = parties0.maxBy { p -> Ledger.balance(p, by[p.id].orEmpty(), today) }.id
        val parties = parties0.map { if (it.id == top) it.copy(name = "محمد الصنعاني") else it }
        val sums = parties.map { Ledger.summarize(it, by[it.id].orEmpty(), today) }
        return LedgerRepository.buildDashboard(sums, entries, today) to entries
    }

    private class Mem : HeadAnchor { var v: Pair<Long, String>? = null; override fun read() = v; override fun write(seq: Long, hash: String) { v = seq to hash } }

    private fun logRows(): Pair<List<AuditRecord>, AuditLog> = runBlocking {
        val log = AuditLog(AuditDb.create(ctx, true).dao(), Mem())
        val repo = LedgerRepository(DaftariDb.create(ctx, true), ctx, log)
        val m = repo.saveParty(Party(kind = PartyKind.CUSTOMER, name = "محمد الصنعاني", phone = "+967771234567", currency = "YER", startDate = today))
        val s = repo.saveParty(Party(kind = PartyKind.CUSTOMER, name = "سالم الحداد", currency = "YER", startDate = today))
        val f = repo.saveParty(Party(kind = PartyKind.CUSTOMER, name = "فهد الحميري", currency = "YER", startDate = today))
        repo.addCharge(m, 500_000, today, "", "رز 10 كيلو، سكر", null)
        repo.addEntry(Entry(partyId = m, type = EntryType.PAYMENT, amount = 1_395_000, date = today, currency = "SAR", origAmount = 10_000, rate = "139.5", method = "transfer", transferNo = "123456", transferCo = "الكريمي"), com.daftari.app.audit.Via.ASSISTANT)
        val e = repo.addCharge(s, 250_000, today, "زيت")
        repo.updateEntry(repo.findEntry(e)!!.copy(amount = 320_000, note = "زيت + صابون"))
        repo.addCharge(f, 120_000, today, "")
        repo.deleteParty(f)
        repo.restoreFromLog(log.all().last { it.action == com.daftari.app.audit.Action.DELETE })
        repo.addCharge(s, 80_000, today, "خبز")
        repo.deleteEntry(repo.allEntries().last { it.note == "خبز" }.id)
        log.all().reversed() to log
    }

    @Test fun a01_drive() {
        val files = listOf(
            DriveFile("1", "Daftari-2026-10-29-0905.daftari", 48_213, 1_793_257_500_000), DriveFile("2", "Daftari-2026-10-28-2100.daftari", 47_920, 1_793_214_000_000),
            DriveFile("3", "Daftari-2026-10-27-2100.daftari", 46_002, 1_793_127_600_000),
        )
        shot("01_drive") { BackupScreen(BackupUi("madyan.store@gmail.com", true, 1_793_257_500_000, 0, files, null, false, true, true), BackupActions()) }
    }

    @Test fun a02_drive_setup_missing() {
        shot("02_drive_not_set_up") { BackupScreen(BackupUi("", false, 0, 0, emptyList(), null, true, false, false), BackupActions()) }
    }

    @Test fun a03_assistant_confirm() {
        val (d, e) = data()
        val x = OfflineAssistant.Ctx(d, e, Role.SHOP_OWNER, Currency.YER, Rates(), today)
        val q1 = "محمد سدد 100 سعودي حوالة رقم 123456 الكريمي"
        val r1 = OfflineAssistant.respond(q1, x)!!
        shot("03_assistant_confirm_card") { ChatScreen(listOf(ChatMessage(true, q1, id = 1), r1.copy(id = 2)), false, Role.SHOP_OWNER, {}, {}, {}, {}, {}, onAttach = {}) }
    }

    @Test fun a04_assistant_debt_and_answer() {
        val (d, e) = data()
        val x = OfflineAssistant.Ctx(d, e, Role.SHOP_OWNER, Currency.YER, Rates(), today)
        val q1 = "كم على فهد؟"; val q2 = "سجل على فهد رز وسكر بـ5000"
        shot("04_assistant_debt_card") {
            ChatScreen(listOf(ChatMessage(true, q1, id = 1), OfflineAssistant.respond(q1, x)!!.copy(id = 2), ChatMessage(true, q2, id = 3), OfflineAssistant.respond(q2, x)!!.copy(id = 4)),
                false, Role.SHOP_OWNER, {}, {}, {}, {}, {}, onAttach = {})
        }
    }

    @Test fun a05_import_mapping_preview() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fixtures/import_sample_monthly_his.xls")!!.readBytes()
        val t = StatementImporter.read("import_sample_monthly_his.xls", bytes).first()
        val plan = StatementImporter.build(t, StatementImporter.guess(t), emptyList(), emptyList(), today)
        shot("05_import_mapping_preview") { MigratePreview(listOf(t), 0, plan, false, null, {}, { _, _ -> }, {}, {}, {}, {}) }
    }

    @Test fun a06_log_list() {
        val (rows, log) = logRows()
        val v = runBlocking { log.verify() }
        shot("06_log_list") { LogListScreen(rows, v, emptySet(), "", {}, {}, {}, {}, {}) }
    }

    @Test fun a07_log_detail() {
        val (rows, _) = logRows()
        val upd = rows.first { it.action == com.daftari.app.audit.Action.UPDATE }
        shot("07_log_detail_before_after") { LogDetailScreen(upd, null, {}, {}) }
    }

    @Test fun a08_restore_confirm() {
        val (rows, _) = logRows()
        val del = rows.first { it.action == com.daftari.app.audit.Action.DELETE && it.entity == "ENTRY" }
        shot("08_restore_confirm") { Box(Modifier.fillMaxSize()) { LogDetailScreen(del, null, {}, {}); RestoreConfirm(del, {}, {}) } }
    }

    @Test fun a09_gate_pin() {
        shot("09_gate_fingerprint_and_pin") { SecureGate("السجل", "السجل محمي: يفتح بالبصمة والرمز معاً", Bio.READY, true, true, {}, {}, { "الرمز غير صحيح" }, {}, {}, autoPrompt = false) }
    }

    @Test fun a10_gate_fingerprint() {
        shot("10_gate_step1_fingerprint") { SecureGate("تأكيد الاسترجاع", "الاسترجاع يحتاج البصمة والرمز", Bio.READY, false, false, {}, {}, { "الرمز غير صحيح" }, {}, {}, autoPrompt = false) }
    }

    @Test fun a11_payment_sheet_currency_transfer() {
        shot("11_payment_sar_transfer") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {},
                    initialCurrency = Currency.SAR, initialMethod = "transfer", initialAmount = "100", initialTransfer = "123456", initialCompany = "الكريمي") { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Test fun a12_rates() { shot("12_rates_table") { RatesScreen(Rates(), null, {}, {}, {}) } }

    @Config(qualifiers = "ar-w360dp-h1900dp-xxhdpi")
    @Test fun a13_settings() {
        val st = AppSettings(onboarded = true, role = Role.SHOP_OWNER, businessName = "بقالة مدين", ownerName = "مدين", trialStartedAt = System.currentTimeMillis(), driveAccount = "madyan.store@gmail.com")
        shot("13_settings") { SettingsScreen(st, ServerInfo(version = "1.1.0"), SettingsActions()) }
    }

    @Test fun a14_backup_preview() {
        shot("14_restore_backup_preview") {
            Box(Modifier.fillMaxSize()) {
                BackupScreen(BackupUi("", false, 0, 0, emptyList(), null, false, false, true), BackupActions())
                BackupPreviewSheet("Daftari-2026-10-29-0905.daftari", 34, 212, 167, 431, 3, "29 أكتوبر 2026", emptyList(), true, {}, {})
            }
        }
    }

    /** «كشف السجل»: the real PDF (rasterised outside for the screenshot) + the same page as PNG + Excel. */
    @Test fun a15_log_export() {
        val (rows, log) = logRows()
        val r = LogReport.build(rows, today.withDayOfMonth(1), today, "", emptySet(), runBlocking { log.verify() }, "بقالة مدين", "مدين", today)
        val out = File("build/outputs/v110").apply { mkdirs() }
        val renderer = ReportRenderer(ctx)
        runCatching { renderer.pdf(r, File(out, "log_export.pdf")) }
        File(out, "log_export.png").outputStream().use { renderer.bitmap(r, 60).compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "log_export.xlsx").writeBytes(XlsxWriter.build(r))
    }
}
