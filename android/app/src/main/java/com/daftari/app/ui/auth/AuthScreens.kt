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
import com.daftari.app.core.Phone
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

@Composable
fun LoginScreen(
    role: Role, onRole: (Role) -> Unit, serverReady: Boolean, googleReady: Boolean,
    onSendOtp: (phone: String, channel: String) -> Unit, onGoogle: () -> Unit, onOffline: () -> Unit, onLogoLongPress: () -> Unit = {},
) {
    val c = Df.c
    var phone by remember { mutableStateOf("") }
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
            Spacer(Modifier.height(18.dp))
            T("رقم الجوال", 11.5.sp, FontWeight.Medium, c.muted)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).border(1.dp, c.outline2, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (phone.isEmpty()) T("77 123 4567", 15.sp, FontWeight.SemiBold, c.muted2)
                    BasicTextField(phone, { phone = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        textStyle = TextStyle(color = c.text, fontSize = 15.sp, fontFamily = NotoArabic, fontWeight = FontWeight.SemiBold, textDirection = TextDirection.Ltr),
                        cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.width(1.dp).height(24.dp).background(c.outline2)); Spacer(Modifier.width(10.dp))
                T("+967 🇾🇪", 14.sp, FontWeight.SemiBold, c.muted)
            }
            PrimaryButton("أرسل رمز التحقق عبر واتساب", Modifier.padding(top = 14.dp).fillMaxWidth(), R.drawable.ic_si_whatsapp, enabled = serverReady && Phone.normalize(phone) != null) { onSendOtp(phone, "whatsapp") }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                T("ليس لديك واتساب؟ ", 12.5.sp, color = c.muted)
                T("أرسله برسالة SMS", 12.5.sp, FontWeight.Bold, if (c.isDark) c.primaryLight else c.primary, Modifier.clickable(enabled = serverReady) { onSendOtp(phone, "sms") })
            }
            Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(c.outline)); T("  أو  ", 12.sp, color = c.muted2); Box(Modifier.weight(1f).height(1.dp).background(c.outline))
            }
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (c.isDark) Color.White else c.s2).clickable(enabled = googleReady, onClick = onGoogle),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                DfIcon(R.drawable.ic_si_google, 19.dp, Color(0xFF4285F4)); Spacer(Modifier.width(8.dp))
                T("المتابعة باستخدام Google", 15.sp, FontWeight.Bold, Color(0xFF111111))
            }
            if (!serverReady) T("الدخول بالحساب قيد التجهيز (الخادم لم يُنشر بعد) — يمكنك البدء الآن وبياناتك محفوظة على جوالك.", 11.5.sp, color = c.warn,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center, lineHeight = 19.sp)
            GhostButton("ابدأ دون حساب", Modifier.padding(top = 12.dp).fillMaxWidth(), R.drawable.ic_smartphone, onClick = onOffline)
            T("بياناتك محفوظة على جوالك أولاً وتعمل دون إنترنت · قفل بالبصمة", 11.sp, color = c.muted2, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), align = TextAlign.Center)
        }
    }
}

@Composable
fun OtpScreen(phone: String, channel: String, devCode: String?, onBack: () -> Unit, onVerify: (code: String, email: String) -> Unit, onResend: (String) -> Unit) {
    val c = Df.c
    var code by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        IconBox(R.drawable.ic_arrow_right, onBack)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(if (channel == "sms") c.primarySoft else Color(0x1F25D366)), contentAlignment = Alignment.Center) {
            DfIcon(if (channel == "sms") R.drawable.ic_message_square else R.drawable.ic_si_whatsapp, 30.dp, if (channel == "sms") c.primary else Color(0xFF25D366))
        }
        T("أدخل رمز التحقق", 22.sp, FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp))
        T("أرسلنا رمزاً من 6 أرقام ${if (channel == "sms") "برسالة SMS" else "عبر واتساب"} الرقم ${Phone.display(phone)}", 13.sp, color = c.muted, lineHeight = 22.sp)
        if (devCode != null) T("وضع التجربة: الرمز $devCode", 12.sp, FontWeight.Bold, c.warn)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).border(1.5.dp, c.primary, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            BasicTextField(code, { v -> code = v.filter { it.isDigit() || it in '\u0660'..'\u0669' }.take(6) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = TextStyle(color = c.text, fontSize = 26.sp, fontFamily = NotoArabic, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, letterSpacing = 10.sp, textDirection = TextDirection.Ltr),
                cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            T("لم يصل؟", 12.5.sp, color = c.muted)
            Spacer(Modifier.width(8.dp))
            T("أعد الإرسال", 12.5.sp, FontWeight.Bold, if (c.isDark) c.primaryLight else c.primary, Modifier.clickable { onResend(channel) })
            Spacer(Modifier.weight(1f))
            T(if (channel == "sms") "أرسل عبر واتساب" else "أرسل SMS بدلاً منه", 12.5.sp, FontWeight.Bold, c.muted, Modifier.clickable { onResend(if (channel == "sms") "whatsapp" else "sms") })
        }
        Spacer(Modifier.height(18.dp))
        Field("البريد الإلكتروني (اختياري للنسخ الاحتياطي)", email, { email = it }, placeholder = "name@gmail.com", keyboard = KeyboardType.Email, ltr = true, icon = R.drawable.ic_mail)
        PrimaryButton("تحقق ودخول", Modifier.padding(top = 20.dp).fillMaxWidth(), R.drawable.ic_check, enabled = code.length == 6) { onVerify(code, email) }
    }
}

@Composable
fun SetupScreen(
    role: Role, onRole: (Role) -> Unit, defaultOwner: String,
    onDone: (business: String, owner: String, shops: Int, currency: Currency, demo: Boolean) -> Unit, onImport: () -> Unit,
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
        RolePicker(role, onRole)
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
