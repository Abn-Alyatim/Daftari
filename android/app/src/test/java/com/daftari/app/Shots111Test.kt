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
import com.daftari.app.chat.ChatMessage
import com.daftari.app.chat.OfflineAssistant
import com.daftari.app.core.Currency
import com.daftari.app.core.Rates
import com.daftari.app.data.AppSettings
import com.daftari.app.data.LedgerRepository
import com.daftari.app.data.ServerInfo
import com.daftari.app.data.ThemeMode
import com.daftari.app.domain.Attachment
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Role
import com.daftari.app.export.ReportBuilder
import com.daftari.app.export.ReportRenderer
import com.daftari.app.ui.chat.ChatScreen
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.log.Bio
import com.daftari.app.ui.log.SecureGate
import com.daftari.app.ui.parties.DetailsActions
import com.daftari.app.ui.parties.MoneySheet
import com.daftari.app.ui.parties.PartyDetailsScreen
import com.daftari.app.ui.settings.SettingsActions
import com.daftari.app.ui.settings.SettingsScreen
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** The 1.1.1 screens → build/outputs/roborazzi/v111_*.png (copied to shots_1.1.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class Shots111Test {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 30)
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun noVideo() { ClipConfig.useVideo = false }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v111_$name.png")
    }

    private val party = Party(id = 1, kind = PartyKind.CUSTOMER, name = "محمد الصنعاني", phone = "+967771234567", currency = "YER", startDate = today.minusDays(20))
    private val entries = listOf(
        Entry(id = 5, partyId = 1, type = EntryType.PAYMENT, amount = 500_000, date = today, note = "محفظة جوالي", method = "wallet", transferNo = "778899", transferCo = "جوالي", senderPhone = "771234567"),
        Entry(id = 4, partyId = 1, type = EntryType.PAYMENT, amount = 300_000, date = today.minusDays(1), method = "wallet", transferNo = "4512309", transferCo = "ون كاش"),
        Entry(id = 3, partyId = 1, type = EntryType.PAYMENT, amount = 1_395_000, date = today.minusDays(2), currency = "SAR", origAmount = 10_000, rate = "139.5", method = "transfer", transferNo = "123456", transferCo = "الحزمي"),
        Entry(id = 2, partyId = 1, type = EntryType.CHARGE, amount = 1_200_000, date = today.minusDays(5), items = "كيس رز 10 كيلو\nسكر 2 كيلو"),
        Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 2_450_000, date = today.minusDays(9), items = "مواد شهرية"),
    )

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun b01_transfer_chips() {
        shot("01_payment_transfer_chips") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {}, receiptAttached = true,
                    initialMethod = "transfer", initialAmount = "20000", initialTransfer = "445566", initialCompany = "الحزمي") { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun b02_transfer_other() {
        shot("02_payment_transfer_other_typed") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {},
                    initialMethod = "transfer", initialAmount = "20000", initialCompany = "العمقي للصرافة") { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun b03_wallet_chips() {
        shot("03_payment_wallet_chips_icons") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {},
                    initialMethod = "wallet", initialAmount = "5000", initialTransfer = "778899", initialCompany = "جوالي", initialPhone = "771234567") { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Config(qualifiers = "ar-w360dp-h1250dp-xxhdpi")
    @Test fun b04_statement_rows() {
        val sum = Ledger.summarize(party, entries, today)
        val receipt = Attachment(id = 1, partyId = 1, fileName = "r.jpg", displayName = "إيصال محفظة جوالي 778899", mime = "image/jpeg", entryId = 5)
        shot("04_statement_wallet_row") { PartyDetailsScreen(sum, entries, listOf(receipt), Role.SHOP_OWNER, Currency.YER, DetailsActions()) }
        // the same payments in the shared statement (PDF / Excel use the same rows)
        val st = Ledger.statement(party, entries, today.minusDays(30), today)
        val r = ReportBuilder.partyStatement(st, Role.SHOP_OWNER, "بقالة الأمل", "مدين", Currency.YER)
        val out = File("build/outputs/v111").apply { mkdirs() }
        val renderer = ReportRenderer(ctx)
        File(out, "statement.png").outputStream().use { renderer.bitmap(r, 30).compress(Bitmap.CompressFormat.PNG, 100, it) }
        runCatching { renderer.pdf(r, File(out, "statement.pdf")) }
        File(out, "statement.xlsx").writeBytes(com.daftari.app.export.XlsxWriter.build(r))
    }

    @Test fun b05_madeen_wallet_card() {
        val sums = listOf(Ledger.summarize(party, entries, today))
        val x = OfflineAssistant.Ctx(LedgerRepository.buildDashboard(sums, entries, today), entries, Role.SHOP_OWNER, Currency.YER, Rates(), today)
        val q = "محمد سدد 5000 جوالي رقم 778899"
        val r = OfflineAssistant.respond(q, x)!!
        shot("05_madeen_wallet_confirm") { ChatScreen(listOf(ChatMessage(true, q, id = 1), r.copy(id = 2)), false, Role.SHOP_OWNER, {}, {}, {}, {}, {}, onAttach = {}) }
    }

    @Config(qualifiers = "ar-w360dp-h1900dp-xxhdpi")
    @Test fun b06_settings() {
        val st = AppSettings(onboarded = true, role = Role.SHOP_OWNER, businessName = "بقالة مدين", ownerName = "مدين", trialStartedAt = System.currentTimeMillis(), driveAccount = "madyan.store@gmail.com")
        shot("06_settings_daftari_row") { SettingsScreen(st, ServerInfo(version = "1.1.1"), SettingsActions()) }
    }

    @Test fun b07_pin_locked() {
        val msg = "أُوقفت المحاولات بعد 5 محاولات خاطئة. حاول بعد 5 دقائق."
        shot("07_pin_lockout") { SecureGate("السجل", "السجل محمي: يُفتح بالبصمة والرمز معاً", Bio.READY, true, true, {}, {}, { msg }, {}, {}, autoPrompt = false, lockedNow = { msg }) }
    }
}
