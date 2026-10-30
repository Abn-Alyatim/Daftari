/*
 * دفتري · test/Shots116Test.kt
 * لقطات شاشة 1.1.6 (Robolectric + Roborazzi): نافذة الدين معبّأة من الصوت، تنبيه «التبديل»، مراجعة الصوت، اختيار الزبون السريع،
 * «سداد كامل»، وسطر تنزيل حزمة العربية. → build/outputs/roborazzi/v116_*.png
 * 1.1.6 screenshots for review without a phone (record mode, like the earlier Shots tests).
 */
package com.daftari.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.daftari.app.core.Currency
import com.daftari.app.core.Rates
import com.daftari.app.data.ThemeMode
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Role
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.parties.DetailsActions
import com.daftari.app.ui.parties.MoneySheet
import com.daftari.app.ui.parties.PartyDetailsScreen
import com.daftari.app.ui.parties.QuickPickSheet
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import com.daftari.app.voice.HeardLine
import com.daftari.app.voice.ModelDownloads
import com.daftari.app.voice.ModelState
import com.daftari.app.voice.ModelStatusLine
import com.daftari.app.voice.VoiceModel
import com.daftari.app.voice.VoiceCustomer
import com.daftari.app.voice.VoiceEntryParser
import com.daftari.app.voice.VoiceKind
import com.daftari.app.voice.VoicePrefill
import com.daftari.app.voice.VoiceReviewSheet
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class Shots116Test {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 30)

    @Before fun noVideo() { ClipConfig.useVideo = false }
    @After fun idle() { ModelDownloads.state.value = ModelState.Idle }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v116_$name.png")
    }

    private val parties = listOf(
        Party(id = 1, kind = PartyKind.CUSTOMER, name = "علي أحمد", currency = "YER", startDate = today.minusDays(30)),
        Party(id = 2, kind = PartyKind.CUSTOMER, name = "محمد صالح", currency = "YER", startDate = today.minusDays(30)),
        Party(id = 3, kind = PartyKind.CUSTOMER, name = "خالد سعيد", currency = "YER", startDate = today.minusDays(30)),
        Party(id = 4, kind = PartyKind.CUSTOMER, name = "عبدالله", currency = "YER", startDate = today.minusDays(30)),
        Party(id = 5, kind = PartyKind.CUSTOMER, name = "محمد علي", currency = "YER", startDate = today.minusDays(30)),
    )
    private val entries = listOf(
        Entry(id = 1, partyId = 1, type = EntryType.CHARGE, amount = 1_250_000, date = today.minusDays(3), createdAt = 1),
        Entry(id = 2, partyId = 2, type = EntryType.CHARGE, amount = 800_000, date = today.minusDays(2), createdAt = 2),
        Entry(id = 3, partyId = 3, type = EntryType.CHARGE, amount = 300_000, date = today.minusDays(1), createdAt = 3),
        Entry(id = 4, partyId = 1, type = EntryType.PAYMENT, amount = 250_000, date = today, createdAt = 4),
    )
    private val sums = parties.map { p -> Ledger.summarize(p, entries.filter { it.partyId == p.id }, today) }
    private val book = parties.map { VoiceCustomer(it.id, it.name) }

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun a_debt_filled_by_voice() {
        val pf = VoicePrefill("5000", null, "كيس رز", "علي أحمد عليه خمسة آلاف ريال حق كيس رز", VoiceKind.DEBT)
        shot("a_debt_sheet_voice_filled") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(true, "دين جديد", "علي أحمد", 1_000_000, 0, Currency.YER, false, {}, rates = Rates(), initialAmount = pf.amount, voice = pf) { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Config(qualifiers = "ar-w360dp-h1100dp-xxhdpi")
    @Test fun b_payment_said_debt_and_chips() {
        val pf = VoicePrefill("2000", null, "", "سجل على محمد صالح ألفين", VoiceKind.DEBT)
        shot("b_pay_sheet_switch_and_chips") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد صالح", 800_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {},
                    initialAmount = pf.amount, voice = pf, onSwitchKind = {}) { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Test fun c_review_sure() {
        val d = VoiceEntryParser.parse("سجل على محمد صالح ألفين وخمسمية", book)
        shot("c_review_sure_match") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                VoiceReviewSheet(d, false, Currency.YER, { id -> parties.firstOrNull { it.id == id }?.title }, { _, _ -> }, { _, _ -> }, {}, {}, {})
            }
        }
    }

    @Test fun d_review_suggest() {
        val d = VoiceEntryParser.parse("سدد خالد ثلاثة آلاف", book)
        shot("d_review_did_you_mean") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                VoiceReviewSheet(d, false, Currency.YER, { id -> parties.firstOrNull { it.id == id }?.title }, { _, _ -> }, { _, _ -> }, {}, {}, {})
            }
        }
    }

    @Test fun e_review_new_and_options() {
        val d = VoiceEntryParser.parse("محمد عليه عشرين ألف دولار", book)
        shot("e_review_options_new") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                VoiceReviewSheet(d, false, Currency.YER, { id -> parties.firstOrNull { it.id == id }?.title }, { _, _ -> }, { _, _ -> }, {}, {}, {})
            }
        }
    }

    @Test fun f_quick_pick_recent() {
        val recent = com.daftari.app.core.QuickEntry.recentPartyIds(entries, parties.map { it.id }.toSet())
        shot("f_quick_pick_recent") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                QuickPickSheet("دين جديد", sums, recent, Currency.YER, {}, {}, {}, {})
            }
        }
    }

    @Test fun g_details_pay_full() {
        shot("g_details_pay_full") {
            PartyDetailsScreen(sums[0], entries.filter { it.partyId == 1L }, emptyList(), Role.SHOP_OWNER, Currency.YER, DetailsActions(onPayFull = {}))
        }
    }

    @Test fun h_pack_downloading_line() {
        // 1.1.7: the Google pack line became the offline model's download line
        ModelDownloads.state.value = ModelState.Downloading(VoiceModel.BASE, 40)
        shot("h_pack_status_and_heard") {
            Column(Modifier.padding(16.dp)) {
                ModelStatusLine()
                HeardLine("سدد خالد ثلاثة آلاف", "عُبّئ: المبلغ 3,000", "فهمتُ من كلامك «تسديد»، وهذه نافذة «دين جديد».", "التبديل إلى «تسديد»", {})
            }
        }
    }
}
