package com.daftari.app.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.BuildConfig
import com.daftari.app.R
import com.daftari.app.core.AppInfo
import com.daftari.app.core.Currency
import com.daftari.app.core.Phone
import com.daftari.app.data.AppSettings
import com.daftari.app.data.ServerInfo
import com.daftari.app.data.ThemeMode
import com.daftari.app.ui.common.Badge
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.Pill
import com.daftari.app.ui.common.SectionTitle
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.Toggle
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsActions(
    val onTheme: (ThemeMode) -> Unit = {}, val onCurrency: (Currency) -> Unit = {}, val onEditBusiness: () -> Unit = {}, val onLock: (Boolean) -> Unit = {},
    val onPassword: () -> Unit = {}, val onBackup: () -> Unit = {}, val onImport: () -> Unit = {}, val onAccount: () -> Unit = {}, val onSignOut: () -> Unit = {},
    val onWipe: () -> Unit = {},
    val onPro: () -> Unit = {}, val onAlerts: (Boolean) -> Unit = {}, val onExportDb: () -> Unit = {}, val onDrive: () -> Unit = {}, val onRestore: () -> Unit = {},
    val onReports: () -> Unit = {},
    val onLog: () -> Unit = {}, val onBackupScreen: () -> Unit = {}, val onMigrate: () -> Unit = {}, val onRates: () -> Unit = {},
    val onSwitchRole: (com.daftari.app.domain.Role) -> Unit = {}, val onDeveloper: () -> Unit = {},
)

@Composable
fun SettingsScreen(s: AppSettings, info: ServerInfo?, a: SettingsActions) {
    val c = Df.c
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        TopBar("الإعدادات")
        Column(Modifier.padding(horizontal = 16.dp)) {
            DfCard(Modifier.fillMaxWidth(), onClick = a.onAccount) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(s.ownerName.take(1).ifBlank { "؟" }, 50.dp, 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(s.ownerName.ifBlank { "بلا اسم" }, 14.sp, FontWeight.Bold, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                            com.daftari.app.core.Subs.badge(s)?.let { b -> Spacer(Modifier.width(8.dp)); com.daftari.app.ui.subs.PlanBadge(b) }
                        }
                        val line = listOf(s.googleEmail.ifBlank { if (s.phone.isNotBlank()) Phone.display(s.phone) else s.email }, s.role.labelAr).filter { it.isNotBlank() }.joinToString(" · ")
                        T(line, 11.5.sp, color = c.muted, maxLines = 1)
                    }
                    if (s.googleEmail.isNotBlank()) Pill("Google", c.ok, c.okSoft) else Pill("بلا حساب", c.muted, c.s3)
                }
            }
            SectionTitle("المظهر")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeCard("أسود وأزرق", Color.Black, Color(0xFF161A22), Brush.linearGradient(listOf(Color(0xFF1E5FD0), Color(0xFF0A1E4A))), s.theme == ThemeMode.DARK_BLUE, Modifier.weight(1f)) { a.onTheme(ThemeMode.DARK_BLUE) }
                ThemeCard("أبيض وأسود", Color.White, Color(0xFFECEEF2), Brush.linearGradient(listOf(Color(0xFF1A1A1A), Color.Black)), s.theme == ThemeMode.LIGHT, Modifier.weight(1f)) { a.onTheme(ThemeMode.LIGHT) }
            }
            SectionTitle("العملة الافتراضية للحسابات الجديدة")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Currency.entries.forEach { cur ->
                    val on = cur == s.currency
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) c.primarySoft else c.s2)
                            .border(if (on) 1.5.dp else 1.dp, if (on) c.primary else c.outline, RoundedCornerShape(14.dp)).clickable { a.onCurrency(cur) }.padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { T(cur.code, 14.sp, FontWeight.ExtraBold); T(cur.labelAr, 10.5.sp, color = c.muted) }
                }
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.outline, RoundedCornerShape(14.dp)).clickable(onClick = a.onRates).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DfIcon(R.drawable.ic_coins, 18.dp, if (c.isDark) c.primaryLight else c.text); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val r = s.rates.current
                    T("أسعار الصرف · ${r.city}", 13.sp, FontWeight.Bold)
                    T("سعودي ${r.sarBuy}/${r.sarSell} · دولار ${r.usdBuy}/${r.usdSell} · آخر تحديث \u2066${r.date}\u2069", 11.sp, color = c.muted, maxLines = 1)
                }
                DfIcon(R.drawable.ic_chevron_left, 16.dp, c.muted)
            }
            T("تغيير العملة لا يغيّر أي حساب قديم — كل حساب وكل عملية تحتفظ بعملتها.", 11.sp, color = c.muted2, modifier = Modifier.padding(top = 6.dp))
            SectionTitle(if (s.role == com.daftari.app.domain.Role.LANDLORD) "السوق" else "المحل")
            Group {
                Item(if (s.role == com.daftari.app.domain.Role.LANDLORD) R.drawable.ic_building_2 else R.drawable.ic_store, s.businessName.ifBlank { "اضغط لإضافة الاسم" },
                    listOf(s.ownerName, if (s.shopCount > 0) "${s.shopCount} محل" else "").filter { it.isNotBlank() }.joinToString(" · "), a.onEditBusiness)
                Item(R.drawable.ic_file_spreadsheet, "انتقل من تطبيق آخر", "Excel · CSV · JSON · SQLite — دون إعادة كتابة", a.onMigrate)
                Line()
                Item(R.drawable.ic_cloud_upload, "استيراد من Word أو صورة", "كشف التحصيل · PDF · صورة دفتر", a.onImport)
            }
            SectionTitle("الاشتراك")
            Group {
                Item(R.drawable.ic_badge_check, "الاشتراكات", com.daftari.app.core.Plan.statusText(s), a.onPro)
            }
            SectionTitle("التنبيهات والتقارير")
            Group {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(R.drawable.ic_bell); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { T("تنبيهات تلقائية", 13.5.sp, FontWeight.Bold); T("منبّه سقف الدين، مواعيد السداد، ملخص يومي", 11.5.sp, color = c.muted, maxLines = 1) }
                    Toggle(s.alertsEnabled, a.onAlerts)
                }
                Line()
                Item(R.drawable.ic_chart_column, "التقارير", "إجمالي، تفصيلي، شهري، تصنيفات", a.onReports)
            }
            SectionTitle("الدفاتر")
            Group {
                val other = if (s.role == com.daftari.app.domain.Role.SHOP_OWNER) com.daftari.app.domain.Role.LANDLORD else com.daftari.app.domain.Role.SHOP_OWNER
                Item(if (other == com.daftari.app.domain.Role.LANDLORD) R.drawable.ic_building_2 else R.drawable.ic_store, "التبديل إلى «${other.labelAr}»",
                    "دفتر منفصل بالحساب نفسه · المفتوح الآن: ${s.role.labelAr}") { a.onSwitchRole(other) }
            }
            SectionTitle("السجل والنسخ")
            Group {
                Item(R.drawable.ic_shield_check, "السجل", "كل إضافة وتعديل وحذف · لا يُحذف · بالبصمة والرمز", a.onLog)
                Line()
                Item(R.drawable.ic_cloud, "النسخ الاحتياطي و Google Drive", if (s.driveAccount.isNotBlank()) "يومياً الساعة ${com.daftari.app.core.BackupClock.label(s.backupMinuteOfDay)} · ${s.driveAccount}" else "نسخ يومي تلقائي · استرجاع · ملف \u2066.daftari\u2069", a.onBackupScreen)
            }
            SectionTitle("الأمان")
            Group {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(R.drawable.ic_fingerprint); Spacer(Modifier.width(12.dp))
                    T("فتح التطبيق بالبصمة", 13.5.sp, FontWeight.Bold, modifier = Modifier.weight(1f)); Toggle(s.biometricOn, a.onLock)
                }
                Line()
                Item(R.drawable.ic_key_round, if (s.hasPassword) "تغيير كلمة السر" else "ضبط كلمة سر", "للتأكيد عند التعديل من «مدين» وعند فتح التطبيق", a.onPassword)
                Line()
                val last = if (s.lastBackupAt > 0) SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(s.lastBackupAt)) else "لم يتم بعد"
                Item(R.drawable.ic_cloud, "النسخ الاحتياطي على الخادم", "آخر نسخ: $last", a.onBackup)
                Line()
                Item(R.drawable.ic_download, "مشاركة نسخة (\u2066.daftari\u2069)", "أرسلها عبر واتساب أو احفظها", a.onExportDb)
                Line()
                Item(R.drawable.ic_arrow_down_left, "استعادة من ملف", "ملف \u2066.daftari\u2069 (أو نسخة قديمة \u2066.json\u2069)", a.onRestore)
            }
            SectionTitle("الخدمات")
            Group {
                Status("الخادم", if (info != null) "متصل · ${info.version}" else "قيد التجهيز", info != null)
                Line()
                Status("مدين (الذكاء)", if (info?.aiReady == true) "جاهز" else "الخدمة قيد التجهيز", info?.aiReady == true)
            }
            SectionTitle("عن التطبيق")
            Group {
                Item(R.drawable.ic_send, "تواصل مع المطوّر عبر تيليجرام", "\u2066${AppInfo.DEV_TELEGRAM}\u2069", a.onDeveloper)
            }
            Spacer(Modifier.height(14.dp))
            Group {
                if (s.googleEmail.isNotBlank()) { Item(R.drawable.ic_log_out, "تسجيل الخروج من Google", "\u2066${s.googleEmail}\u2069 · تبقى بياناتك على الجوال", a.onSignOut); Line() }
                Item(R.drawable.ic_trash_2, "مسح كل البيانات", "يحذف كل شيء من هذا الجوال", a.onWipe)
            }
            T("${AppInfo.NAME} · ${AppInfo.NAME_EN} ${BuildConfig.VERSION_NAME}", 11.sp, color = c.muted2, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable private fun Group(content: @Composable () -> Unit) {
    val c = Df.c
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.s1).border(1.dp, c.outline, RoundedCornerShape(20.dp))) { content() }
}
@Composable private fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(Df.c.outline))
@Composable private fun IconTile(icon: Int) {
    val c = Df.c
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.s3), contentAlignment = Alignment.Center) { DfIcon(icon, 17.dp, if (c.isDark) c.primaryLight else c.text) }
}
@Composable private fun Item(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { T(title, 13.5.sp, FontWeight.Bold, maxLines = 1); if (sub.isNotBlank()) T(sub, 11.5.sp, color = Df.c.muted, maxLines = 1) }
        DfIcon(R.drawable.ic_chevron_left, 18.dp, Df.c.muted)
    }
}
@Composable private fun Status(title: String, value: String, ok: Boolean) {
    val c = Df.c
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) c.ok else c.warn)); Spacer(Modifier.width(10.dp))
        T(title, 13.5.sp, FontWeight.Bold, modifier = Modifier.weight(1f)); T(value, 12.sp, color = if (ok) c.ok else c.warn)
    }
}
@Composable private fun ThemeCard(name: String, bg: Color, card: Color, hero: Brush, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Df.c
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.s1).border(if (on) 2.dp else 1.dp, if (on) c.primary else c.outline, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Column(Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, Color(0x40808080), RoundedCornerShape(12.dp)).padding(9.dp)) {
            Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).background(hero))
            Spacer(Modifier.height(6.dp)); Box(Modifier.fillMaxWidth(0.7f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(card))
            Spacer(Modifier.height(5.dp)); Box(Modifier.fillMaxWidth(0.9f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(card))
        }
        Row(Modifier.padding(top = 8.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).border(if (on) 5.dp else 1.5.dp, if (on) c.primary else c.outline2, CircleShape).background(if (on) Color.White else Color.Transparent))
            Spacer(Modifier.width(6.dp)); T(name, 12.5.sp, FontWeight.Bold)
        }
    }
}
