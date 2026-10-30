/*
 * دفتري · voice/VoiceReview.kt
 * مراجعة ما فُهم من الصوت قبل فتح نافذة المبلغ: الزبون (موجود / هل تقصد… / زبون جديد)، النوع (دين أو سداد)، المبلغ والعملة.
 * Review step for the home-screen mic: shows what the sentence was understood as and lets the user fix the customer and
 * the kind with one tap. «متابعة» opens the normal money sheet pre-filled — the user still reviews and taps «حفظ» there.
 * Nothing is saved from this screen (a new customer is only added when the user taps that button explicitly).
 */
package com.daftari.app.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.daftari.app.core.Currency
import com.daftari.app.core.Money
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.theme.Df

private const val NEW = -1L

/**
 * [titleOf] gives an account's shown title; [landlord] = market owner (only payments are recorded from here, like the details screen).
 * [onContinue] (partyId, debt) and [onNewCustomer] (name, debt) are the only ways out besides [onRetry] / [onClose].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceReviewSheet(
    draft: VoiceDraft, landlord: Boolean, defaultCurrency: Currency, titleOf: (Long) -> String?,
    onContinue: (Long, Boolean) -> Unit, onNewCustomer: (String, Boolean) -> Unit, onPickFromList: (Boolean?) -> Unit,
    onRetry: () -> Unit, onClose: () -> Unit,
) {
    val c = Df.c
    var kind by rememberSaveable { mutableStateOf(if (landlord) VoiceKind.PAYMENT.name else draft.kind?.name.orEmpty()) }
    var picked by rememberSaveable { mutableStateOf(if (draft.customer != null && draft.customerSure) draft.customer.id else 0L) }
    val k = VoiceKind.entries.firstOrNull { it.name == kind }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.sheet)
            .border(1.dp, c.outline2, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.outline2))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("التسجيل بالصوت", 17.sp, FontWeight.ExtraBold); T("راجع ما فهمه دفتري ثم تابع", 12.5.sp, color = c.muted) }
            IconBox(R.drawable.ic_x, onClose, 36.dp)
        }
        HeardLine(draft.transcript, "")

        // ---- kind
        if (!landlord) {
            Spacer(Modifier.height(14.dp))
            T("نوع العملية", 11.5.sp, FontWeight.Medium, c.muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("دين جديد", k == VoiceKind.DEBT, Modifier.weight(1f)) { kind = VoiceKind.DEBT.name }
                Chip("تسديد", k == VoiceKind.PAYMENT, Modifier.weight(1f)) { kind = VoiceKind.PAYMENT.name }
            }
            if (draft.kind == null) T("لم يتضح من كلامك هل هو دين أم تسديد، فاختر أحدهما.", 11.5.sp, color = c.warn, modifier = Modifier.padding(top = 6.dp))
        }

        // ---- customer
        Spacer(Modifier.height(14.dp))
        T(if (landlord) "الحساب" else "الزبون", 11.5.sp, FontWeight.Medium, c.muted)
        Spacer(Modifier.height(6.dp))
        val suggested = draft.customer?.takeIf { !draft.customerSure }
        if (suggested != null) T("هل تقصد «${titleOf(suggested.id) ?: suggested.name}»؟", 12.5.sp, FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        if (draft.options.isNotEmpty()) T("وجدتُ أكثر من حساب بهذا الاسم، فاختر الصحيح:", 12.sp, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val shown = (listOfNotNull(draft.customer) + draft.options).distinctBy { it.id }
            shown.forEach { cu -> Chip(titleOf(cu.id) ?: cu.name, picked == cu.id) { picked = cu.id } }
            if (draft.newName.isNotBlank()) Chip("${if (landlord) "حساب" else "زبون"} جديد: ${draft.newName}", picked == NEW) { picked = NEW }
            Chip("اختيار من القائمة…", false) { onPickFromList(k?.let { it == VoiceKind.DEBT }) }
        }
        if (draft.customer == null && draft.options.isEmpty() && draft.newName.isBlank())
            T("لم أفهم اسم الزبون؛ اختره من القائمة.", 11.5.sp, color = c.warn, modifier = Modifier.padding(top = 6.dp))

        // ---- amount / note
        Spacer(Modifier.height(14.dp))
        val cur = draft.currency
        val amountLine = draft.amount?.let { a ->
            val minor = runCatching { a.movePointRight(2).toLong() }.getOrNull()
            if (minor == null) null else "المبلغ: ${Money.number(minor)} ${(cur ?: defaultCurrency).labelAr}" + if (cur == null) " (عملة الحساب)" else ""
        }
        T(amountLine ?: "لم أفهم المبلغ؛ ستكتبه في الخطوة التالية.", 13.sp, FontWeight.SemiBold, if (amountLine == null) c.warn else c.text)
        if (draft.note.isNotBlank()) T("ملاحظة: ${draft.note}", 12.5.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(16.dp))
        val ready = k != null && picked != 0L
        PrimaryButton(
            if (picked == NEW) "إضافة «${draft.newName}» والمتابعة" else "متابعة",
            Modifier.fillMaxWidth(), R.drawable.ic_check, enabled = ready,
        ) {
            val debt = k == VoiceKind.DEBT
            if (picked == NEW) onNewCustomer(draft.newName, debt) else onContinue(picked, debt)
        }
        Spacer(Modifier.height(8.dp))
        GhostButton("إعادة التسجيل", Modifier.fillMaxWidth(), R.drawable.ic_mic, onClick = onRetry)
    }
}
