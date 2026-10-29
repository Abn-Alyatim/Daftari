package com.daftari.app.ui.importer

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.domain.EntryType
import com.daftari.app.importer.Dup
import com.daftari.app.importer.Field
import com.daftari.app.importer.ImportPlan
import com.daftari.app.importer.Layout
import com.daftari.app.importer.SourceTable
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.Pill
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import com.daftari.app.ui.theme.NotoArabic

/** «انتقل من تطبيق آخر»: pick the file, check the columns, see every customer's total and the grand total, then import. */
@Composable
fun MigrateStart(onPick: () -> Unit, onBack: () -> Unit) {
    val c = Df.c
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("انتقل من تطبيق آخر", centered = true, start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        Column(Modifier.padding(16.dp)) {
            DfCard(Modifier.fillMaxWidth(), onClick = onPick) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    DfIcon(R.drawable.ic_file_spreadsheet, 34.dp, if (c.isDark) c.primaryLight else c.primary)
                    T("اختر الملف", 16.sp, FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                    T("Excel (\u2066.xlsx\u2069 و\u2066.xls\u2069) · CSV · JSON · قاعدة بيانات \u2066.db\u2069 · نسخة دفتري \u2066.daftari\u2069 · كشف Word", 12.sp, color = c.muted, align = TextAlign.Center, lineHeight = 19.sp)
                }
            }
            T("تعمل جميع الأشكال:", 13.sp, FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            listOf(
                "عملية في كل سطر" to "التاريخ، الاسم، المبلغ، دين/سداد",
                "رصيد لكل زبون" to "الاسم والمبلغ المستحق عليه",
                "تفصيل شهري" to "الاسم + أعمدة الشهور (يناير…ديسمبر) + الرصيد",
            ).forEach { (t, s) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfIcon(R.drawable.ic_check, 16.dp, c.ok); Spacer(Modifier.width(8.dp)); T(t, 13.sp, FontWeight.SemiBold); T(" · $s", 12.sp, color = c.muted)
                }
            }
            T("تظهر لك معاينة بإجمالي كل زبون والإجمالي العام قبل أي حفظ، ويُتخطّى المكرر تلقائياً. وإذا كانت الأعمدة غير مألوفة تحددها مرة واحدة ونتذكرها للتطبيق نفسه.", 12.sp, color = c.muted2, modifier = Modifier.padding(top = 12.dp), lineHeight = 19.sp)
        }
    }
}

@Composable
fun MigratePreview(
    tables: List<SourceTable>, index: Int, plan: ImportPlan, remembered: Boolean, done: String?,
    onTable: (Int) -> Unit, onField: (Int, Field) -> Unit, onLayout: (Layout) -> Unit, onConfirm: () -> Unit, onBack: () -> Unit, onFinish: () -> Unit,
) {
    val c = Df.c
    val blue = if (c.isDark) c.primaryLight else c.primary
    Column(Modifier.fillMaxSize()) {
        TopBar("معاينة الاستيراد", subtitle = plan.table.fileName + (if (plan.table.sheet.isNotBlank()) " · ${plan.table.sheet}" else ""), start = { IconBox(R.drawable.ic_arrow_right, onBack) })
        if (done != null) {
            Column(Modifier.padding(16.dp)) {
                DfCard(Modifier.fillMaxWidth(), border = c.ok) { T(done, 14.sp, FontWeight.SemiBold, modifier = Modifier.padding(16.dp), lineHeight = 24.sp) }
                PrimaryButton("تم", Modifier.padding(top = 16.dp).fillMaxWidth(), R.drawable.ic_check, onClick = onFinish)
            }
            return
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tables.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tables.forEachIndexed { i, t -> Chip("${t.sheet.ifBlank { "جدول ${i + 1}" }} (${t.rows.size})", i == index) { onTable(i) } }
                }
            }
            item {
                // totals card
                DfCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Big("زبون", plan.customers.size.toString(), Modifier.weight(1f)); Big("جديد", plan.newCustomers.toString(), Modifier.weight(1f))
                            Big("عملية", plan.toImport.size.toString(), Modifier.weight(1f)); Big("مكرر", (plan.txns.size - plan.toImport.size).toString(), Modifier.weight(1f))
                        }
                        Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(c.outline))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T("الإجمالي العام", 13.sp, FontWeight.Bold, modifier = Modifier.weight(1f)); T("\u2066" + Money.number(plan.fileNet) + "\u2069", 17.sp, FontWeight.ExtraBold)
                        }
                        plan.fileTotal?.let { ft ->
                            val ok = plan.totalMatches == true
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                DfIcon(if (ok) R.drawable.ic_circle_check_big else R.drawable.ic_triangle_alert, 16.dp, if (ok) c.ok else c.warn); Spacer(Modifier.width(6.dp))
                                T(if (ok) "يطابق إجمالي الملف ${Money.number(ft)} ✓" else "إجمالي الملف ${Money.number(ft)} — الفرق ${Money.number(ft - plan.fileNet)}", 12.5.sp, FontWeight.SemiBold, if (ok) c.ok else c.warn)
                            }
                        }
                        Row(Modifier.padding(top = 8.dp)) {
                            T("الشكل: ", 12.sp, color = c.muted)
                            Layout.entries.forEach { l -> T(l.ar, 12.sp, if (l == plan.mapping.layout) FontWeight.Bold else FontWeight.Normal, if (l == plan.mapping.layout) blue else c.muted2, Modifier.padding(end = 10.dp).clickable { onLayout(l) }) }
                        }
                        if (remembered) T("✓ استخدمنا ربط الأعمدة الذي حفظته للملف نفسه", 11.5.sp, color = c.ok, modifier = Modifier.padding(top = 4.dp))
                        plan.warnings.forEach { T("⚠️ $it", 12.sp, FontWeight.SemiBold, c.warn, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            item { T("ربط الأعمدة", 13.5.sp, FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan.table.header.forEachIndexed { i, h -> ColumnCard(h, plan.table.rows.take(3).map { it.getOrElse(i) { "" } }, plan.mapping.fields[i]) { f -> onField(i, f) } }
                }
            }
            item { T("إجمالي كل زبون", 13.5.sp, FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(plan.customers.take(200), key = { it.name }) { cu ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.outline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(cu.name, 13.5.sp, FontWeight.Bold, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            if (cu.existingId == null) Pill("جديد", blue, Color.Transparent) else Pill("موجود", c.muted, Color.Transparent)
                        }
                        T("${cu.count} عملية" + (if (cu.paid > 0) " · سدّد ${Money.number(cu.paid)}" else ""), 11.5.sp, color = c.muted)
                    }
                    T("\u2066" + Money.number(cu.net) + "\u2069", 14.sp, FontWeight.ExtraBold, if (cu.net > 0) c.bad else c.ok)
                }
            }
            item { T("العمليات", 13.5.sp, FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(plan.txns.take(60)) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (t.type == EntryType.PAYMENT) "سداد" else "دين", if (t.type == EntryType.PAYMENT) c.ok else c.bad, Color.Transparent)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        T(t.name, 12.5.sp, FontWeight.SemiBold, if (t.dup != null) c.muted2 else c.text, maxLines = 1)
                        T(listOf(Dates.numeric(t.date), t.note, t.items).filter { it.isNotBlank() }.joinToString(" · "), 11.sp, color = c.muted, maxLines = 1)
                    }
                    if (t.dup != null) { Pill(t.dup.ar, c.warn, Color.Transparent); Spacer(Modifier.width(6.dp)) }
                    T("\u2066" + Money.number(t.amount) + "\u2069", 13.sp, FontWeight.Bold, if (t.dup != null) c.muted2 else c.text)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(c.bg).padding(16.dp)) {
            PrimaryButton("استيراد ${plan.toImport.size} عملية لـ ${plan.customers.size} زبون", Modifier.fillMaxWidth(), R.drawable.ic_check, enabled = plan.toImport.isNotEmpty() && Field.NAME in plan.mapping.fields, onClick = onConfirm)
        }
    }
}

@Composable private fun Big(label: String, v: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { T(v, 18.sp, FontWeight.ExtraBold); T(label, 11.sp, color = Df.c.muted) }
}

@Composable
private fun ColumnCard(header: String, samples: List<String>, field: Field, onPick: (Field) -> Unit) {
    val c = Df.c
    var open by remember { mutableStateOf(false) }
    val on = field != Field.IGNORE
    Box {
        Column(Modifier.width(132.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, if (on) c.primary else c.outline, RoundedCornerShape(14.dp)).clickable { open = true }.padding(10.dp)) {
            T("«$header»", 12.sp, FontWeight.Bold, maxLines = 1)
            samples.filter { it.isNotBlank() }.take(2).forEach { T(it, 11.sp, color = c.muted, maxLines = 1) }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) c.primary else c.s3).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                T(field.ar, 11.5.sp, FontWeight.Bold, if (on) c.onPrimary else c.muted); Spacer(Modifier.width(4.dp)); T("▾", 10.sp, color = if (on) c.onPrimary else c.muted)
            }
        }
        DropdownMenu(open, { open = false }) {
            Field.entries.forEach { f -> DropdownMenuItem(text = { Text(f.ar, fontFamily = NotoArabic) }, onClick = { open = false; onPick(f) }) }
        }
    }
}
