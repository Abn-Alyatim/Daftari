package com.daftari.app.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.AppInfo
import com.daftari.app.core.Currency
import com.daftari.app.domain.Role
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.Toggle
import com.daftari.app.ui.theme.Df
import com.daftari.app.ui.theme.NotoArabic

@Composable
fun RolePicker(role: Role, onRole: (Role) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoleCard(Role.SHOP_OWNER, "صاحب محل / بقالة", "زبائن يدينون منك", R.drawable.ic_store, role == Role.SHOP_OWNER, Modifier.weight(1f)) { onRole(Role.SHOP_OWNER) }
        RoleCard(Role.LANDLORD, "صاحب سوق / مؤجر", "محلات وإيجار", R.drawable.ic_building_2, role == Role.LANDLORD, Modifier.weight(1f)) { onRole(Role.LANDLORD) }
    }
}

@Composable
private fun RoleCard(@Suppress("UNUSED_PARAMETER") r: Role, title: String, sub: String, icon: Int, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Df.c
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(if (on) c.primarySoft else c.s1)
            .border(if (on) 1.5.dp else 1.dp, if (on) c.primary else c.outline, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (on) c.primary else c.s3), contentAlignment = Alignment.Center) { DfIcon(icon, 18.dp, if (on) c.onPrimary else c.muted) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(18.dp).clip(CircleShape).border(if (on) 5.dp else 1.5.dp, if (on) c.primary else c.outline2, CircleShape).background(if (on) Color.White else Color.Transparent))
        }
        Spacer(Modifier.height(8.dp))
        T(title, 13.5.sp, FontWeight.Bold, maxLines = 1)
        T(sub, 11.5.sp, color = c.muted, maxLines = 1)
    }
}

/** 1.1.4 launch screen. FIRST_RUN: Google only. RETURNING: the app password + fingerprint. Same design (logo, title, role cards) in both. */
enum class EntryMode { FIRST_RUN, RETURNING }

@Composable
fun EntryScreen(
    mode: EntryMode, role: Role, onRole: (Role) -> Unit,
    googleReady: Boolean = true, onGoogle: () -> Unit = {}, googleError: String? = null, onWithoutGoogle: (() -> Unit)? = null,
    roleNote: String? = null, account: String = "", hasBiometric: Boolean = false, autoBiometric: Boolean = false,
    onBiometric: () -> Unit = {}, onPassword: (String) -> String? = { null }, onForgot: () -> Unit = {}, onLogoLongPress: () -> Unit = {},
) {
    val c = Df.c
    val blue = if (c.isDark) c.primaryLight else c.primary
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    if (mode == EntryMode.RETURNING && autoBiometric && hasBiometric) androidx.compose.runtime.LaunchedEffect(Unit) { onBiometric() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.radialGradient(listOf(c.primary.copy(alpha = if (c.isDark) 0.28f else 0.08f), Color.Transparent), radius = 700f)))
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(150.dp).clip(RoundedCornerShape(36.dp)).background(Color.Black).clickable(onClick = onLogoLongPress)) {
                Image(painterResource(R.drawable.logo_full), null, Modifier.fillMaxSize())
            }
            T(AppInfo.NAME, 24.sp, FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), align = TextAlign.Center)
            T("ديونك وإيجاراتك… مرتبة ومحفوظة", 12.5.sp, FontWeight.Medium, c.muted, Modifier.fillMaxWidth(), TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            RolePicker(role, onRole)
            roleNote?.let { T(it, 11.5.sp, FontWeight.Medium, blue, Modifier.fillMaxWidth().padding(top = 8.dp), TextAlign.Center, lineHeight = 18.sp) }
            Spacer(Modifier.height(22.dp))
            if (mode == EntryMode.FIRST_RUN) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (c.isDark) Color.White else c.s2).clickable(enabled = googleReady, onClick = onGoogle),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    DfIcon(R.drawable.ic_si_google, 19.dp, Color(0xFF4285F4)); Spacer(Modifier.width(8.dp))
                    T("المتابعة باستخدام Google", 15.sp, FontWeight.Bold, Color(0xFF111111))
                }
                googleError?.let { T(it, 12.sp, FontWeight.Medium, c.bad, Modifier.fillMaxWidth().padding(top = 10.dp), TextAlign.Center, lineHeight = 19.sp) }
                if (onWithoutGoogle != null) T("تعذّر الدخول بـ Google؟ تابِع بكلمة سر فقط مؤقتاً", 12.sp, FontWeight.SemiBold, c.muted,
                    Modifier.fillMaxWidth().padding(top = 12.dp).clickable(onClick = onWithoutGoogle), TextAlign.Center)
                T("حسابك في Google يحمي دفترك ونسخه الاحتياطية · بياناتك على جوالك أولاً", 11.sp, color = c.muted2, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), align = TextAlign.Center, lineHeight = 17.sp)
            } else {
                if (account.isNotBlank()) T("\u2066$account\u2069", 12.sp, FontWeight.SemiBold, c.muted, Modifier.fillMaxWidth().padding(bottom = 10.dp), TextAlign.Center, maxLines = 1)
                Field("", pw, { v -> pw = v.filter(Char::isDigit).take(12); error = null }, Modifier.fillMaxWidth(), placeholder = "كلمة السر", keyboard = KeyboardType.NumberPassword, ltr = true, icon = R.drawable.ic_key_round)
                error?.let { T(it, 12.sp, FontWeight.SemiBold, c.bad, Modifier.fillMaxWidth().padding(top = 6.dp), align = TextAlign.Center) }
                Spacer(Modifier.height(12.dp))
                PrimaryButton("فتح", Modifier.fillMaxWidth(), enabled = pw.length >= 4) { onPassword(pw)?.let { error = it; pw = "" } }
                if (hasBiometric) { Spacer(Modifier.height(10.dp)); GhostButton("البصمة", Modifier.fillMaxWidth(), R.drawable.ic_fingerprint, onClick = onBiometric) }
                T("نسيت كلمة المرور؟", 12.5.sp, FontWeight.Bold, blue, Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = onForgot), TextAlign.Center)
            }
        }
    }
}

/** After the first Google sign-in (or «نسيت كلمة المرور؟»): the app password (+ confirm) and the fingerprint offer. */
@Composable
fun SetPasswordScreen(account: String, hasBiometric: Boolean, reset: Boolean, onEnableBiometric: (onResult: (Boolean) -> Unit) -> Unit, onSave: (pw: String, biometric: Boolean) -> Unit) {
    val c = Df.c
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf(false) }
    val problem = com.daftari.app.core.AuthGate.passwordProblem(a, b)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(84.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, c.outline2, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
            DfIcon(R.drawable.ic_key_round, 34.dp, if (c.isDark) c.primaryLight else c.primary)
        }
        T(if (reset) "كلمة سر جديدة" else "أنشئ كلمة سر لدفتري", 20.sp, FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), align = TextAlign.Center)
        T("تفتح بها التطبيق في كل مرة، وتؤكّد بها الحذف والتعديل. لا تُحفظ إلا مشفّرة على جوالك.", 13.sp, color = c.muted, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), align = TextAlign.Center, lineHeight = 20.sp)
        if (account.isNotBlank()) Row(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.outline2, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            DfIcon(R.drawable.ic_si_google, 14.dp, Color(0xFF4285F4)); Spacer(Modifier.width(6.dp)); T("\u2066$account\u2069", 12.sp, FontWeight.SemiBold, c.ok, maxLines = 1)
        }
        Spacer(Modifier.height(20.dp))
        Field("كلمة السر (4 أرقام أو أكثر)", a, { a = it.filter(Char::isDigit).take(12) }, keyboard = KeyboardType.NumberPassword, ltr = true, icon = R.drawable.ic_key_round)
        Spacer(Modifier.height(12.dp))
        Field("تأكيد كلمة السر", b, { b = it.filter(Char::isDigit).take(12) }, keyboard = KeyboardType.NumberPassword, ltr = true, icon = R.drawable.ic_key_round)
        if (b.isNotEmpty() && problem != null) T(problem, 12.sp, FontWeight.SemiBold, c.bad, Modifier.padding(top = 6.dp))
        if (hasBiometric) {
            DfCard(Modifier.padding(top = 16.dp).fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfIcon(R.drawable.ic_fingerprint, 22.dp, if (c.isDark) c.primaryLight else c.primary); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { T("تفعيل البصمة", 14.sp, FontWeight.Bold); T("افتح دفتري بالبصمة، وكلمة السر احتياطاً", 11.5.sp, color = c.muted) }
                    Toggle(bio) { on -> if (on) onEnableBiometric { ok -> bio = ok } else bio = false }
                }
            }
        }
        PrimaryButton("حفظ والمتابعة", Modifier.padding(top = 20.dp).fillMaxWidth(), R.drawable.ic_check, enabled = problem == null) { onSave(a, bio) }
    }
}

@Composable
fun SetupScreen(
    role: Role, onRole: (Role) -> Unit, defaultOwner: String,
    onDone: (business: String, owner: String, shops: Int, currency: Currency, demo: Boolean) -> Unit, onImport: () -> Unit, showRole: Boolean = true,
) {
    val c = Df.c
    val landlord = role == Role.LANDLORD
    var business by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf(defaultOwner) }
    var shops by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(Currency.YER) }
    var demo by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("لنجهّز دفترك", 22.sp, FontWeight.ExtraBold)
        T("دقيقة واحدة وتبدأ. يمكن تعديل كل شيء لاحقاً من الإعدادات.", 13.sp, color = c.muted)
        if (showRole) RolePicker(role, onRole) else T("دفتر «${role.labelAr}»", 13.5.sp, FontWeight.Bold, if (c.isDark) c.primaryLight else c.primary)
        Field(if (landlord) "اسم السوق" else "اسم المحل / البقالة", business, { business = it }, placeholder = if (landlord) "مثلاً سوق الكحلاني" else "مثلاً بقالة الأمانة", icon = if (landlord) R.drawable.ic_building_2 else R.drawable.ic_store)
        Field(if (landlord) "اسم المؤجر" else "اسمك", owner, { owner = it }, placeholder = "الاسم", icon = R.drawable.ic_user)
        if (landlord) Field("عدد المحلات في السوق", shops, { shops = it.filter(Char::isDigit) }, placeholder = "مثلاً 200", keyboard = KeyboardType.Number, ltr = true)
        T("العملة", 11.5.sp, FontWeight.Medium, c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Currency.entries.forEach { cur -> Chip(cur.code, currency == cur, Modifier.weight(1f)) { currency = cur } } }
        DfCard(Modifier.fillMaxWidth(), onClick = onImport) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                DfIcon(R.drawable.ic_cloud_upload, 22.dp, if (c.isDark) c.primaryLight else c.primary); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T("لديك كشف جاهز؟ ارفع ملفاً", 14.sp, FontWeight.Bold)
                    T("Word أو Excel أو CSV أو PDF أو صورة دفتر — «مدين» يرتّبه", 11.5.sp, color = c.muted)
                }
            }
        }
        DfCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("جرّب ببيانات تجريبية", 14.sp, FontWeight.Bold)
                    T(if (landlord) "سوق فيه 27 محلاً لتجربة كل الميزات" else "بقالة فيها 9 زبائن لتجربة كل الميزات", 11.5.sp, color = c.muted)
                }
                Toggle(demo) { demo = it }
            }
        }
        PrimaryButton("ابدأ", Modifier.fillMaxWidth(), R.drawable.ic_check) { onDone(business.trim(), owner.trim(), shops.toIntOrNull() ?: 0, currency, demo) }
    }
}
