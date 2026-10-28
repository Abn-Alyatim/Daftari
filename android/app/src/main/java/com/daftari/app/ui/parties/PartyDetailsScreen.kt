package com.daftari.app.ui.parties

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.Currency
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.core.Phone
import com.daftari.app.domain.Attachment
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.PartySummary
import com.daftari.app.domain.Period
import com.daftari.app.domain.Role
import com.daftari.app.domain.Status
import com.daftari.app.ui.common.AmountText
import com.daftari.app.ui.common.Badge
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.Pill
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.Segmented
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df

data class DetailsActions(
    val onBack: () -> Unit = {}, val onEdit: () -> Unit = {}, val onCall: () -> Unit = {}, val onWhatsApp: () -> Unit = {}, val onSms: () -> Unit = {},
    val onStatement: () -> Unit = {}, val onPay: () -> Unit = {}, val onDebt: () -> Unit = {}, val onDeleteEntry: (Entry) -> Unit = {},
    val onAttach: () -> Unit = {}, val onOpenAttachment: (Attachment) -> Unit = {}, val onDeleteAttachment: (Attachment) -> Unit = {},
)

@Composable
fun PartyDetailsScreen(s: PartySummary, entries: List<Entry>, attachments: List<Attachment>, role: Role, currency: Currency, a: DetailsActions) {
    val c = Df.c
    val p = s.party
    val landlord = role == Role.LANDLORD
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            TopBar(p.title, centered = true, start = { IconBox(R.drawable.ic_arrow_right, a.onBack) }, end = { IconBox(R.drawable.ic_pencil, a.onEdit) })
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Badge(p.badge, 66.dp, 24.sp)
                Spacer(Modifier.height(10.dp))
                T(p.name, 19.sp, FontWeight.ExtraBold)
                val sub = listOf(p.activity, if (p.phone.isNotBlank()) Phone.display(p.phone) else "").filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) T(sub, 12.5.sp, FontWeight.Medium, c.muted)
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                val tone = when (s.status) { Status.LATE -> c.bad; Status.PAID -> c.ok; else -> c.warn }
                DfCard(Modifier.padding(top = 16.dp).fillMaxWidth(), border = tone.copy(alpha = 0.28f), bg = c.s1) {
                    Column(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tone.copy(alpha = 0.08f), Color.Transparent))).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        T(if (s.balance > 0) "المتبقي عليه" else "حسابه", 12.5.sp, FontWeight.Medium, c.muted)
                        if (s.balance > 0) AmountText(s.balance, currency, 30.sp, tone) else T("مسدد بالكامل ✓", 22.sp, FontWeight.ExtraBold, c.ok)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.status == Status.LATE) Pill(if (s.daysLate > 0) "متأخر ${s.daysLate} يوم" else "متأخر", c.bad, c.badSoft)
                            if (p.periodicAmount > 0 && p.period != Period.NONE) Pill("${p.period.labelAr} ${Money.format(p.periodicAmount, currency, false)}", if (c.isDark) c.primaryLight else c.primary, c.primarySoft)
                            if (p.creditLimit > 0) Pill("الحد ${Money.format(p.creditLimit, currency, false)}", if (s.balance > p.creditLimit) c.bad else c.muted, if (s.balance > p.creditLimit) c.badSoft else c.s3)
                        }
                    }
                }
                Row(Modifier.padding(top = 16.dp)) {
                    Action(R.drawable.ic_phone, "اتصال", if (c.isDark) c.primaryLight else c.primary, Modifier.weight(1f), a.onCall)
                    Action(R.drawable.ic_si_whatsapp, "واتساب", Color(0xFF25D366), Modifier.weight(1f), a.onWhatsApp)
                    Action(R.drawable.ic_message_square, "SMS", if (c.isDark) c.primaryLight else c.primary, Modifier.weight(1f), a.onSms)
                    Action(R.drawable.ic_file_text, "الكشف", if (c.isDark) c.primaryLight else c.primary, Modifier.weight(1f), a.onStatement)
                }
                Spacer(Modifier.height(18.dp))
                Segmented(listOf(if (landlord) "الدفعات" else "الحركات", "المعلومات", "المرفقات ${attachments.size}".trim()), tab) { tab = it }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    0 -> EntriesList(entries, currency, a.onDeleteEntry)
                    1 -> InfoList(s, role, currency)
                    else -> AttachmentsList(attachments, a)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Transparent, c.bg, c.bg))))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!landlord) PrimaryButton("دين جديد", Modifier.weight(1f), R.drawable.ic_plus, bg = c.s3, fg = c.text, onClick = a.onDebt)
            PrimaryButton(if (landlord) "تسجيل دفعة" else "تسديد", Modifier.weight(1f), R.drawable.ic_banknote, onClick = a.onPay)
        }
    }
}

@Composable private fun Action(icon: Int, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBox(icon, onClick, 52.dp, tint)
        Spacer(Modifier.height(6.dp))
        T(label, 11.5.sp, FontWeight.SemiBold)
    }
}

@Composable private fun EntriesList(entries: List<Entry>, currency: Currency, onDelete: (Entry) -> Unit) {
    val c = Df.c
    if (entries.isEmpty()) { T("ما في حركات بعد", 13.sp, color = c.muted, modifier = Modifier.fillMaxWidth().padding(24.dp), align = TextAlign.Center); return }
    DfCard(Modifier.fillMaxWidth()) {
        entries.take(60).forEachIndexed { i, e ->
            val pay = e.type == EntryType.PAYMENT
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (pay) c.okSoft else c.badSoft), contentAlignment = Alignment.Center) {
                    DfIcon(if (pay) R.drawable.ic_arrow_down_left else R.drawable.ic_arrow_up_right, 18.dp, if (pay) c.ok else c.bad)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(e.note.ifBlank { if (pay) "دفعة" else "دين" }, 13.5.sp, FontWeight.Bold, maxLines = 1)
                    val meta = listOf(Dates.relative(e.date), e.collector.takeIf { it.isNotBlank() }?.let { "المحصل $it" }, e.receiptNo.takeIf { it.isNotBlank() }?.let { "سند $it" },
                        e.dueDate?.let { "موعد ${Dates.short(it)}" }).filterNotNull().joinToString(" · ")
                    T(meta, 11.5.sp, color = c.muted, maxLines = 1)
                    if (e.items.isNotBlank()) T(e.items.replace("\n", "، "), 11.5.sp, color = c.muted2, maxLines = 2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T((if (pay) "+" else "−") + Money.format(e.amount, currency, false), 14.sp, FontWeight.ExtraBold, if (pay) c.ok else c.bad)
                    T("حذف", 10.5.sp, color = c.muted2, modifier = Modifier.clickable { onDelete(e) })
                }
            }
            if (i < minOf(entries.size, 60) - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        }
    }
}

@Composable private fun InfoList(s: PartySummary, role: Role, currency: Currency) {
    val c = Df.c
    val p = s.party
    val rows = buildList {
        if (role == Role.LANDLORD) { add("رقم المحل" to p.shopNumber.ifBlank { "—" }); add("عدد المحلات" to p.unitsCount.toString()); add("يبيع" to p.activity.ifBlank { "—" }) }
        add("الجوال" to (if (p.phone.isBlank()) "—" else Phone.display(p.phone)))
        if (p.periodicAmount > 0) add("المبلغ ${p.period.labelAr}" to Money.format(p.periodicAmount, currency))
        add("بداية الحساب" to Dates.full(p.startDate))
        if (p.openingBalance > 0) add("متأخرات سابقة" to Money.format(p.openingBalance, currency))
        if (p.creditLimit > 0) add("حد الدين" to Money.format(p.creditLimit, currency))
        add("إجمالي المستحق" to Money.format(s.totalDue, currency))
        add("إجمالي المدفوع" to Money.format(s.totalPaid, currency))
        add("آخر دفعة" to (s.lastPayment?.let { Dates.full(it) } ?: "—"))
        if (p.notes.isNotBlank()) add("ملاحظات" to p.notes)
    }
    DfCard(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, (k, v) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                T(k, 13.sp, color = c.muted, modifier = Modifier.width(120.dp))
                T(v, 13.5.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        }
    }
}

@Composable private fun AttachmentsList(list: List<Attachment>, a: DetailsActions) {
    val c = Df.c
    DfCard(Modifier.fillMaxWidth()) {
        list.forEach { at ->
            Row(Modifier.fillMaxWidth().clickable { a.onOpenAttachment(at) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DfIcon(if (at.mime.contains("pdf")) R.drawable.ic_file_text else R.drawable.ic_image, 20.dp, if (c.isDark) c.primaryLight else c.primary)
                Spacer(Modifier.width(12.dp))
                T(at.displayName, 13.5.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                Box(Modifier.clickable { a.onDeleteAttachment(at) }.padding(6.dp)) { DfIcon(R.drawable.ic_trash_2, 17.dp, c.muted) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = a.onAttach).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DfIcon(R.drawable.ic_paperclip, 18.dp, c.muted); Spacer(Modifier.width(10.dp))
            T("إرفاق عقد أو صورة أو PDF", 13.5.sp, FontWeight.SemiBold, c.muted)
        }
    }
}
