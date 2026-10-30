package com.daftari.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.daftari.app.data.ActivePlan
import com.daftari.app.data.AppSettings
import com.daftari.app.data.Broadcast
import com.daftari.app.data.ThemeMode
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.settings.SettingsActions
import com.daftari.app.ui.settings.SettingsScreen
import com.daftari.app.ui.subs.BroadcastDialog
import com.daftari.app.ui.subs.MaintenanceBanner
import com.daftari.app.ui.subs.MaintenanceScreen
import com.daftari.app.ui.subs.SubActions
import com.daftari.app.ui.subs.SubDecisionDialog
import com.daftari.app.ui.subs.SubFlowState
import com.daftari.app.ui.subs.SubLoad
import com.daftari.app.ui.subs.SubStep
import com.daftari.app.ui.subs.SubUi
import com.daftari.app.ui.subs.SubscriptionFlow
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 1.1.5 subscription screens → build/outputs/roborazzi/v115_*.png (copied to shots_1.1.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h780dp-xxhdpi", application = android.app.Application::class)
class Shots115Test {
    @get:Rule val compose = createComposeRule()

    @Before fun noVideo() { ClipConfig.useVideo = false }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = true
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v115_$name.png")
    }

    private val cfg = Samples115.config
    @Composable private fun flow(f: SubFlowState, receipt: Boolean = false, plan: ActivePlan? = null) {
        val bmp = if (receipt) receiptBitmap() else null
        SubscriptionFlow(SubUi(SubLoad.Ready(cfg), f, plan = plan, statusText = "تجربة برو مجانية · متبقٍّ 5 يوم", receipt = bmp), SubActions())
    }

    /** A drawn stand-in for a wallet receipt (no real person's receipt in the repo). */
    private fun receiptBitmap(): androidx.compose.ui.graphics.ImageBitmap {
        val b = android.graphics.Bitmap.createBitmap(720, 440, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        cv.drawColor(android.graphics.Color.rgb(246, 246, 242))
        val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(40, 40, 40); textSize = 34f; isAntiAlias = true }
        cv.drawRect(0f, 0f, 720f, 70f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(196, 30, 58) })
        cv.drawText("JAIB  -  Transfer receipt", 30f, 48f, android.graphics.Paint(p).apply { color = android.graphics.Color.WHITE })
        listOf("To: 780647484", "Amount: 3,000 YER", "Ref: 556677", "2026-10-30 10:12").forEachIndexed { i, s -> cv.drawText(s, 30f, 140f + i * 66f, p) }
        return b.asImageBitmap()
    }

    @Test fun a_plans() { shot("a_plan_pick") { flow(SubFlowState(planId = "pro_month")) } }
    @Test fun b_transfer_tab() { shot("b_tab_hawala") { flow(SubFlowState(SubStep.METHOD, "pro_month", tab = "transfer", methodId = "kuraimi")) } }
    @Test fun c_wallet_tab() { shot("c_tab_wallets") { flow(SubFlowState(SubStep.METHOD, "pro_month", tab = "wallet", methodId = "jaib")) } }
    @Test fun c2_other() { shot("c2_tab_wallets_other") { flow(SubFlowState(SubStep.METHOD, "pro_month", tab = "wallet", methodId = "wallet_other", other = "موبايل موني")) } }
    @Test fun d_pay() { shot("d_payment_details_copy") { flow(SubFlowState(SubStep.PAY, "pro_month", tab = "wallet", methodId = "jaib")) } }
    @Test fun e_receipt() {
        shot("e_receipt_fields") { flow(SubFlowState(SubStep.RECEIPT, "pro_month", tab = "wallet", methodId = "jaib", txn = "556677", name = "مدين محافظ حميد اليتيم", receiptPath = "/tmp/x.jpg"), receipt = true) }
    }
    @Test fun e2_receipt_empty() { shot("e2_receipt_empty") { flow(SubFlowState(SubStep.RECEIPT, "pro_month", tab = "transfer", methodId = "kuraimi", txn = "12", name = "مدين اليتيم")) } }
    @Test fun f_pending() {
        shot("f_pending") { SubscriptionFlow(SubUi(SubLoad.Ready(cfg), SubFlowState(), pending = Samples115.pending, statusText = "تجربة برو مجانية · متبقٍّ 5 يوم"), SubActions()) }
    }
    @Test fun g_approved() {
        shot("g_approved_message") {
            Box(Modifier.fillMaxSize()) {
                SettingsScreen(AppSettings(ownerName = "مدين اليتيم", googleEmail = "alyateemmadin@gmail.com", proUntil = Samples115.approved.endsAt, planName = "برو شهري", planBadge = "برو"), null, SettingsActions())
                SubDecisionDialog(Samples115.approved, {})
            }
        }
    }
    @Test fun h_badge() {
        shot("h_badge_next_to_account") {
            SettingsScreen(AppSettings(ownerName = "مدين اليتيم", googleEmail = "alyateemmadin@gmail.com", proUntil = System.currentTimeMillis() + 29 * 86_400_000L, planName = "برو شهري", planBadge = "برو"), null, SettingsActions())
        }
    }
    @Test fun i_rejected() { shot("i_rejected_reason") { SubDecisionDialog(Samples115.rejected, {}) } }
    @Test fun j_maintenance_full() { shot("j_maintenance_full") { MaintenanceScreen("نحدّث خادم دفتري الآن، وسنعود خلال نصف ساعة تقريباً.", false) {} } }
    @Test fun k_maintenance_banner() {
        shot("k_maintenance_banner") { Column(Modifier.fillMaxSize()) { MaintenanceBanner("الاشتراكات متوقفة مؤقتاً للصيانة، ودفترك يعمل كالمعتاد.") {} } }
    }
    @Test fun l_broadcast() { shot("l_broadcast") { BroadcastDialog(Broadcast("bc_1", "نسخة جديدة", "صدرت النسخة 1.1.5 من دفتري: اشتراك بالحوالة أو المحفظة من داخل التطبيق.")) {} } }
    @Test fun m_unavailable() { shot("m_server_not_ready") { SubscriptionFlow(SubUi(SubLoad.Failed(com.daftari.app.core.Subs.errorText(com.daftari.app.data.ApiException("http_404", "", 404))), SubFlowState()), SubActions()) } }
    @Test fun n_active_renew() {
        shot("n_active_plan_renew") { flow(SubFlowState(planId = "pro_year"), plan = ActivePlan("برو شهري", "برو", Samples115.T0, Samples115.T0 + 20 * 86_400_000L, true, 20)) }
    }
}
