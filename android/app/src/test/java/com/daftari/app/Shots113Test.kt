package com.daftari.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.daftari.app.core.Currency
import com.daftari.app.core.Rates
import com.daftari.app.data.ThemeMode
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.parties.MoneySheet
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 1.1.3 receipt row → build/outputs/roborazzi/v113_*.png (copied to shots_1.1.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h1000dp-xxhdpi", application = android.app.Application::class)
class Shots113Test {
    @get:Rule val compose = createComposeRule()

    @Before fun noVideo() { ClipConfig.useVideo = false }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v113_$name.png")
    }

    /** a stand-in transfer receipt photo (white slip, a few printed lines) */
    private fun receiptPhoto(): Bitmap {
        val b = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.drawColor(0xFFE9E4DA.toInt())
        val p = Paint().apply { color = 0xFFFFFFFF.toInt() }
        cv.drawRect(30f, 20f, 210f, 300f, p)
        p.color = 0xFF1E6BFF.toInt(); cv.drawRect(30f, 20f, 210f, 62f, p)
        p.color = 0xFF555555.toInt()
        for (i in 0 until 9) cv.drawRect(45f, 85f + i * 22f, if (i % 3 == 2) 140f else 195f, 93f + i * 22f, p)
        return b
    }

    @Test fun a_two_receipt_buttons() {
        shot("a_receipt_camera_gallery") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {}, onCamera = {},
                    initialMethod = "transfer", initialAmount = "10000", initialTransfer = "60412873", initialCompany = "الكريمي") { _, _, _, _, _, _, _ -> }
            }
        }
    }

    @Test fun b_receipt_attached_thumbnail() {
        val thumb = receiptPhoto().asImageBitmap()
        shot("b_receipt_attached") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MoneySheet(false, "تسديد", "محمد الصنعاني", 2_450_000, 0, Currency.YER, false, {}, rates = Rates(), onReceipt = {}, onCamera = {},
                    receiptAttached = true, receiptThumb = thumb, onRemoveReceipt = {},
                    initialMethod = "wallet", initialAmount = "3000", initialTransfer = "4512309", initialCompany = "جوالي") { _, _, _, _, _, _, _ -> }
            }
        }
    }
}
