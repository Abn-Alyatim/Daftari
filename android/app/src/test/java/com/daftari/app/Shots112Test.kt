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
import com.daftari.app.ui.settings.RatesScreen
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

/** The 1.1.2 screens → build/outputs/roborazzi/v112_*.png (copied to shots_1.1.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class Shots112Test {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 30)

    @Before fun noVideo() { ClipConfig.useVideo = false }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v112_$name.png")
    }

    private val party = Party(id = 1, kind = PartyKind.CUSTOMER, name = "محمد الصنعاني", phone = "+967771234567", currency = "YER", startDate = today.minusDays(20))
    private val entries = listOf(
        Entry(id = 6, partyId = 1, type = EntryType.PAYMENT, amount = 1_000_000, date = today, method = "transfer", transferNo = "60412873", transferCo = "الكريمي"),
        Entry(id = 5, partyId = 1, type = EntryType.PAYMENT, amount = 500_000, date = today.minusDays(1), method = "transfer", transferNo = "445566", transferCo = "النجم"),
        Entry(id = 4, partyId = 1, type = EntryType.PAYMENT, amount = 300_000, date = today.minusDays(1), method = "wallet", transferNo = "4512309", transferCo = "حاسب"),
        Entry(id = 3, partyId = 1, type = EntryType.PAYMENT, amount = 1_395_000, date = today.minusDays(2), currency = "SAR", origAmount = 10_000, rate = "139.5", method = "transfer", transferNo = "123456", transferCo = "الحزمي"),
        Entry(id = 2, partyId = 1, type = EntryType.CHARGE, amount = 1_200_000, date = today.minusDays(5), items = "كيس رز 10 كيلو\nسكر 2 كيلو"),
        Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 2_450_000, date = today.minusDays(9), items = "مواد شهرية"),
    )

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun c01_transfer_logos() {
        shot("a_transfer_chips_logos") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {},
                    initialMethod = "transfer", initialAmount = "10000", initialTransfer = "60412873", initialCompany = "الكريمي") { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun c02_wallet_hasib() {
        shot("b_wallet_chips_hasib") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {},
                    initialMethod = "wallet", initialAmount = "3000", initialTransfer = "4512309", initialCompany = "حاسب") { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Config(qualifiers = "ar-w360dp-h1250dp-xxhdpi")
    @Test fun c03_rows() {
        val sum = Ledger.summarize(party, entries, today)
        shot("c_transaction_rows_kuraimi") { PartyDetailsScreen(sum, entries, emptyList(), Role.SHOP_OWNER, Currency.YER, DetailsActions()) }
    }

    @Config(qualifiers = "ar-w360dp-h900dp-xxhdpi")
    @Test fun c04_rates() {
        shot("d_rates_buy_sell") { RatesScreen(Rates(), null, {}, {}, {}) }
    }

    @Test fun c05_madeen_kuraimi() {
        val sums = listOf(Ledger.summarize(party, entries, today))
        val x = OfflineAssistant.Ctx(LedgerRepository.buildDashboard(sums, entries, today), entries, Role.SHOP_OWNER, Currency.YER, Rates(), today)
        val q = "محمد سدد 100 سعودي حوالة الكريمي رقم 778899"
        val r = OfflineAssistant.respond(q, x)!!
        shot("e_madeen_kuraimi_confirm") { ChatScreen(listOf(ChatMessage(true, q, id = 1), r.copy(id = 2)), false, Role.SHOP_OWNER, {}, {}, {}, {}, {}, onAttach = {}) }
    }
}
