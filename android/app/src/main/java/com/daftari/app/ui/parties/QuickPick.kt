/*
 * دفتري · ui/parties/QuickPick.kt
 * اختيار الزبون بسرعة عند «دين جديد / تسديد» من الرئيسية: آخر خمسة زبائن بضغطة، بحث بالاسم، وزر الصوت.
 * Quick customer picker opened from the home quick actions: the 5 most recent accounts as one-tap chips, a search box,
 * the matching accounts, and the mic. Picking one opens the normal money sheet for it. Real data only.
 */
package com.daftari.app.ui.parties

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.chat.ArabicText
import com.daftari.app.core.Currency
import com.daftari.app.core.Money
import com.daftari.app.core.cur
import com.daftari.app.domain.PartySummary
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.T
import com.daftari.app.ui.theme.Df
import com.daftari.app.voice.MicButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickPickSheet(
    title: String, summaries: List<PartySummary>, recentIds: List<Long>, currency: Currency,
    onPick: (Long) -> Unit, onVoice: () -> Unit, onNew: () -> Unit, onClose: () -> Unit,
) {
    val c = Df.c
    var q by rememberSaveable { mutableStateOf("") }
    val byId = summaries.associateBy { it.party.id }
    val recent = recentIds.mapNotNull { byId[it] }.take(5)
    val nq = ArabicText.normalize(q)
    val list = (if (nq.isBlank()) recent.ifEmpty { summaries } else summaries.filter {
        ArabicText.normalize(it.party.title).contains(nq) || ArabicText.normalize(it.party.name).contains(nq) || it.party.phone.contains(q.trim())
    }).take(8)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.sheet)
            .border(1.dp, c.outline2, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.outline2))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T(title, 17.sp, FontWeight.ExtraBold); T("اختر الزبون أو قل الاسم والمبلغ بصوتك", 12.5.sp, color = c.muted) }
            MicButton(onVoice, 36.dp)
            Spacer(Modifier.width(8.dp))
            IconBox(R.drawable.ic_x, onClose, 36.dp)
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            T("آخر من تعاملت معهم", 11.5.sp, FontWeight.Medium, c.muted)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEach { s -> Chip(s.party.title, false) { onPick(s.party.id) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        Field("", q, { q = it }, placeholder = "ابحث بالاسم أو الرقم", icon = R.drawable.ic_search)
        Spacer(Modifier.height(8.dp))
        list.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(s.party.id) }.padding(horizontal = 8.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(s.party.title, 14.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                T(Money.format(s.balance, s.party.cur(currency)), 12.5.sp, color = if (s.balance > 0) c.bad else c.muted, maxLines = 1)
            }
        }
        if (list.isEmpty()) T(if (summaries.isEmpty()) "لا توجد حسابات بعد." else "لا يوجد حساب بهذا الاسم.", 12.5.sp, color = c.muted, modifier = Modifier.padding(vertical = 10.dp))
        Spacer(Modifier.height(10.dp))
        GhostButton("زبون جديد", Modifier.fillMaxWidth(), R.drawable.ic_plus, onClick = onNew)
    }
}
