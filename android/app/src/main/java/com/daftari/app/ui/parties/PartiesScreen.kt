package com.daftari.app.ui.parties

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.Currency
import com.daftari.app.core.Money
import com.daftari.app.core.asciiDigits
import com.daftari.app.domain.PartySummary
import com.daftari.app.domain.Role
import com.daftari.app.domain.Status
import com.daftari.app.ui.common.Badge
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.EmptyState
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.StatusPill
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import com.daftari.app.ui.theme.NotoArabic

enum class SortBy(val labelAr: String) { DEFAULT("الترتيب"), BALANCE("الأكثر عليه"), NAME("الاسم"), LATE("الأكثر تأخيراً"), RECENT("آخر دفعة") }

fun sortParties(list: List<PartySummary>, by: SortBy): List<PartySummary> = when (by) {
    SortBy.DEFAULT -> list
    SortBy.BALANCE -> list.sortedByDescending { it.balance }
    SortBy.NAME -> list.sortedBy { it.party.name }
    SortBy.LATE -> list.sortedByDescending { it.daysLate }
    SortBy.RECENT -> list.sortedByDescending { it.lastPayment?.toEpochDay() ?: Long.MIN_VALUE }
}

/** [entryText] = every entry's note/items per party, so the search also finds «رز» inside a debt's items. */
fun filterParties(list: List<PartySummary>, query: String, filter: Int, entryText: Map<Long, String> = emptyMap(), category: String = ""): List<PartySummary> {
    val q = query.trim().asciiDigits()
    return list.filter { s ->
        if (category.isNotEmpty() && s.party.category != category) return@filter false
        val okQ = q.isEmpty() || s.party.name.contains(q) || s.party.shopNumber == q || s.party.shopNumber.startsWith(q) ||
            s.party.phone.filter { it.isDigit() }.contains(q.filter { it.isDigit() }.ifEmpty { "\u0000" }) || s.party.activity.contains(q) ||
            s.party.notes.contains(q) || s.party.category.contains(q) || (entryText[s.party.id]?.contains(q) == true)
        val okF = when (filter) {
            1 -> s.status == Status.LATE
            2 -> s.status == Status.PARTIAL || s.status == Status.OWES
            3 -> s.status == Status.PAID
            else -> true
        }
        okQ && okF
    }
}

@Composable
fun PartiesScreen(
    list: List<PartySummary>, role: Role, currency: Currency, onOpen: (Long) -> Unit, onAdd: () -> Unit, onImport: () -> Unit, initialQuery: String = "",
    entryText: Map<Long, String> = emptyMap(),
) {
    val c = Df.c
    val landlord = role == Role.LANDLORD
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(SortBy.DEFAULT) }
    var category by rememberSaveable { mutableStateOf("") }
    val categories = remember(list) { list.map { it.party.category }.filter { it.isNotBlank() }.distinct().sorted() }
    val shown = remember(list, query, filter, sort, category, entryText) { sortParties(filterParties(list, query, filter, entryText, category), sort) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                role.partyLabel,
                subtitle = null,
                end = { IconBox(R.drawable.ic_cloud_upload, onImport) },
            )
            T("${list.size} ${role.partySingular} · ${list.count { it.status == Status.LATE }} متأخر", 12.5.sp, FontWeight.Medium, c.muted, Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(15.dp)).background(c.s2)
                    .border(1.dp, c.outline, RoundedCornerShape(15.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfIcon(R.drawable.ic_search, 18.dp, c.muted2); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) T(if (landlord) "ابحث برقم المحل أو اسم المستأجر" else "ابحث باسم الزبون أو رقمه", 13.sp, color = c.muted2, maxLines = 1)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = NotoArabic), cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("الكل ${list.size}", filter == 0) { filter = 0 }
                Chip("متأخر ${list.count { it.status == Status.LATE }}", filter == 1) { filter = 1 }
                Chip((if (landlord) "جزئي " else "عليه ") + list.count { it.status == Status.PARTIAL || it.status == Status.OWES }, filter == 2) { filter = 2 }
                Chip("مسدد ${list.count { it.status == Status.PAID }}", filter == 3) { filter = 3 }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DfIcon(R.drawable.ic_sliders_horizontal, 16.dp, c.muted)
                SortBy.entries.forEach { s -> Chip(s.labelAr, sort == s) { sort = s } }
                if (categories.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp)); DfIcon(R.drawable.ic_list_filter, 16.dp, c.muted)
                    Chip("كل التصنيفات", category.isEmpty()) { category = "" }
                    categories.forEach { cat -> Chip(cat, category == cat) { category = cat } }
                }
            }
            if (list.isEmpty()) {
                EmptyState(if (landlord) R.drawable.ic_store else R.drawable.ic_users, "القائمة فارغة", "أضف واحداً، أو استورد ملف Word / Excel / صورة كشف.", "إضافة", onAdd)
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 170.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(shown, key = { it.party.id }) { s -> PartyCard(s, currency, landlord) { onOpen(s.party.id) } }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 92.dp)) {
            Row(
                Modifier.height(54.dp).clip(RoundedCornerShape(18.dp)).background(c.primary).clickable(onClick = onAdd).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfIcon(R.drawable.ic_plus, 22.dp, c.onPrimary); Spacer(Modifier.width(8.dp))
                T(if (landlord) "محل جديد" else "زبون جديد", 14.sp, FontWeight.Bold, c.onPrimary)
            }
        }
    }
}

@Composable
fun PartyCard(s: PartySummary, currency: Currency, landlord: Boolean, onClick: () -> Unit) {
    @Suppress("NAME_SHADOWING") val currency = s.party.let { p -> if (p.currency.isBlank()) currency else Currency.of(p.currency) }
    val c = Df.c
    DfCard(Modifier.fillMaxWidth(), radius = 18.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(s.party.badge, 46.dp, if (landlord) 15.sp else 17.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(s.party.name, 14.sp, FontWeight.Bold, maxLines = 1)
                val sub = if (landlord) listOf(s.party.activity, if (s.party.periodicAmount > 0) "${s.party.period.labelAr} ${Money.format(s.party.periodicAmount, currency, false)}" else "").filter { it.isNotBlank() }.joinToString(" · ")
                else listOf(s.lastPayment?.let { "آخر دفعة ${com.daftari.app.core.Dates.relative(it)}" } ?: "لم يدفع بعد", if (s.party.creditLimit > 0 && s.balance >= s.party.creditLimit) "وصل الحد ⚠️" else if (s.party.category.isNotBlank()) s.party.category else "").filter { it.isNotBlank() }.joinToString(" · ")
                T(sub, 11.5.sp, color = c.muted, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                T(when { s.balance < 0 -> "له " + Money.format(-s.balance, currency, false); s.balance == 0L -> "—"; else -> Money.format(s.balance, currency, false) }, 14.5.sp, FontWeight.ExtraBold,
                    when { s.balance < 0 -> c.ok; s.status == Status.LATE -> c.bad; s.status == Status.PAID -> c.muted; else -> c.warn })
                Spacer(Modifier.height(4.dp))
                StatusPill(s.status)
            }
        }
    }
}
