package com.daftari.app.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.audit.Action
import com.daftari.app.audit.AuditRecord
import com.daftari.app.audit.Verify
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.Pill
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Bio { READY, NOT_ENROLLED, NO_HARDWARE }

/**
 * The double lock for «السجل» and every restore: fingerprint (BiometricPrompt) AND the app PIN, both, every time.
 * A phone with no sensor at all falls back to the PIN alone; a phone with a sensor but no fingerprint enrolled is asked to enroll.
 */
@Composable
fun SecureGate(
    title: String, subtitle: String, bio: Bio, bioDone: Boolean, hasPin: Boolean,
    onBiometric: () -> Unit, onEnroll: () -> Unit, onPin: (String) -> String?, onSetPin: (String) -> Unit, onCancel: () -> Unit,
    autoPrompt: Boolean = true,
    /** the persistent lockout message when the PIN is locked right now (1.1.1), else null */
    lockedNow: () -> String? = { null },
) {
    val c = Df.c
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(lockedNow()) }
    val bioOk = bioDone || bio == Bio.NO_HARDWARE
    LaunchedEffect(Unit) { if (autoPrompt && bio == Bio.READY && !bioDone) onBiometric() }
    fun submit() {
        if (!hasPin) {
            if (pin.length < 4) { err = "يجب أن يكون الرمز ٤ أرقام أو أكثر"; return }
            if (first == null) { first = pin; pin = ""; err = null; return }
            if (first != pin) { err = "الرمزان مختلفان، أعد المحاولة"; first = null; pin = ""; return }
            onSetPin(pin); pin = ""; return
        }
        lockedNow()?.let { err = it; pin = ""; return }
        onPin(pin)?.let { err = it; pin = "" }
    }
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        TopBar(title, centered = true, start = { IconBox(R.drawable.ic_arrow_right, onCancel) })
        Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, c.outline2, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                DfIcon(R.drawable.ic_shield_check, 30.dp, if (c.isDark) c.primaryLight else c.primary)
            }
            Spacer(Modifier.height(10.dp))
            T(subtitle, 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Step(1, "البصمة", when { bio == Bio.NO_HARDWARE -> "لا يوجد في جهازك حسّاس بصمة"; bioDone -> "تم ✓"; bio == Bio.NOT_ENROLLED -> "لا توجد بصمة مسجّلة في الجوال"; else -> "المس الحسّاس" }, bioOk, !bioOk) {
                if (!bioOk) Row(Modifier.padding(top = 10.dp)) {
                    if (bio == Bio.NOT_ENROLLED) PrimaryButton("سجّل بصمة في الجوال", Modifier.fillMaxWidth(), R.drawable.ic_fingerprint, height = 44.dp, onClick = onEnroll)
                    else PrimaryButton("استخدم البصمة", Modifier.fillMaxWidth(), R.drawable.ic_fingerprint, height = 44.dp, onClick = onBiometric)
                }
            }
            Spacer(Modifier.height(10.dp))
            Step(2, if (hasPin) "رمز التطبيق" else if (first == null) "اضبط رمزاً للتطبيق" else "أكّد الرمز", if (hasPin) "اكتب رمزك" else "أول مرة: اختر رمزاً من ٤ أرقام أو أكثر", false, bioOk) {
                if (bioOk) Column(Modifier.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(maxOf(4, pin.length)) { i -> Box(Modifier.size(14.dp).clip(CircleShape).then(if (i < pin.length) Modifier.background(c.primary) else Modifier.border(1.5.dp, c.outline2, CircleShape))) }
                    }
                    err?.let { T(it, 12.sp, FontWeight.SemiBold, c.bad, Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(12.dp))
                    PinPad(onDigit = { if (pin.length < 8) { pin += it; err = null } }, onBack = { pin = pin.dropLast(1) }, onOk = { submit() })
                }
            }
            Spacer(Modifier.height(14.dp))
            T("الفتح يتطلب الاثنين معاً: البصمة + الرمز", 11.5.sp, color = c.muted2, align = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String, done: Boolean, active: Boolean, content: @Composable () -> Unit) {
    val c = Df.c
    DfCard(Modifier.fillMaxWidth(), border = if (active) c.primary else c.outline) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).then(if (done) Modifier.background(c.ok) else Modifier.border(1.dp, if (active) c.primary else c.outline2, CircleShape)), contentAlignment = Alignment.Center) {
                    if (done) DfIcon(R.drawable.ic_check, 16.dp, Color.White) else T(listOf("١", "٢")[n - 1], 13.sp, FontWeight.Bold, if (active) c.text else c.muted)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { T(title, 14.sp, FontWeight.Bold, if (active || done) c.text else c.muted); T(sub, 11.5.sp, color = if (done) c.ok else c.muted) }
                if (n == 1) DfIcon(R.drawable.ic_fingerprint, 22.dp, if (done) c.ok else c.muted) else DfIcon(R.drawable.ic_key_round, 20.dp, if (active) c.text else c.muted)
            }
            content()
        }
    }
}

@Composable
fun PinPad(onDigit: (String) -> Unit, onBack: () -> Unit, onOk: () -> Unit) {
    val c = Df.c
    val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    val shape = RoundedCornerShape(16.dp)
                    Box(Modifier.size(width = 76.dp, height = 50.dp).clip(shape).then(if (k == "✓") Modifier.background(c.primary) else Modifier.border(1.dp, c.outline, shape))
                        .clickable { when (k) { "⌫" -> onBack(); "✓" -> onOk(); else -> onDigit(k) } }, contentAlignment = Alignment.Center) {
                        T(k, 19.sp, FontWeight.Bold, if (k == "✓") c.onPrimary else c.text)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the log list
private val timeFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd · HH:mm")
fun AuditRecord.timeText(): String = timeFmt.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

@Composable
fun actionColor(a: String): Color = when (a) {
    Action.CREATE -> Df.c.ok; Action.DELETE, Action.WIPE -> Df.c.bad; Action.RESTORE -> if (Df.c.isDark) Df.c.primaryLight else Df.c.primary
    else -> Df.c.warn
}

@Composable
fun LogListScreen(
    records: List<AuditRecord>, verify: Verify?, filter: Set<String>, query: String,
    onFilter: (Set<String>) -> Unit, onQuery: (String) -> Unit, onOpen: (AuditRecord) -> Unit, onExport: () -> Unit, onBack: () -> Unit,
) {
    val c = Df.c
    Column(Modifier.fillMaxSize()) {
        TopBar("السجل", subtitle = "دائم · لا يُحذف", start = { IconBox(R.drawable.ic_arrow_right, onBack) }, end = { IconBox(R.drawable.ic_share_2, onExport) })
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val ok = verify?.ok != false
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, if (ok) c.outline else c.bad, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfIcon(if (ok) R.drawable.ic_shield_check else R.drawable.ic_triangle_alert, 20.dp, if (ok) c.ok else c.bad)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T(verify?.messageAr ?: "جارٍ التحقق من السجل…", 12.5.sp, FontWeight.Bold, if (ok) c.text else c.bad)
                        T("كل عملية مختومة ومربوطة بالتي قبلها — أي حذف أو تعديل يُكشف", 11.sp, color = c.muted)
                    }
                }
            }
            item {
                Field("", query, onQuery, placeholder = "ابحث باسم الزبون أو الصنف أو رقم الحوالة…", icon = R.drawable.ic_search)
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("الكل", filter.isEmpty()) { onFilter(emptySet()) }
                    listOf(Action.CREATE, Action.UPDATE, Action.DELETE, Action.RESTORE).forEach { a ->
                        Chip(Action.labelAr(a), a in filter) { onFilter(if (a in filter) filter - a else filter + a) }
                    }
                }
            }
            if (records.isEmpty()) item { T("لا توجد عمليات بهذا البحث", 13.sp, color = c.muted, modifier = Modifier.fillMaxWidth().padding(24.dp), align = TextAlign.Center) }
            items(records, key = { it.seq }) { r -> LogRow(r) { onOpen(r) } }
        }
    }
}

@Composable
private fun LogRow(r: AuditRecord, onClick: () -> Unit) {
    val c = Df.c
    DfCard(Modifier.fillMaxWidth(), radius = 16.dp, onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).border(1.dp, actionColor(r.action), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                DfIcon(when (r.action) { Action.CREATE -> R.drawable.ic_plus; Action.DELETE, Action.WIPE -> R.drawable.ic_trash_2; Action.RESTORE -> R.drawable.ic_arrow_down_left; else -> R.drawable.ic_pencil }, 17.dp, actionColor(r.action))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(r.title, 13.sp, FontWeight.Bold, maxLines = 2, lineHeight = 19.sp)
                T("#${r.seq} · ${r.timeText()} · ${r.via}", 11.sp, color = c.muted, maxLines = 1)
            }
            if (r.restorable) Pill("استرجاع", if (c.isDark) c.primaryLight else c.primary, c.primarySoft)
        }
    }
}

// ------------------------------------------------------------------ one record
private val fieldNames = linkedMapOf(
    "name" to "الاسم", "phone" to "الجوال", "type" to "النوع", "amount" to "المبلغ", "items" to "الأغراض", "note" to "ملاحظة", "day" to "التاريخ",
    "activity" to "النشاط", "openingBalance" to "رصيد سابق", "creditLimit" to "سقف الدين", "category" to "التصنيف", "shopNumber" to "رقم المحل",
    "currency" to "العملة", "origAmount" to "المبلغ الأصلي", "rate" to "السعر", "method" to "طريقة الدفع", "transferNo" to "رقم الحوالة / العملية", "transferCo" to "الشركة / المحفظة", "senderPhone" to "جوال المرسل",
)

private fun show(k: String, v: Any?): String = when {
    v == null || v == JSONObject.NULL -> ""
    k == "amount" || k == "openingBalance" || k == "creditLimit" || k == "origAmount" -> Money.number((v as? Number)?.toLong() ?: v.toString().toLongOrNull() ?: 0)
    k == "day" -> runCatching { Dates.short(LocalDate.ofEpochDay((v as Number).toLong())) }.getOrDefault(v.toString())
    k == "type" -> if (v == "PAYMENT") "سداد" else "دين"
    k == "method" -> com.daftari.app.core.PayMethods.methodLabel(v.toString())
    else -> v.toString()
}

/** before/after as rows: (field, before, after) — party+entries snapshots show the party and the count. */
fun diffRows(r: AuditRecord): List<Triple<String, String, String>> {
    fun obj(s: String): JSONObject? = s.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { if (it.has("party")) it.getJSONObject("party") else it }
    val b = obj(r.before); val a = obj(r.after)
    return fieldNames.mapNotNull { (k, label) ->
        val bv = show(k, b?.opt(k)); val av = show(k, a?.opt(k))
        if (bv.isBlank() && av.isBlank()) null else Triple(label, bv, av)
    }
}

@Composable
fun LogDetailScreen(r: AuditRecord, restoredAt: AuditRecord?, onRestore: () -> Unit, onBack: () -> Unit) {
    val c = Df.c
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("عملية #${r.seq}", centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        Column(Modifier.padding(horizontal = 16.dp)) {
            DfCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(Action.labelAr(r.action), actionColor(r.action), Color.Transparent); Spacer(Modifier.width(8.dp))
                        T(r.timeText(), 12.sp, color = c.muted)
                    }
                    T(r.title, 15.sp, FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp), lineHeight = 23.sp)
                    T("بواسطة: ${r.actor}" + (if (r.device.isNotBlank()) " · ${r.device}" else "") + " · عن طريق: ${r.via}", 11.5.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp), lineHeight = 18.sp)
                }
            }
            val rows = diffRows(r)
            if (rows.isNotEmpty()) {
                T("قبل ← بعد", 13.sp, FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                DfCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        T("الحقل", 11.sp, FontWeight.Bold, c.muted, Modifier.weight(1f)); T("قبل", 11.sp, FontWeight.Bold, c.muted, Modifier.weight(1.3f)); T("بعد", 11.sp, FontWeight.Bold, c.muted, Modifier.weight(1.3f))
                    }
                    rows.forEach { (k, b, a) ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            T(k, 12.sp, FontWeight.SemiBold, c.muted, Modifier.weight(1f))
                            T(b.ifBlank { "—" }, 12.5.sp, FontWeight.SemiBold, if (b != a && b.isNotBlank()) c.bad else c.text, Modifier.weight(1.3f), maxLines = 3)
                            T(a.ifBlank { "—" }, 12.5.sp, FontWeight.SemiBold, if (b != a && a.isNotBlank()) c.ok else c.text, Modifier.weight(1.3f), maxLines = 3)
                        }
                    }
                }
            }
            val entriesInside = runCatching { JSONObject(r.before).optJSONArray("entries")?.length() ?: 0 }.getOrDefault(0)
            if (entriesInside > 0) T("ومعها $entriesInside عملية محفوظة كاملة", 12.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
            T("بصمة العملية: ${r.hash.take(16)}…  ← مربوطة بـ ${r.prevHash.take(10)}…", 10.5.sp, color = c.muted2, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(18.dp))
            if (r.restorable) {
                restoredAt?.let { T("استُرجعت من قبل (#${it.seq} · ${it.timeText()})", 12.sp, FontWeight.SemiBold, c.warn, modifier = Modifier.padding(bottom = 8.dp)) }
                PrimaryButton("استرجاع", Modifier.fillMaxWidth(), R.drawable.ic_arrow_down_left, onClick = onRestore)
                T("يتطلب البصمة + الرمز، والاسترجاع نفسه يُكتب في السجل", 11.sp, color = c.muted2, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** «متأكد؟» before a restore (then the double lock). */
@Composable
fun RestoreConfirm(r: AuditRecord, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Df.c
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.75f)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(c.sheet).border(1.dp, c.outline2, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .clickable(enabled = false) {}.navigationBarsPadding().padding(18.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.outline2))
            Spacer(Modifier.height(14.dp))
            T("استرجاع هذه العملية؟", 17.sp, FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            DfCard(Modifier.fillMaxWidth(), radius = 14.dp) {
                Column(Modifier.padding(12.dp)) {
                    T(r.title, 13.5.sp, FontWeight.Bold, lineHeight = 21.sp)
                    T("#${r.seq} · ${r.timeText()}", 11.sp, color = c.muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            T(when (r.action) {
                Action.DELETE -> "سيعود كما كان قبل الحذف، بالأرقام والتواريخ نفسها."
                Action.UPDATE -> "سيعود إلى البيانات القديمة (قبل التعديل)."
                else -> "سيعود كل ما كان محفوظاً في هذه العملية."
            }, 12.5.sp, color = c.muted, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("تأكيد بالبصمة والرمز", Modifier.weight(1f), R.drawable.ic_fingerprint, onClick = onConfirm)
                GhostButton("إلغاء", Modifier.width(90.dp), onClick = onDismiss)
            }
        }
    }
}

/** «كشف السجل»: date range, customer, action type, then PDF / Excel / Word / image and share. */
@Composable
fun LogExportSheet(
    count: Int, parties: List<String>, initialActions: Set<String>,
    onExport: (from: LocalDate?, to: LocalDate?, party: String, actions: Set<String>, format: String) -> Unit, onDismiss: () -> Unit,
    preview: (LocalDate?, LocalDate?, String, Set<String>) -> Int = { _, _, _, _ -> count },
) {
    val c = Df.c
    var range by remember { mutableStateOf("all") }
    var party by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf(initialActions) }
    var format by remember { mutableStateOf("PDF") }
    val today = LocalDate.now()
    val (from, to) = when (range) { "today" -> today to today; "week" -> today.minusDays(6) to today; "month" -> today.withDayOfMonth(1) to today; "last" -> today.minusMonths(1).withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }; else -> null to null }
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.75f)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(c.sheet).border(1.dp, c.outline2, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .clickable(enabled = false) {}.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp)) {
            T("كشف السجل", 17.sp, FontWeight.ExtraBold)
            T("PDF أو Excel ومشاركته عبر واتساب", 12.sp, color = c.muted)
            T("الفترة", 12.sp, FontWeight.SemiBold, c.muted, Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "الكل", "today" to "اليوم", "week" to "أسبوع", "month" to "هذا الشهر", "last" to "الشهر الماضي").forEach { (k, l) -> Chip(l, range == k) { range = k } }
            }
            T("الزبون", 12.sp, FontWeight.SemiBold, c.muted, Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("الكل", party.isBlank()) { party = "" }
                parties.take(30).forEach { p -> Chip(p, party == p) { party = p } }
            }
            T("نوع العملية", 12.sp, FontWeight.SemiBold, c.muted, Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Action.CREATE, Action.UPDATE, Action.DELETE, Action.RESTORE).forEach { a -> Chip(Action.labelAr(a), a in actions, Modifier.weight(1f)) { actions = if (a in actions) actions - a else actions + a } }
            }
            T("الصيغة", 12.sp, FontWeight.SemiBold, c.muted, Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PDF", "XLSX", "DOCX", "PNG").forEach { f -> Chip(when (f) { "XLSX" -> "Excel"; "DOCX" -> "Word"; "PNG" -> "صورة"; else -> f }, format == f, Modifier.weight(1f)) { format = f } }
            }
            val n = preview(from, to, party, actions)
            PrimaryButton("تصدير ومشاركة ($n عملية)", Modifier.padding(top = 16.dp).fillMaxWidth(), R.drawable.ic_share_2, enabled = n > 0) { onExport(from, to, party, actions, format) }
        }
    }
}
