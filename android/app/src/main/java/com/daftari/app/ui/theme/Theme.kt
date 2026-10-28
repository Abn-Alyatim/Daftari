package com.daftari.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.data.ThemeMode

@Immutable
data class DfColors(
    val bg: Color, val s1: Color, val s2: Color, val s3: Color, val outline: Color, val outline2: Color,
    val primary: Color, val primaryLight: Color, val primarySoft: Color, val onPrimary: Color,
    val text: Color, val muted: Color, val muted2: Color,
    val ok: Color, val okSoft: Color, val bad: Color, val badSoft: Color, val warn: Color, val warnSoft: Color,
    val heroStart: Color, val heroEnd: Color, val sheet: Color, val isDark: Boolean,
)

/** «أسود وأزرق»: AMOLED black, Daftari blue (from the logo). */
val DarkBlue = DfColors(
    bg = Color(0xFF000000), s1 = Color(0xFF000000), s2 = Color(0xFF050505), s3 = Color(0xFF0C0C0C), outline = Color(0xFF2C2C2E), outline2 = Color(0xFF3A3A3C),
    primary = Color(0xFF1E6BFF), primaryLight = Color(0xFF6EA2FF), primarySoft = Color(0x00000000), onPrimary = Color.White,
    text = Color(0xFFEEF2F8), muted = Color(0xFF8B95A8), muted2 = Color(0xFF5E687B),
    ok = Color(0xFF34C77B), okSoft = Color(0x00000000), bad = Color(0xFFFF6464), badSoft = Color(0x00000000), warn = Color(0xFFF5B43C), warnSoft = Color(0x00000000),
    heroStart = Color(0xFF000000), heroEnd = Color(0xFF000000), sheet = Color(0xFF000000), isDark = true,
)

/** «أبيض وأسود». */
val LightMono = DfColors(
    bg = Color(0xFFFFFFFF), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFFAFAFA), s3 = Color(0xFFF2F2F2), outline = Color(0xFFD9D9D9), outline2 = Color(0xFFC4C4C4),
    primary = Color(0xFF0A0A0A), primaryLight = Color(0xFF2B2B2B), primarySoft = Color(0x00FFFFFF), onPrimary = Color.White,
    text = Color(0xFF0A0A0A), muted = Color(0xFF6B7280), muted2 = Color(0xFF9AA1AD),
    ok = Color(0xFF178A4E), okSoft = Color(0x00FFFFFF), bad = Color(0xFFD63B3B), badSoft = Color(0x00FFFFFF), warn = Color(0xFFB7791F), warnSoft = Color(0x00FFFFFF),
    heroStart = Color(0xFFFFFFFF), heroEnd = Color(0xFFFFFFFF), sheet = Color(0xFFFFFFFF), isDark = false,
)

val LocalDf = staticCompositionLocalOf { DarkBlue }

val NotoArabic = FontFamily(
    Font(R.font.noto_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.noto_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.noto_sans_arabic_medium, FontWeight.SemiBold),
    Font(R.font.noto_sans_arabic_bold, FontWeight.Bold),
    Font(R.font.noto_sans_arabic_bold, FontWeight.ExtraBold),
)

object Df {
    val c: DfColors @Composable get() = LocalDf.current
}

@Composable
fun DaftariTheme(mode: ThemeMode = ThemeMode.DARK_BLUE, content: @Composable () -> Unit) {
    val colors = if (mode == ThemeMode.LIGHT) LightMono else DarkBlue
    val base = TextStyle(fontFamily = NotoArabic, color = colors.text)
    val typography = MaterialTheme.typography.let { t ->
        t.copy(
            displayLarge = t.displayLarge.merge(base), displayMedium = t.displayMedium.merge(base), displaySmall = t.displaySmall.merge(base),
            headlineLarge = t.headlineLarge.merge(base), headlineMedium = t.headlineMedium.merge(base), headlineSmall = t.headlineSmall.merge(base),
            titleLarge = t.titleLarge.merge(base).copy(fontWeight = FontWeight.ExtraBold, fontSize = 20.sp),
            titleMedium = t.titleMedium.merge(base).copy(fontWeight = FontWeight.Bold), titleSmall = t.titleSmall.merge(base),
            bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
            labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base), labelSmall = t.labelSmall.merge(base),
        )
    }
    val scheme = if (colors.isDark) darkColorScheme(
        primary = colors.primary, onPrimary = colors.onPrimary, background = colors.bg, onBackground = colors.text,
        surface = colors.s1, onSurface = colors.text, surfaceVariant = colors.s2, onSurfaceVariant = colors.muted, outline = colors.outline2,
        surfaceContainer = colors.s2, surfaceContainerHigh = colors.s3, surfaceContainerHighest = colors.s3, surfaceContainerLow = colors.s1,
        error = colors.bad,
    ) else lightColorScheme(
        primary = colors.primary, onPrimary = colors.onPrimary, background = colors.bg, onBackground = colors.text,
        surface = colors.bg, onSurface = colors.text, surfaceVariant = colors.s2, onSurfaceVariant = colors.muted, outline = colors.outline2,
        surfaceContainer = colors.s1, surfaceContainerHigh = colors.s2, surfaceContainerHighest = colors.s3, surfaceContainerLow = colors.s1,
        error = colors.bad,
    )
    CompositionLocalProvider(LocalDf provides colors, LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
