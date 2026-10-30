/*
 * دفتري · test/Shots117Test.kt
 * لقطات 1.1.7: 🎤 في «دين جديد» و«تسديد» وبجانب زر الإرسال في «اسأل مدين»، وعرض تنزيل نموذج الصوت، والتقدّم، والإعدادات.
 * 1.1.7 screenshots + checks: the mic is present (found by its accessibility label) in the debt sheet, the payment sheet
 * and the chat input bar next to «إرسال»; the one-time model download offer, its progress, and the settings rows.
 * → build/outputs/roborazzi/v117_*.png
 */
package com.daftari.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.daftari.app.core.Currency
import com.daftari.app.core.Rates
import com.daftari.app.data.ThemeMode
import com.daftari.app.domain.Role
import com.daftari.app.ui.chat.ChatScreen
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.parties.MoneySheet
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import com.daftari.app.voice.DownloadResult
import com.daftari.app.voice.MIC_LABEL
import com.daftari.app.voice.ModelDownloads
import com.daftari.app.voice.ModelOffer
import com.daftari.app.voice.ModelProgress
import com.daftari.app.voice.ModelState
import com.daftari.app.voice.VoiceModel
import com.daftari.app.voice.VoiceSettingsRows
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class Shots117Test {
    @get:Rule val compose = createComposeRule()

    @Before fun noVideo() { ClipConfig.useVideo = false }
    @After fun idle() { ModelDownloads.state.value = ModelState.Idle }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v117_$name.png")
    }

    private fun assertOneMic() = assertEquals(1, compose.onAllNodesWithContentDescription(MIC_LABEL).fetchSemanticsNodes().size)

    @Composable private fun Card(content: @Composable () -> Unit) =
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Df.c.sheet).padding(20.dp)) { content() }
        }

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun a_debt_sheet_has_mic() {
        shot("a_debt_sheet_mic") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(true, "دين جديد", "علي أحمد", 1_000_000, 0, Currency.YER, false, {}, rates = Rates()) { _, _, _, _, _, _, _ -> }
            }
        }
        assertOneMic()
    }

    @Config(qualifiers = "ar-w360dp-h1000dp-xxhdpi")
    @Test fun b_payment_sheet_has_mic_while_model_downloads() {
        ModelDownloads.state.value = ModelState.Downloading(VoiceModel.BASE, 62)
        shot("b_payment_sheet_mic") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد صالح", 800_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {}) { _, _, _, _, _, _, _ -> }
            }
        }
        assertOneMic()
    }

    @Test fun c_chat_has_mic_next_to_send() {
        shot("c_chat_mic") {
            ChatScreen(emptyList(), busy = false, role = Role.SHOP_OWNER, onSend = {}, onConfirm = {}, onCancel = {}, onExportTable = {}, onClear = {}, onAttach = {})
        }
        assertOneMic()
    }

    @Test fun d_model_offer() {
        shot("d_model_offer") { Card { ModelOffer(onDownload = {}, onClose = {}, mobileData = true) } }
        compose.onNodeWithText("تنزيل نموذج الصوت دون إنترنت", substring = true).assertExists()
    }

    @Config(qualifiers = "ar-w360dp-h640dp-xxhdpi")
    @Test fun e_model_progress() {
        shot("e_model_progress") { Card { ModelProgress(ModelState.Downloading(VoiceModel.BASE, 40), onRetry = {}, onClose = {}) } }
        compose.onNodeWithText("متابعة الكتابة").assertExists()
    }

    @Config(qualifiers = "ar-w360dp-h640dp-xxhdpi")
    @Test fun e2_model_failed() {
        shot("e2_model_failed") { Card { ModelProgress(ModelState.Failed(DownloadResult.NETWORK), onRetry = {}, onClose = {}) } }
        compose.onNodeWithText("إعادة المحاولة").assertExists()
    }

    @Config(qualifiers = "ar-w360dp-h640dp-xxhdpi")
    @Test fun f_settings_rows() {
        shot("f_settings_voice") { Card { VoiceSettingsRows() } }
        compose.onNodeWithText("إرسال تلقائي بعد التحدث").assertExists()
    }
}
