package com.daftari.app.ui.home

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
import androidx.compose.foundation.layout.offset
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
import com.daftari.app.R
import com.daftari.app.core.Currency
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.data.Dashboard
import com.daftari.app.domain.PartySummary
import com.daftari.app.domain.Role
import com.daftari.app.domain.Status
import com.daftari.app.ui.common.AmountText
import com.daftari.app.ui.common.Badge
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.EmptyState
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.SectionTitle
import com.daftari.app.ui.common.StatusPill
import com.daftari.app.ui.common.T
import com.daftari.app.ui.theme.Df
import java.time.LocalDate

data class HomeActions(
    val onSearch: () -> Unit = {}, val onQuickPay: () -> Unit = {}, val onQuickDebt: () -> Unit = {}, val onNewParty: () -> Unit = {},
    val onStatements: () -> Unit = {}, val onChat: () -> Unit = {}, val onParty: (Long) -> Unit = {}, val onSeeAll: () -> Unit = {},
    val onRemind: (PartySummary) -> Unit = {},
)

@Composable
fun HomeScreen(d: Dashboard, role: Role, business: String, owner: String, currency: Currency, today: LocalDate, a: HomeActions) {
    val c = Df.c
    val landlord = role == Role.LANDLORD
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("أهلاً ${owner.substringBefore(' ').ifBlank { "بك" }} 👋", 12.5.sp, FontWeight.Medium, c.muted)
                T(business.ifBlank { if (landlord) "سوقي" else "محلي" }, 20.sp, FontWeight.ExtraBold, maxLines = 1)
            }
            IconBox(R.drawable.ic_search, a.onSearch)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            // hero
            Box(
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(c.bg).border(1.dp, c.outline2, RoundedCornerShape(24.dp)).padding(18.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DfIcon(R.drawable.ic_wallet, 16.dp, if (c.isDark) c.primaryLight else c.primary)
                        Spacer(Modifier.width(6.dp))
                        T(if (landlord) "إجمالي المستحق على المستأجرين" else "لك عند الزبائن", 12.5.sp, FontWeight.SemiBold, c.muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    AmountText(d.totalOwed, currency, 32.sp, c.text, c.muted)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeroPill(R.drawable.ic_building_2.takeIf { landlord } ?: R.drawable.ic_users, "${d.summaries.size} ${if (landlord) "محل" else "زبون"}")
                        HeroPill(R.drawable.ic_triangle_alert, "${d.lateCount} متأخر")
                    }
                }
            }
            // today
            DfCard(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(if (landlord) "تحصيل اليوم" else "ملخص اليوم", 14.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        T("${Dates.dayName(today)} ${Dates.short(today)}", 11.5.sp, color = c.muted)
                    }
                    if (landlord) {
                        Row(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                            AmountText(d.collectedToday, currency, 22.sp)
                            Spacer(Modifier.weight(1f))
                            T("من ${Money.format(d.expectedToday, currency, false)}", 11.5.sp, color = c.muted)
                        }
                        val frac = if (d.expectedToday > 0) (d.collectedToday.toFloat() / d.expectedToday).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(c.s3)) {
                            Box(Modifier.fillMaxWidth(frac).height(7.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(c.primary, c.primaryLight))))
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Dot(c.ok, "دفعوا ${d.paidTodayCount}")
                            Dot(c.warn, "جزئي ${d.partialCount}")
                            Dot(c.bad, "متأخر ${d.lateCount}")
                        }
                    } else {
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MiniStat("ديون جديدة", d.newDebtToday, currency, c.bad, Modifier.weight(1f))
                            MiniStat("المحصَّل", d.collectedToday, currency, c.ok, Modifier.weight(1f))
                            MiniStat("هذا الشهر", d.collectedMonth, currency, c.text, Modifier.weight(1f))
                        }
                    }
                }
            }
            // quick actions
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (landlord) {
                    Quick(R.drawable.ic_banknote, "دفعة", Modifier.weight(1f), a.onQuickPay)
                    Quick(R.drawable.ic_store, "محل جديد", Modifier.weight(1f), a.onNewParty)
                } else {
                    Quick(R.drawable.ic_plus, "دين جديد", Modifier.weight(1f), a.onQuickDebt)
                    Quick(R.drawable.ic_banknote, "تسديد", Modifier.weight(1f), a.onQuickPay)
                }
                Quick(R.drawable.ic_file_text, "كشف", Modifier.weight(1f), a.onStatements)
                Quick(R.drawable.ic_sparkles, "اسأل مدين", Modifier.weight(1f), a.onChat)
            }
            if (d.summaries.isEmpty()) {
                EmptyState(if (landlord) R.drawable.ic_store else R.drawable.ic_users, if (landlord) "لا توجد محلات بعد" else "لا يوجد زبائن بعد",
                    if (landlord) "أضف أول محل، أو استورد كشف السوق من Word أو Excel أو صورة." else "سجّل أول دين لزبون، وبعدها يُحسب كل شيء تلقائياً.",
                    if (landlord) "إضافة محل" else "إضافة زبون", a.onNewParty)
                return@Column
            }
            if (!landlord && d.dueNow.isNotEmpty()) {
                SectionTitle("مواعيد سداد حلّت")
                DfCard(Modifier.fillMaxWidth()) {
                    d.dueNow.take(4).forEachIndexed { i, (s, due) ->
                        Row(Modifier.fillMaxWidth().clickable { a.onParty(s.party.id) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(s.party.badge, 40.dp, 15.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                T(s.party.name, 14.sp, FontWeight.Bold, maxLines = 1)
                                T(if (due == today) "موعده اليوم" else "موعده كان ${Dates.short(due)}", 11.5.sp, color = c.bad)
                            }
                            Box(Modifier.clip(RoundedCornerShape(11.dp)).border(1.dp, Color(0x6625D366), RoundedCornerShape(11.dp)).clickable { a.onRemind(s) }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { DfIcon(R.drawable.ic_si_whatsapp, 14.dp, Color(0xFF25D366)); Spacer(Modifier.width(5.dp)); T("ذكّره", 12.sp, FontWeight.Bold) }
                            }
                        }
                        if (i < minOf(3, d.dueNow.size - 1)) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                    }
                }
            }
            if (!landlord && d.overLimit.isNotEmpty()) {
                DfCard(Modifier.padding(top = 12.dp).fillMaxWidth(), border = c.bad.copy(alpha = 0.35f), bg = c.badSoft) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        DfIcon(R.drawable.ic_triangle_alert, 18.dp, c.bad); Spacer(Modifier.width(10.dp))
                        T("${d.overLimit.size} زبون تجاوزوا حد الدين: " + d.overLimit.take(3).joinToString("، ") { it.party.name }, 12.5.sp, FontWeight.SemiBold, maxLines = 2)
                    }
                }
            }
            val late = d.summaries.filter { it.balance > 0 }.sortedWith(compareByDescending<PartySummary> { it.status == Status.LATE }.thenByDescending { it.balance }).take(5)
            if (late.isNotEmpty()) {
                SectionTitle(if (landlord) "الأكثر تأخراً" else "أكبر المديونين", "عرض الكل", a.onSeeAll)
                DfCard(Modifier.fillMaxWidth()) {
                    late.forEachIndexed { i, s ->
                        PartyRow(s, currency, landlord) { a.onParty(s.party.id) }
                        if (i < late.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                    }
                }
            }
        }
    }
}

@Composable
fun PartyRow(s: PartySummary, currency: Currency, landlord: Boolean, onClick: () -> Unit) {
    @Suppress("NAME_SHADOWING") val currency = s.party.let { p -> if (p.currency.isBlank()) currency else Currency.of(p.currency) }
    val c = Df.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(s.party.badge, 42.dp, if (landlord) 14.sp else 16.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(s.party.name, 14.sp, FontWeight.Bold, maxLines = 1)
            val sub = buildString {
                if (s.party.activity.isNotBlank()) append(s.party.activity).append(" · ")
                append(if (s.status == Status.LATE) (if (s.daysLate > 0) "متأخر ${s.daysLate} يوم" else "متأخر") else s.status.labelAr)
            }
            T(sub, 11.5.sp, color = if (s.status == Status.LATE) c.bad else c.muted, maxLines = 1)
        }
        T(Money.format(s.balance, currency, false), 15.sp, FontWeight.ExtraBold, if (s.status == Status.LATE) c.bad else if (s.balance > 0) c.warn else c.muted)
    }
}

@Composable private fun HeroPill(icon: Int, text: String) {
    val c = Df.c
    Row(Modifier.clip(CircleShape).border(1.dp, c.outline2, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        DfIcon(icon, 13.dp, c.muted); Spacer(Modifier.width(5.dp)); T(text, 11.sp, FontWeight.Bold, c.text)
    }
}

@Composable private fun Dot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(5.dp)); T(text, 11.5.sp, FontWeight.SemiBold)
    }
}

@Composable private fun MiniStat(label: String, v: Long, currency: Currency, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Df.c.s2).padding(10.dp)) {
        T(label, 11.sp, color = Df.c.muted)
        T(Money.format(v, currency, false), 15.sp, FontWeight.ExtraBold, color, maxLines = 1)
    }
}

@Composable private fun Quick(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Df.c
    DfCard(modifier, radius = 18.dp, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.primarySoft), contentAlignment = Alignment.Center) {
                DfIcon(icon, 19.dp, if (c.isDark) c.primaryLight else c.primary)
            }
            Spacer(Modifier.height(7.dp))
            T(label, 11.5.sp, FontWeight.Bold, maxLines = 1)
        }
    }
}

@Suppress("unused") @Composable private fun keep(s: Status) = StatusPill(s)
