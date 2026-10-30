package com.daftari.app.ui.backup

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.backup.DriveFile
import com.daftari.app.backup.Manifest
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.Pill
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.Toggle
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupUi(
    val account: String, val backupMinuteOfDay: Int, val lastDrive: Long, val lastLocal: Long, val files: List<DriveFile>, val error: String?, val setupMissing: Boolean,
    val clientIdSet: Boolean, val encrypted: Boolean,
)

data class BackupActions(
    val onBack: () -> Unit = {}, val onConnect: () -> Unit = {}, val onBackupNow: () -> Unit = {}, val onTime: () -> Unit = {}, val onRefresh: () -> Unit = {},
    val onRestore: (DriveFile) -> Unit = {}, val onSaveToDrive: () -> Unit = {}, val onImportFile: () -> Unit = {}, val onPassword: () -> Unit = {},
)

private fun t(ms: Long) = if (ms <= 0) "لم يتم بعد" else SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.US).format(Date(ms))

@Composable
fun BackupScreen(u: BackupUi, a: BackupActions) {
    val c = Df.c
    val blue = if (c.isDark) c.primaryLight else c.primary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        TopBar("النسخ الاحتياطي", subtitle = "Google Drive · ملف \u2066.daftari\u2069", start = { IconBox(R.drawable.ic_arrow_right, a.onBack) })
        Column(Modifier.padding(horizontal = 16.dp)) {
            // ---------------- Google account + Drive
            DfCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).border(1.dp, c.outline2, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            DfIcon(R.drawable.ic_si_google, 21.dp, c.text)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            T("Google Drive", 15.sp, FontWeight.Bold)
                            if (u.account.isNotBlank()) T("مسجّل الدخول باسم", 11.sp, color = c.muted, maxLines = 1)
                            T(if (u.account.isNotBlank()) "\u2066${u.account}\u2069" else "غير مربوط", 12.5.sp, if (u.account.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal, if (u.account.isNotBlank()) c.ok else c.muted, maxLines = 1)
                        }
                        if (u.account.isNotBlank()) Pill("مربوط", c.ok, Color.Transparent) else Pill("مجلد مخفي", c.muted, Color.Transparent)
                    }
                    if (u.setupMissing || u.error != null) {
                        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, if (u.setupMissing) c.warn else c.bad, RoundedCornerShape(14.dp)).padding(12.dp)) {
                            DfIcon(R.drawable.ic_triangle_alert, 18.dp, if (u.setupMissing) c.warn else c.bad); Spacer(Modifier.width(8.dp))
                            T(u.error ?: com.daftari.app.backup.GoogleDrive.SETUP_AR, 12.sp, FontWeight.Medium, lineHeight = 19.sp)
                        }
                    } else if (u.account.isBlank()) {
                        T("تُحفظ النسخ في مجلد مخفي خاص بدفتري داخل حسابك في Drive، لا يراها غيرك ولا تزاحم ملفاتك.", 12.sp, color = c.muted, modifier = Modifier.padding(top = 10.dp), lineHeight = 19.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (u.account.isBlank()) PrimaryButton("ربط حساب Google", Modifier.fillMaxWidth(), R.drawable.ic_si_google, onClick = a.onConnect)
                    else {
                        PrimaryButton("نسخ احتياطي الآن", Modifier.fillMaxWidth(), R.drawable.ic_cloud_upload, onClick = a.onBackupNow)
                        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.outline2, RoundedCornerShape(14.dp)).clickable(onClick = a.onTime).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            DfIcon(R.drawable.ic_cloud, 18.dp, blue); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                T("النسخ التلقائي يومياً الساعة ${com.daftari.app.core.BackupClock.label(u.backupMinuteOfDay)}", 13.sp, FontWeight.Bold)
                                T("لا يمكن إيقافه، ويمكن تغيير وقته فقط", 11.5.sp, color = c.muted)
                                T("آخر نسخة ناجحة: ${t(u.lastDrive)}", 11.5.sp, color = c.muted)
                            }
                            T("تغيير الوقت", 12.sp, FontWeight.Bold, blue)
                        }
                    }
                }
            }
            if (u.account.isNotBlank()) {
                Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("استرجاع", 13.5.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("تحديث", 12.sp, FontWeight.SemiBold, blue, modifier = Modifier.clickable(onClick = a.onRefresh))
                }
                if (u.files.isEmpty()) T("لا توجد نسخ في Drive بعد", 12.5.sp, color = c.muted, modifier = Modifier.padding(vertical = 8.dp))
                DfCard(Modifier.fillMaxWidth()) {
                    u.files.take(30).forEachIndexed { i, f ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            DfIcon(R.drawable.ic_cloud, 18.dp, blue); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                T(t(f.createdAt), 13.sp, FontWeight.Bold)
                                T("${f.name} · ${f.size / 1024} KB", 11.sp, color = c.muted, maxLines = 1)
                            }
                            Box(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, c.outline2, RoundedCornerShape(10.dp)).clickable { a.onRestore(f) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                T("استرجاع", 12.sp, FontWeight.Bold, blue)
                            }
                        }
                    }
                }
            }
            // ---------------- works today, no setup
            Row(Modifier.padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T("دون أي إعداد", 13.5.sp, FontWeight.Bold, modifier = Modifier.weight(1f)); Pill("يعمل الآن", c.ok, Color.Transparent)
            }
            DfCard(Modifier.fillMaxWidth()) {
                Item(R.drawable.ic_cloud_upload, "حفظ نسخة في Drive", "اختر Google Drive (أو أي مكان) من قائمة الجوال", a.onSaveToDrive)
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                Item(R.drawable.ic_download, "استيراد نسخة", "ملف \u2066.daftari\u2069 من Drive أو واتساب أو الجوال", a.onImportFile)
            }
            // ---------------- encryption
            DfCard(Modifier.padding(top = 14.dp).fillMaxWidth(), border = if (u.encrypted) c.outline else c.warn) {
                Item(R.drawable.ic_lock, if (u.encrypted) "النسخ مشفّرة 🔒" else "النسخ غير مشفّرة", if (u.encrypted) "بكلمة سر النسخ · اضغط للتغيير" else "اضبط «كلمة سر النسخ» كي لا يفتحها غيرك", a.onPassword)
            }
            T("كل نسخة تحتوي على: الزبائن، والديون، والسدادات، والتذكيرات، و«السجل» كاملاً بختمه. يمكنك استرجاعها حتى بعد حذف التطبيق أو تغيير الجوال.", 11.5.sp, color = c.muted2,
                modifier = Modifier.padding(top = 14.dp), lineHeight = 18.sp)
        }
    }
}

@Composable private fun Item(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    val c = Df.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).border(1.dp, c.outline, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { DfIcon(icon, 17.dp, if (c.isDark) c.primaryLight else c.text) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { T(title, 13.5.sp, FontWeight.Bold); T(sub, 11.5.sp, color = c.muted, maxLines = 2) }
        DfIcon(R.drawable.ic_chevron_left, 16.dp, c.muted)
    }
}

/** What a picked / downloaded .daftari holds, before anything is written. */
@Composable
fun BackupPreviewSheet(fileName: String, customers: Int, debts: Int, payments: Int, log: Int, reminders: Int, created: String, warnings: List<String>, encrypted: Boolean,
                       onRestore: () -> Unit, onDismiss: () -> Unit) {
    val c = Df.c
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.75f)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(c.sheet).border(1.dp, c.outline2, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .clickable(enabled = false) {}.navigationBarsPadding().padding(18.dp)) {
            T("استرجاع نسخة", 17.sp, FontWeight.ExtraBold)
            T(fileName, 12.sp, color = c.muted, maxLines = 1)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("زبائن", customers, Modifier.weight(1f)); Stat("ديون", debts, Modifier.weight(1f)); Stat("سدادات", payments, Modifier.weight(1f)); Stat("السجل", log, Modifier.weight(1f))
            }
            T("✓ البصمة سليمة (SHA-256)" + (if (encrypted) " · مشفّرة 🔒" else "") + (if (reminders > 0) " · $reminders تذكير" else "") + " · $created", 12.sp, FontWeight.SemiBold, c.ok, modifier = Modifier.padding(top = 10.dp))
            warnings.forEach { T("⚠️ $it", 12.sp, FontWeight.SemiBold, c.warn, modifier = Modifier.padding(top = 4.dp)) }
            T("دمج دون تكرار: الموجود لا يتكرر، وتُحفظ البيانات الحالية في «السجل» قبل الاسترجاع.", 12.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp), lineHeight = 19.sp)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("استرجاع", Modifier.weight(1f), R.drawable.ic_arrow_down_left, onClick = onRestore)
                GhostButton("إلغاء", Modifier.width(90.dp), onClick = onDismiss)
            }
        }
    }
}

@Composable private fun Stat(label: String, v: Int, modifier: Modifier) {
    val c = Df.c
    Column(modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, c.outline, RoundedCornerShape(14.dp)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T(v.toString(), 18.sp, FontWeight.ExtraBold); T(label, 11.sp, color = c.muted)
    }
}

/** «كلمة سر النسخ» (set / change / open an encrypted file). */
@Composable
fun BackupPasswordDialog(title: String, sub: String, confirm: Boolean, onDone: (String) -> Unit, onClear: (() -> Unit)?, onDismiss: () -> Unit) {
    val c = Df.c
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.75f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        DfCard(Modifier.padding(22.dp).fillMaxWidth().clickable(enabled = false) {}, bg = c.bg, border = c.outline2) {
            Column(Modifier.padding(18.dp)) {
                T(title, 16.sp, FontWeight.ExtraBold)
                T(sub, 12.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp), lineHeight = 19.sp)
                Field("", p1, { p1 = it; err = null }, placeholder = "كلمة سر النسخ", keyboard = KeyboardType.Password, ltr = true, icon = R.drawable.ic_key_round)
                if (confirm) { Spacer(Modifier.height(8.dp)); Field("", p2, { p2 = it; err = null }, placeholder = "أعدها للتأكيد", keyboard = KeyboardType.Password, ltr = true, icon = R.drawable.ic_key_round) }
                err?.let { T(it, 12.sp, FontWeight.SemiBold, c.bad, Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("تم", Modifier.weight(1f)) {
                        when {
                            p1.length < 4 -> err = "٤ أحرف أو أكثر"
                            confirm && p1 != p2 -> err = "غير متطابقتين"
                            else -> onDone(p1)
                        }
                    }
                    GhostButton("إلغاء", Modifier.weight(1f), onClick = onDismiss)
                }
                if (onClear != null) T("إلغاء التشفير", 12.sp, FontWeight.SemiBold, c.muted, modifier = Modifier.padding(top = 12.dp).clickable(onClick = onClear))
            }
        }
    }
}

@Suppress("unused") private fun keep(m: Manifest?) = m to TextAlign.Center
