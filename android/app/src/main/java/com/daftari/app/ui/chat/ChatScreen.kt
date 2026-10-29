package com.daftari.app.ui.chat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.chat.ChatMessage
import com.daftari.app.core.AppInfo
import com.daftari.app.data.ChatAction
import com.daftari.app.data.ChatTable
import com.daftari.app.domain.Role
import com.daftari.app.ui.common.DaftariClip
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.Pill
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.theme.Df
import com.daftari.app.ui.theme.NotoArabic

fun actionLabel(a: ChatAction): String = when (a.type) {
    "add_payment" -> if (a.offline) "تسجيل سداد" else "تسجيل دفعة ${a.amount.toLong()} لـ ${a.party}"
    "add_debt", "add_charge" -> if (a.offline) "تسجيل دين" else "تسجيل دين ${a.amount.toLong()} على ${a.party}"
    "add_party_add_debt" -> "زبون جديد + دين"
    "add_party_add_payment" -> "زبون جديد + سداد"
    "reminder" -> "تذكير"
    "undo" -> "تراجع عن آخر عملية"
    "import_backup" -> "استرجاع النسخة"
    "add_party" -> if (a.offline) "إضافة زبون" else "إضافة ${a.name.ifBlank { a.party }}"
    "edit_party" -> "تعديل بيانات ${a.party}"
    "delete_party" -> "حذف ${a.party}"
    "delete_entry" -> "حذف عملية"
    else -> a.type
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>, busy: Boolean, role: Role, onSend: (String) -> Unit, onConfirm: (ChatMessage) -> Unit, onCancel: (ChatMessage) -> Unit,
    onExportTable: (ChatTable) -> Unit, onClear: () -> Unit,
    onChoice: (ChatMessage, com.daftari.app.data.ChatAction) -> Unit = { _, _ -> }, onAttach: (() -> Unit)? = null,
) {
    val c = Df.c
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, busy) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + (if (busy) 1 else 0) - 1 + 1) }
    val suggestions = if (role == Role.LANDLORD) listOf("من لم يدفع اليوم؟", "أكثر المتأخرين", "ملخص اليوم", "تراجع")
    else listOf("سجّل على محمد رز وسكر بـ5000", "محمد سدّد 2000", "من الأكثر ديناً؟", "ملخص اليوم", "تراجع")
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E6BFF), Color(0xFF0A2A7A)))), contentAlignment = Alignment.Center) {
                DfIcon(R.drawable.ic_sparkles, 21.dp, Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(AppInfo.ASSISTANT, 15.5.sp, FontWeight.Bold)
                T("● يعمل دون إنترنت · يعرف بياناتك فقط", 11.5.sp, color = c.ok)
            }
            IconBox(R.drawable.ic_trash_2, onClear)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("أهلاً، أنا ${AppInfo.ASSISTANT} 👋", 17.sp, FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    T("اسألني عن حساباتك: من عليه دين، وكم حصّلت، أو اطلب جدولاً. ويمكنني الإضافة والتعديل بعد تأكيد بصمتك.", 13.sp, color = c.muted, align = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 21.sp)
                }
            }
            items(messages, key = { it.id }) { m -> Bubble(m, onConfirm, onCancel, onExportTable, onChoice) }
            if (busy) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { DaftariClip(56.dp) } }
        }
        Column(Modifier.fillMaxWidth().background(c.bg).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { s ->
                    Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.outline, RoundedCornerShape(50)).background(c.s1).clickable { onSend(s) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        T(s, 11.5.sp, FontWeight.SemiBold, c.muted)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onAttach != null) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, c.outline, RoundedCornerShape(16.dp)).clickable(onClick = onAttach), contentAlignment = Alignment.Center) {
                        DfIcon(R.drawable.ic_paperclip, 19.dp, c.muted)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).border(1.dp, c.outline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) T("اسأل ${AppInfo.ASSISTANT} عن حساباتك…", 13.sp, color = c.muted2)
                    BasicTextField(input, { input = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = NotoArabic), cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.primary).clickable(enabled = !busy) { onSend(input); input = "" }, contentAlignment = Alignment.Center) {
                    DfIcon(R.drawable.ic_send, 20.dp, c.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onConfirm: (ChatMessage) -> Unit, onCancel: (ChatMessage) -> Unit, onExportTable: (ChatTable) -> Unit,
                   onChoice: (ChatMessage, com.daftari.app.data.ChatAction) -> Unit) {
    val c = Df.c
    // RTL: user bubbles on the left (outgoing, like WhatsApp in Arabic), «مدين» on the right
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        val shape = if (m.fromUser) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape).background(if (m.fromUser) c.primary else c.s2)
                .then(if (m.fromUser) Modifier else Modifier.border(1.dp, c.outline, shape)).padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            if (m.text.isNotBlank()) T(m.text, 13.5.sp, FontWeight.Medium, if (m.fromUser) c.onPrimary else c.text, lineHeight = 22.sp)
            m.table?.let { t ->
                Spacer(Modifier.height(6.dp))
                if (t.title.isNotBlank()) T(t.title, 12.sp, FontWeight.Bold, c.muted)
                Row(Modifier.padding(vertical = 4.dp)) { t.columns.forEach { h -> T(h, 11.sp, FontWeight.Bold, c.muted, Modifier.weight(1f), maxLines = 1) } }
                t.rows.take(15).forEach { r ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                    Row(Modifier.padding(vertical = 5.dp)) { t.columns.indices.forEach { i -> T(r.getOrElse(i) { "" }, 11.5.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1) } }
                }
                if (t.rows.size > 15) T("+${t.rows.size - 15} سطر في الملف", 11.sp, color = c.muted)
                Row(Modifier.padding(top = 6.dp).clickable { onExportTable(t) }) { Pill("صدّر الجدول PDF / Word / صورة", if (c.isDark) c.primaryLight else c.primary, c.primarySoft, R.drawable.ic_file_text) }
            }
            if (m.choices.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                m.choices.forEach { ch ->
                    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.outline2, RoundedCornerShape(12.dp)).clickable { onChoice(m, ch) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        DfIcon(R.drawable.ic_user, 16.dp, if (c.isDark) c.primaryLight else c.primary); Spacer(Modifier.width(8.dp))
                        T(ch.party, 13.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        DfIcon(R.drawable.ic_chevron_left, 14.dp, c.muted)
                    }
                }
            }
            m.action?.let { a ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.s1).border(1.dp, c.outline2, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    val secure = !a.offline || a.type in setOf("undo", "import_backup")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(if (a.offline) "بطاقة تأكيد" else "عملية مقترحة", 11.5.sp, color = c.muted, modifier = Modifier.weight(1f))
                        Pill(if (a.offline) "دون إنترنت ✓" else "تحتاج إلى تأكيد", if (a.offline) c.ok else c.warn, c.warnSoft, if (a.offline) null else R.drawable.ic_lock)
                    }
                    T(actionLabel(a), 14.sp, FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                    val payLogo = a.note.takeIf { (a.method == "wallet" || a.method == "transfer") && com.daftari.app.ui.parties.walletIcon(it) != null }
                    a.lines.forEach { l ->
                        if (payLogo != null && (l.startsWith("حوالة") || l.startsWith("محفظة"))) Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            com.daftari.app.ui.parties.WalletLogo(payLogo, 18.dp); Spacer(Modifier.width(6.dp))
                            T(l, 12.5.sp, FontWeight.Medium, c.text, lineHeight = 19.sp)
                        } else T(l, 12.5.sp, FontWeight.Medium, c.text, modifier = Modifier.padding(bottom = 3.dp), lineHeight = 19.sp)
                    }
                    if (a.lines.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    when (m.actionDone) {
                        true -> T("✓ تم", 13.sp, FontWeight.Bold, c.ok)
                        false -> T("أُلغيت", 13.sp, FontWeight.Bold, c.muted)
                        null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (secure) PrimaryButton("تأكيد بالبصمة", Modifier.weight(1f), R.drawable.ic_fingerprint, height = 40.dp) { onConfirm(m) }
                            else PrimaryButton("تأكيد", Modifier.weight(1f), R.drawable.ic_check, height = 40.dp) { onConfirm(m) }
                            PrimaryButton("إلغاء", Modifier.width(80.dp), bg = c.s3, fg = c.text, height = 40.dp) { onCancel(m) }
                        }
                    }
                }
            }
        }
    }
}
