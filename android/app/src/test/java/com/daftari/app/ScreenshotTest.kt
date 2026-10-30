package com.daftari.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.daftari.app.chat.ChatMessage
import com.daftari.app.core.Currency
import com.daftari.app.data.AppSettings
import com.daftari.app.data.ChatTable
import com.daftari.app.data.Demo
import com.daftari.app.data.LedgerRepository
import com.daftari.app.data.ServerInfo
import com.daftari.app.data.ThemeMode
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Role
import com.daftari.app.importer.ImportResult
import com.daftari.app.importer.ImportRow
import com.daftari.app.ui.auth.EntryMode
import com.daftari.app.ui.auth.EntryScreen
import com.daftari.app.ui.chat.ChatScreen
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.extra.ImportScreen
import com.daftari.app.ui.extra.LockScreen
import com.daftari.app.ui.extra.PaywallScreen
import com.daftari.app.ui.extra.ReportsMenu
import com.daftari.app.ui.home.HomeActions
import com.daftari.app.ui.home.HomeScreen
import com.daftari.app.ui.parties.DetailsActions
import com.daftari.app.ui.parties.MoneySheet
import com.daftari.app.ui.parties.PartiesScreen
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
import java.time.LocalDate

/** Every main screen in both modes (landlord / grocery) and both themes. PNGs → app/build/outputs/roborazzi/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 28)

    @Before fun noVideo() { ClipConfig.useVideo = false }

    private fun shot(name: String, theme: ThemeMode = ThemeMode.DARK_BLUE, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(theme) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun data(role: Role): Pair<com.daftari.app.data.Dashboard, List<com.daftari.app.domain.Entry>> {
        val seed = Demo.forRole(role, today)
        val parties = seed.parties.mapIndexed { i, p -> p.copy(id = i + 1L, category = if (role == Role.LANDLORD) (if (i < 12) "الدور الأول" else "الدور الثاني") else "") }
        val entries = seed.entries(parties.map { it.id })
        val by = entries.groupBy { it.partyId }
        val sums = parties.map { Ledger.summarize(it, by[it.id].orEmpty(), today) }
        return LedgerRepository.buildDashboard(sums, entries, today) to entries
    }

    @Test fun s01_home_landlord() { val (d, _) = data(Role.LANDLORD); shot("01_home_landlord") { HomeScreen(d, Role.LANDLORD, "سوق الكحلاني", "سامي الهلباء", Currency.YER, today, HomeActions()) } }
    @Test fun s02_home_grocery() { val (d, _) = data(Role.SHOP_OWNER); shot("02_home_grocery") { HomeScreen(d, Role.SHOP_OWNER, "بقالة الأمانة", "عبدالله أحمد", Currency.YER, today, HomeActions()) } }
    @Test fun s03_home_light() { val (d, _) = data(Role.LANDLORD); shot("03_home_landlord_light", ThemeMode.LIGHT) { HomeScreen(d, Role.LANDLORD, "سوق الكحلاني", "سامي الهلباء", Currency.YER, today, HomeActions()) } }
    @Test fun s04_shops() { val (d, _) = data(Role.LANDLORD); shot("04_shops_list") { PartiesScreen(d.summaries, Role.LANDLORD, Currency.YER, {}, {}, {}) } }
    @Test fun s05_customers() { val (d, _) = data(Role.SHOP_OWNER); shot("05_customers_list") { PartiesScreen(d.summaries, Role.SHOP_OWNER, Currency.YER, {}, {}, {}) } }
    @Test fun s05b_customers_light() { val (d, _) = data(Role.SHOP_OWNER); shot("05b_customers_light", ThemeMode.LIGHT) { PartiesScreen(d.summaries, Role.SHOP_OWNER, Currency.YER, {}, {}, {}) } }
    @Test fun s06_shop_details() {
        val (d, e) = data(Role.LANDLORD); val s = d.summaries[3]
        shot("06_shop_details") { PartyDetailsScreen(s, e.filter { it.partyId == s.party.id }, emptyList(), Role.LANDLORD, Currency.YER, DetailsActions()) }
    }
    @Test fun s07_customer_details() {
        val (d, e) = data(Role.SHOP_OWNER); val s = d.summaries.maxBy { it.balance }
        shot("07_customer_details") { PartyDetailsScreen(s, e.filter { it.partyId == s.party.id }, emptyList(), Role.SHOP_OWNER, Currency.YER, DetailsActions()) }
    }
    @Test fun s08_new_debt_sheet() {
        val (d, _) = data(Role.SHOP_OWNER); val s = d.summaries[0]
        shot("08_new_debt_sheet") { Box(Modifier.fillMaxSize()) { MoneySheet(true, "دين جديد", s.party.title, s.balance, 0, Currency.YER, false, {}) { _, _, _, _, _, _, _ -> } } }
    }
    @Test fun s09_chat() {
        val msgs = listOf(
            ChatMessage(true, "مين أكثر واحد عليه؟", id = 1),
            ChatMessage(false, "أكثر ثلاثة عليهم: سعيد المنصري 18,500 YER، فوزي سعيد 12,000 YER، علي الضبيبي 9,000 YER.",
                ChatTable("أعلى الديون", listOf("الزبون", "المتبقي"), listOf(listOf("سعيد المنصري", "18,500"), listOf("فوزي سعيد", "12,000"))), id = 2),
            ChatMessage(true, "ايش رأيك في الدوري الإسباني؟", id = 3),
            ChatMessage(false, "ودّي أساعدك 😄 بس أنا مختص بدفترك فقط. تبغى أطلع لك المتأخرين هذا الأسبوع؟", id = 4),
        )
        shot("09_chat_madyan") { ChatScreen(msgs, false, Role.SHOP_OWNER, {}, {}, {}, {}, {}) }
    }
    @Test fun s10_reports() { shot("10_reports_menu") { ReportsMenu({}, {}) } }
    @Test fun s11_settings() {
        val st = AppSettings(onboarded = true, role = Role.LANDLORD, businessName = "سوق الكحلاني", ownerName = "سامي الهلباء", shopCount = 200, trialStartedAt = System.currentTimeMillis())
        shot("11_settings") { SettingsScreen(st, ServerInfo(version = "1.0.0", otpProvider = "whatsapp_cloud"), SettingsActions()) }
    }
    @Test fun s12_paywall() { shot("12_paywall_pro") { PaywallScreen("تجربة برو مجانية · باقي 5 يوم", Currency.YER, {}, {}, {}) } }
    @Test fun s13_import_review() {
        val rows = listOf(
            ImportRow("أيوب العبدلي", "1", activity = "بهارات", amount = 200_000, arrears = 600_000),
            ImportRow("بدر هادي الاشول", "2", amount = 200_000),
            ImportRow("جلال القاضي", "", amount = 0, doubtful = true, reason = "رقم المحل غير واضح في الصورة"),
            ImportRow("حمدي الاسدي", "4", amount = 150_000, arrears = 300_000),
        )
        shot("13_import_review") { ImportScreen(ImportResult(rows, "سوق الكحلاني", "سامي الهلباء"), false, true, Role.LANDLORD, Currency.YER, {}, {}, {}) }
    }
    @Test fun s14_login() { shot("14_login") { EntryScreen(EntryMode.FIRST_RUN, Role.SHOP_OWNER, {}) } }
    @Test fun s15_lock() { shot("15_lock") { LockScreen(false, true, {}, { "كلمة السر غير صحيحة" }) } }
}
