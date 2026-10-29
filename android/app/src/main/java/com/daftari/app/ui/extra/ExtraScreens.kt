package com.daftari.app.ui.extra

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.Currency
import com.daftari.app.core.Money
import com.daftari.app.core.Plan
import com.daftari.app.domain.Role
import com.daftari.app.importer.ImportResult
import com.daftari.app.importer.ImportRow
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.ClipLoading
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.Pill
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.SectionTitle
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df

// ------------------------------------------------------------------ app lock

@Composable
fun LockScreen(hasBiometric: Boolean, hasPassword: Boolean, onBiometric: () -> Unit, onPassword: (String) -> String?) {
    val c = Df.c
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (hasBiometric) onBiometric() }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, c.outline2, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
            DfIcon(R.drawable.ic_lock, 34.dp, if (c.isDark) c.primaryLight else c.primary)
        }
        Spacer(Modifier.height(18.dp))
        T("دفترك مقفل", 20.sp, FontWeight.ExtraBold)
        T("افتحه بالبصمة أو كلمة السر", 13.sp, color = c.muted)
        Spacer(Modifier.height(26.dp))
        if (hasPassword) {
            Field("", pw, { pw = it; error = null }, Modifier.fillMaxWidth(), placeholder = "كلمة السر", keyboard = KeyboardType.NumberPassword, ltr = true, icon = R.drawable.ic_key_round)
            error?.let { T(it, 12.sp, FontWeight.SemiBold, c.bad, Modifier.padding(top = 6.dp), align = TextAlign.Center) }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("فتح", Modifier.fillMaxWidth()) { onPassword(pw)?.let { error = it; pw = "" } }
            Spacer(Modifier.height(10.dp))
        }
        if (hasBiometric) GhostButton("البصمة", Modifier.fillMaxWidth(), R.drawable.ic_fingerprint, onClick = onBiometric)
    }
}

/** Set / change the app password (4+ digits). */
@Composable
fun PasswordSetup(onBack: () -> Unit, onSave: (String) -> Unit) {
    val c = Df.c
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= 4 && a == b
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("كلمة سر القفل", centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        Column(Modifier.padding(16.dp)) {
            T("تُستخدم لفتح التطبيق ولتأكيد أي إضافة أو تعديل أو حذف يطلبه «مدين».", 13.sp, color = c.muted, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            Field("كلمة السر (4 أرقام أو أكثر)", a, { a = it.filter(Char::isDigit).take(12) }, keyboard = KeyboardType.NumberPassword, ltr = true)
            Spacer(Modifier.height(12.dp))
            Field("أعد كتابتها", b, { b = it.filter(Char::isDigit).take(12) }, keyboard = KeyboardType.NumberPassword, ltr = true)
            if (b.isNotEmpty() && a != b) T("غير متطابقة", 12.sp, FontWeight.SemiBold, c.bad, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            PrimaryButton("حفظ", Modifier.fillMaxWidth(), enabled = ok) { onSave(a) }
        }
    }
}

/** Password prompt used when the fingerprint is not available (chat actions, deletes). */
@Composable
fun PasswordPrompt(title: String, onDismiss: () -> Unit, onCheck: (String) -> Boolean) {
    val c = Df.c
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.7f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        DfCard(Modifier.padding(24.dp).fillMaxWidth().clickable(enabled = false) {}, bg = c.bg, border = c.outline2) {
            Column(Modifier.padding(18.dp)) {
                T(title, 16.sp, FontWeight.ExtraBold)
                Spacer(Modifier.height(12.dp))
                Field("", pw, { pw = it; err = false }, placeholder = "كلمة السر", keyboard = KeyboardType.NumberPassword, ltr = true, icon = R.drawable.ic_key_round)
                if (err) T("غير صحيحة", 12.sp, FontWeight.SemiBold, c.bad, Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("تأكيد", Modifier.weight(1f)) { if (!onCheck(pw)) err = true }
                    GhostButton("إلغاء", Modifier.weight(1f), onClick = onDismiss)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ smart import

@Composable
fun ImportScreen(
    result: ImportResult?, busy: Boolean, aiReady: Boolean, role: Role, currency: Currency,
    onBack: () -> Unit, onPick: (smart: Boolean) -> Unit, onConfirm: (List<ImportRow>) -> Unit,
) {
    val c = Df.c
    val landlord = role == Role.LANDLORD
    Column(Modifier.fillMaxSize()) {
        TopBar("رفع ملف", subtitle = "Word · Excel · CSV · PDF · صورة دفتر", centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        when {
            busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ClipLoading("جارٍ قراءة الملف وترتيب البيانات…") }
            result == null -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                DfCard(Modifier.fillMaxWidth(), onClick = { onPick(true) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        DfIcon(R.drawable.ic_sparkles, 26.dp, if (c.isDark) c.primaryLight else c.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            T("رفع ذكي بالذكاء الاصطناعي", 15.sp, FontWeight.Bold)
                            T("أي ملف حتى لو مبعثر أو صورة دفتر ورقي: نستخرج الأسماء والمبالغ والمتأخرات ونعلّم المشكوك فيه", 12.sp, color = c.muted, lineHeight = 18.sp)
                            if (!aiReady) Pill("الخدمة قيد التجهيز", c.warn, c.warnSoft)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                DfCard(Modifier.fillMaxWidth(), onClick = { onPick(false) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        DfIcon(R.drawable.ic_file_spreadsheet, 26.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            T("رفع جدول دون إنترنت", 15.sp, FontWeight.Bold)
                            T("جداول Word / Excel / CSV المرتبة (مثل حافظة التحصيل) تُقرأ على جوالك مباشرة", 12.sp, color = c.muted, lineHeight = 18.sp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                T("بعد القراءة تراجع الجدول وتعدّل أي سطر قبل الحفظ. لا يُحفظ شيء دون تأكيدك.", 12.5.sp, color = c.muted, lineHeight = 20.sp)
            }
            else -> {
                val rows = remember(result) { mutableStateListOf(*result.rows.toTypedArray()) }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("${rows.count { it.include }} من ${rows.size} سطر", 14.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        val doubt = rows.count { it.doubtful }
                        if (doubt > 0) Pill("$doubt للمراجعة", c.warn, c.warnSoft, R.drawable.ic_triangle_alert)
                    }
                    result.warnings.forEach { T("• $it", 12.sp, color = c.warn, modifier = Modifier.padding(horizontal = 16.dp)) }
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(rows) { i, r ->
                            DfCard(Modifier.fillMaxWidth(), border = if (r.doubtful) c.warn.copy(alpha = 0.6f) else c.outline, onClick = { rows[i] = r.copy(include = !r.include) }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).border(1.5.dp, if (r.include) c.primary else c.outline2, RoundedCornerShape(7.dp))
                                        .background(if (r.include) c.primary else c.bg), contentAlignment = Alignment.Center) {
                                        if (r.include) DfIcon(R.drawable.ic_check, 14.dp, c.onPrimary)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        T((if (landlord && r.shopNumber.isNotBlank()) "محل ${r.shopNumber} · " else "") + r.name, 14.sp, FontWeight.Bold, maxLines = 1)
                                        val bits = listOfNotNull(
                                            r.activity.takeIf { it.isNotBlank() }, r.phone.takeIf { it.isNotBlank() },
                                            r.amount.takeIf { it > 0 }?.let { (if (landlord) "إيجار " else "دين ") + Money.format(it, currency, false) },
                                            r.arrears.takeIf { it > 0 }?.let { "متأخرات " + Money.format(it, currency, false) },
                                        )
                                        if (bits.isNotEmpty()) T(bits.joinToString(" · "), 12.sp, color = c.muted, maxLines = 2)
                                        if (r.doubtful && r.reason.isNotBlank()) T("⚠️ ${r.reason}", 11.5.sp, FontWeight.SemiBold, c.warn)
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("حفظ ${rows.count { it.include }}", Modifier.weight(1f), R.drawable.ic_check) { onConfirm(rows.filter { it.include }) }
                        GhostButton("ملف آخر", Modifier.weight(1f)) { onPick(aiReady) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ paywall

@Composable
fun PaywallScreen(statusText: String, currency: Currency, onBack: () -> Unit, onActivate: (String) -> Unit, onContact: () -> Unit) {
    val c = Df.c
    var code by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(1) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        TopBar("دفتري برو", centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        Column(Modifier.padding(horizontal = 16.dp)) {
            T(statusText, 13.sp, FontWeight.SemiBold, if (c.isDark) c.primaryLight else c.primary)
            Spacer(Modifier.height(10.dp))
            DfCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Plan.Feature.entries.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) { DfIcon(R.drawable.ic_check, 16.dp, c.ok); Spacer(Modifier.width(8.dp)); T(f.labelAr, 13.5.sp, FontWeight.Medium) }
                    }
                }
            }
            SectionTitle("اختر المدة")
            Plan.prices.forEachIndexed { i, p ->
                val on = i == pick
                DfCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), border = if (on) c.primary else c.outline, onClick = { pick = i }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(p.labelAr, 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (i == 2) { Pill("الأوفر", c.ok, c.okSoft); Spacer(Modifier.width(8.dp)) }
                        T(Plan.priceText(p, currency), 16.sp, FontWeight.ExtraBold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            T("الدفع: حوّل المبلغ ثم أرسل الإيصال، وسيصلك كود التفعيل. (الدفع من داخل المتجر قيد التجهيز)", 12.5.sp, color = c.muted, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            GhostButton("طلب الاشتراك عبر واتساب", Modifier.fillMaxWidth(), R.drawable.ic_si_whatsapp, onClick = onContact)
            Spacer(Modifier.height(14.dp))
            Field("كود التفعيل", code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(24) }, ltr = true, icon = R.drawable.ic_badge_check)
            Spacer(Modifier.height(10.dp))
            PrimaryButton("تفعيل", Modifier.fillMaxWidth(), enabled = code.length >= 6) { onActivate(code) }
            Spacer(Modifier.height(24.dp))
            T("المجانية: حتى ${Plan.FREE_PARTIES} حساب، كشف PDF، اتصال وواتساب ورسائل، وقفل البصمة. كل حساب جديد يبدأ بتجربة برو ${Plan.TRIAL_DAYS} أيام.", 12.sp, color = c.muted2, lineHeight = 19.sp, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ------------------------------------------------------------------ reports menu

enum class ReportKind(val labelAr: String, val subAr: String, val icon: Int) {
    ALL_TOTALS("إجمالي المبالغ", "كشف كل الحسابات مع الأرصدة", R.drawable.ic_table),
    ALL_DETAILS("تفاصيل جميع المبالغ", "كل عملية دين وتسديد", R.drawable.ic_receipt),
    MONTHLY("إجمالي شهري", "الديون والتحصيل لكل شهر", R.drawable.ic_chart_column),
    CATEGORIES("إجمالي التصنيفات", "الرصيد لكل تصنيف", R.drawable.ic_list_filter),
}

@Composable
fun ReportsMenu(onBack: () -> Unit, onPick: (ReportKind) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("التقارير", centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReportKind.entries.forEach { k ->
                DfCard(Modifier.fillMaxWidth(), onClick = { onPick(k) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        DfIcon(k.icon, 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { T(k.labelAr, 15.sp, FontWeight.Bold); T(k.subAr, 12.sp, color = Df.c.muted) }
                        DfIcon(R.drawable.ic_chevron_left, 18.dp, Df.c.muted)
                    }
                }
            }
            T("كل تقرير يُصدَّر PDF أو Word أو Excel أو صورة، ويُرسل عبر واتساب.", 12.sp, color = Df.c.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Suppress("unused") private val keep = listOf<@Composable () -> Unit>({ Chip("", false) {} })
@Suppress("unused") private val kb = KeyboardOptions.Default
