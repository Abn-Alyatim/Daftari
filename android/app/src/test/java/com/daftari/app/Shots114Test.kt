package com.daftari.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.daftari.app.backup.DriveFile
import com.daftari.app.data.ThemeMode
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.daftari.app.domain.Role
import com.daftari.app.ui.auth.EntryMode
import com.daftari.app.ui.auth.EntryScreen
import com.daftari.app.ui.auth.GoogleErrors
import com.daftari.app.ui.auth.SetPasswordScreen
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.backup.BackupActions
import com.daftari.app.ui.backup.BackupScreen
import com.daftari.app.ui.backup.BackupUi
import com.daftari.app.ui.common.ClipConfig
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 1.1.4 Google backup section → build/outputs/roborazzi/v114_*.png (copied to shots_1.1.4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h780dp-xxhdpi", application = android.app.Application::class)
class Shots114Test {
    @get:Rule val compose = createComposeRule()

    @Before fun noVideo() { ClipConfig.useVideo = false }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DaftariTheme(ThemeMode.DARK_BLUE) { Box(Modifier.fillMaxSize().background(Df.c.bg)) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/v114_$name.png")
    }

    @Test fun a_first_run() {
        shot("a_first_run_google_only") {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { EntryScreen(EntryMode.FIRST_RUN, Role.SHOP_OWNER, {}) }
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    GhostButton("استرجاع نسخة", Modifier.weight(1f), R.drawable.ic_arrow_down_left, height = 44.dp) {}
                    GhostButton("انتقل من تطبيق آخر", Modifier.weight(1f), R.drawable.ic_file_spreadsheet, height = 44.dp) {}
                }
            }
        }
    }

    @Test fun b_returning() {
        shot("b_returning_password_fingerprint") {
            EntryScreen(EntryMode.RETURNING, Role.SHOP_OWNER, {}, account = "alyateemmadin@gmail.com", hasBiometric = true)
        }
    }

    @Test fun b2_returning_other_role() {
        shot("b2_returning_switch_to_market") {
            EntryScreen(EntryMode.RETURNING, Role.LANDLORD, {}, account = "alyateemmadin@gmail.com", hasBiometric = true,
                roleNote = "سيُنشأ دفتر جديد منفصل لـ«${Role.LANDLORD.labelAr}» بالحساب نفسه بعد الدخول")
        }
    }

    @Test fun c_set_password() {
        shot("c_set_password") { SetPasswordScreen("alyateemmadin@gmail.com", true, false, onEnableBiometric = {}, onSave = { _, _ -> }) }
    }

    @Test fun d_signed_in() {
        val files = listOf(DriveFile("1", "Daftari-shop-2026-10-30-0200.daftari", 48_213, 1_793_320_200_000), DriveFile("2", "Daftari-shop-2026-10-29-0200.daftari", 47_920, 1_793_296_800_000))
        shot("d_google_backup") { BackupScreen(BackupUi("alyateemmadin@gmail.com", 120, 1_793_320_200_000, 0, files, null, false, true, true), BackupActions()) }
    }

    @Test fun e_developer_error() {
        shot("e_google_error_10") { BackupScreen(BackupUi("", 120, 0, 0, emptyList(), GoogleErrors.text(GoogleErrors.Kind.DEVELOPER), true, true, true), BackupActions()) }
    }
}
