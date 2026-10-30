package com.daftari.app.ui.parties

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.Currency
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.core.asciiDigits
import com.daftari.app.core.Phone
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Period
import com.daftari.app.domain.Role
import com.daftari.app.ui.common.Chip
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.Toggle
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import com.daftari.app.ui.theme.NotoArabic
import java.time.LocalDate

private fun amt(v: Long) = if (v == 0L) "" else Money.number(v).replace(",", "")

@Composable
fun PartyEditScreen(
    initial: Party?, role: Role, currency: Currency, onBack: () -> Unit, onSave: (Party) -> Unit, onDelete: (() -> Unit)?,
    categories: List<String> = emptyList(), pickedContact: Pair<String, String>? = null, onPickContact: (() -> Unit)? = null,
) {
    val landlord = role == Role.LANDLORD
    val base = initial ?: Party(kind = if (landlord) PartyKind.SHOP else PartyKind.CUSTOMER, name = "", period = if (landlord) Period.DAILY else Period.NONE)
    var name by remember { mutableStateOf(base.name) }
    var shop by remember { mutableStateOf(base.shopNumber) }
    var phone by remember { mutableStateOf(base.phone) }
    var activity by remember { mutableStateOf(base.activity) }
    var periodic by remember { mutableStateOf(amt(base.periodicAmount)) }
    var period by remember { mutableStateOf(base.period) }
    var opening by remember { mutableStateOf(amt(base.openingBalance)) }
    var limit by remember { mutableStateOf(amt(base.creditLimit)) }
    var units by remember { mutableStateOf(base.unitsCount.toString()) }
    var notes by remember { mutableStateOf(base.notes) }
    var category by remember { mutableStateOf(base.category) }
    androidx.compose.runtime.LaunchedEffect(pickedContact) { pickedContact?.let { (n, ph) -> if (name.isBlank()) name = n; phone = ph } }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (initial == null) (if (landlord) "محل جديد" else "زبون جديد") else "تعديل", centered = true,
            start = { IconBox(R.drawable.ic_x, onBack) },
            end = { if (onDelete != null) IconBox(R.drawable.ic_trash_2, { confirmDelete = true }, tint = Df.c.bad) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (landlord) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("رقم المحل", shop, { shop = it }, Modifier.weight(1f), "مثلاً 47", KeyboardType.Number, ltr = true)
                Field("عدد المحلات", units, { units = it.filter(Char::isDigit) }, Modifier.weight(1f), "1", KeyboardType.Number, ltr = true)
            }
            Field(if (landlord) "اسم المستأجر" else "اسم الزبون", name, { name = it }, placeholder = "الاسم الكامل", icon = R.drawable.ic_user)
            Row(verticalAlignment = Alignment.Bottom) {
                Field("رقم الجوال", phone, { phone = it }, Modifier.weight(1f), placeholder = "77xxxxxxx", keyboard = KeyboardType.Phone, ltr = true, icon = R.drawable.ic_phone)
                if (onPickContact != null) { Spacer(Modifier.width(8.dp)); IconBox(R.drawable.ic_users, onPickContact, size = 52.dp) }
            }
            if (landlord) Field("ماذا يبيع", activity, { activity = it }, placeholder = "بهارات، أقمشة، عسل…", icon = R.drawable.ic_store)
            if (landlord) {
                T("الإيجار", 11.5.sp, FontWeight.Medium, Df.c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Period.DAILY, Period.WEEKLY, Period.MONTHLY).forEach { pr -> Chip(pr.labelAr, period == pr, Modifier.weight(1f)) { period = pr } }
                }
                Field("مبلغ الإيجار (${currency.code})", periodic, { periodic = it }, placeholder = "2000", keyboard = KeyboardType.Decimal, ltr = true, icon = R.drawable.ic_banknote)
                Field("متأخرات سابقة (${currency.code})", opening, { opening = it }, placeholder = "0", keyboard = KeyboardType.Decimal, ltr = true)
            } else {
                Field("حد الدين (${currency.code}) — اختياري", limit, { limit = it }, placeholder = "مثلاً 30000", keyboard = KeyboardType.Decimal, ltr = true, icon = R.drawable.ic_shield_check)
                Field("دين سابق (${currency.code}) — اختياري", opening, { opening = it }, placeholder = "0", keyboard = KeyboardType.Decimal, ltr = true)
            }
            Field("التصنيف — اختياري", category, { category = it }, placeholder = if (landlord) "الدور الأول، الجناح الشرقي…" else "زبائن الحارة، موظفين…", icon = R.drawable.ic_list_filter)
            if (categories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { cat -> Chip(cat, category == cat) { category = cat } }
            }
            Field("ملاحظات", notes, { notes = it }, singleLine = false, placeholder = "أي شيء تريد تذكّره")
            Spacer(Modifier.height(8.dp))
        }
        PrimaryButton("حفظ", Modifier.fillMaxWidth().padding(16.dp), R.drawable.ic_check) {
            onSave(base.copy(
                category = category.trim(),
                name = name.trim(), shopNumber = shop.trim(), phone = Phone.normalize(phone) ?: phone.trim(), activity = activity.trim(),
                periodicAmount = if (landlord) Money.parse(periodic) ?: 0 else 0, period = if (landlord) period else Period.NONE,
                openingBalance = Money.parse(opening) ?: 0, creditLimit = if (!landlord) Money.parse(limit) ?: 0 else 0,
                unitsCount = units.toIntOrNull()?.coerceAtLeast(1) ?: 1, notes = notes.trim(),
            ))
        }
    }
    if (confirmDelete && onDelete != null) ConfirmBox("حذف ${base.name}؟", "سيُحذف مع جميع حركاته.", "حذف", { confirmDelete = false }) { confirmDelete = false; onDelete() }
}

@Composable
fun ConfirmBox(title: String, body: String, ok: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = Df.c.sheet,
        title = { T(title, 17.sp, FontWeight.Bold) }, text = { T(body, 14.sp, color = Df.c.muted) },
        confirmButton = { T(ok, 15.sp, FontWeight.Bold, Df.c.bad, Modifier.clickable(onClick = onOk).padding(10.dp)) },
        dismissButton = { T("إلغاء", 15.sp, FontWeight.Bold, Df.c.muted, Modifier.clickable(onClick = onDismiss).padding(10.dp)) },
    )
}

/** Bottom sheet body for «تسجيل دفعة / تسديد» and «دين جديد». */
@Composable
fun MoneySheet(
    debt: Boolean, title: String, subtitle: String, balance: Long, periodic: Long, currency: Currency, landlord: Boolean,
    onClose: () -> Unit,
    rates: com.daftari.app.core.Rates = com.daftari.app.core.Rates(),
    onFx: (com.daftari.app.core.Fx?) -> Unit = {},
    /** payment method: method, transfer company / wallet, number, sender phone */
    onMethod: (com.daftari.app.core.PayInfo) -> Unit = {},
    /** «اختيار من المعرض»; when set, the receipt row is shown (camera + gallery side by side) */
    onReceipt: (() -> Unit)? = null, receiptAttached: Boolean = false,
    /** 1.1.3: «التقاط صورة» (falls back to [onReceipt] when null), the thumbnail, and «إزالة» */
    onCamera: (() -> Unit)? = null, receiptThumb: androidx.compose.ui.graphics.ImageBitmap? = null, onRemoveReceipt: (() -> Unit)? = null,
    transferUsed: (String) -> String? = { null },
    initialCurrency: Currency? = null, initialMethod: String = "cash", initialAmount: String = "", initialTransfer: String = "", initialCompany: String = "", initialPhone: String = "",
    /** 1.1.6: a pre-fill from the voice (or «سداد كامل»): note / items and what was heard; the user still reviews and saves */
    voice: com.daftari.app.voice.VoicePrefill? = null,
    /** 1.1.6: the sentence said the other kind («سدد» in «دين جديد»): reopen the other sheet with the same pre-fill */
    onSwitchKind: ((com.daftari.app.voice.VoicePrefill) -> Unit)? = null,
    onSave: (amount: Long, date: LocalDate, note: String, extra1: String, extra2: String, due: LocalDate?, notify: Boolean) -> Unit,
) {
    val c = Df.c
    var payCur by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialCurrency ?: currency) }
    var method by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialMethod) }
    var transferNo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialTransfer) }
    // «أخرى» is chosen when the starting name is not one of the chips
    val known = initialCompany.isBlank() || initialCompany in com.daftari.app.core.PayMethods.COMPANIES || com.daftari.app.core.PayMethods.wallet(initialCompany) != null
    var transferCo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (known) initialCompany else "") }
    var otherCo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(!known) }
    var otherName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (known) "" else initialCompany) }
    var senderPhone by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialPhone) }
    var rateText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var amount by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialAmount) }
    var note by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (!debt) voice?.note.orEmpty() else "") }
    var collector by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var receipt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var items by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (debt) voice?.note.orEmpty() else "") }
    // 1.1.6 voice: what was heard, what the sentence said the entry is, and what was filled from it
    var heard by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(voice?.transcript.orEmpty()) }
    var heardKind by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(voice?.kind?.name.orEmpty()) }
    var filled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(voice?.let { voiceFilled(it, debt) }.orEmpty()) }
    var listening by remember { mutableStateOf(false) }
    val amountFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // the amount field opens the number pad at once (not when the voice already filled it: the user reviews first)
    LaunchedEffect(Unit) { if (initialAmount.isBlank() && voice == null) { kotlinx.coroutines.delay(300); runCatching { amountFocus.requestFocus(); keyboard?.show() } } }
    var daysAgo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0L) }
    var dueIn by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var notify by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val typed = Money.parse(amount) ?: 0
    val foreign = payCur != currency
    LaunchedEffect(payCur) { rateText = if (foreign) rates.rate(payCur.code, currency.code)?.let { com.daftari.app.core.Rates.plain(it) } ?: "" else "" }
    val parsed = if (foreign) com.daftari.app.core.Rates.convert(typed, rateText.asciiDigits()) else typed
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.sheet)
            .border(1.dp, c.outline2, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.outline2))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T(title, 17.sp, FontWeight.ExtraBold); T(subtitle, 12.5.sp, color = c.muted, maxLines = 1) }
            com.daftari.app.voice.MicButton({ listening = true }, 36.dp)
            Spacer(Modifier.width(8.dp))
            IconBox(R.drawable.ic_x, onClose, 36.dp)
        }
        if (listening) com.daftari.app.voice.VoiceCaptureDialog(
            if (debt) "عليه ألفين وخمسمية حق غاز" else "سدد ثلاثة آلاف",
            onText = { text ->
                listening = false
                val d = com.daftari.app.voice.VoiceEntryParser.parse(text, emptyList())
                if (d.amountText.isNotEmpty()) amount = d.amountText
                d.currency?.let { payCur = it }
                if (d.note.isNotBlank()) { if (debt) items = d.note else note = d.note }
                heard = text; heardKind = d.kind?.name.orEmpty()
                filled = voiceFilled(com.daftari.app.voice.VoicePrefill.of(d), debt)
            },
            onDismiss = { listening = false },
        )
        com.daftari.app.voice.PackStatusLine(Modifier.padding(top = 10.dp))
        if (heard.isNotBlank()) {
            val saidOther = heardKind.isNotEmpty() && (heardKind == com.daftari.app.voice.VoiceKind.DEBT.name) != debt
            val otherName = if (debt) (if (landlord) "دفعة" else "تسديد") else "دين جديد"
            com.daftari.app.voice.HeardLine(
                heard, filled,
                warning = if (saidOther) "فهمتُ من كلامك «$otherName»، وهذه نافذة «$title»." else null,
                action = if (saidOther && onSwitchKind != null && !(landlord && !debt)) "التبديل إلى «$otherName»" else null,
                onAction = onSwitchKind?.let { sw -> {
                    sw(com.daftari.app.voice.VoicePrefill(amount, payCur.takeIf { it != currency }, if (debt) items else note, heard,
                        com.daftari.app.voice.VoiceKind.entries.firstOrNull { it.name == heardKind }))
                } },
            )
        }
        Column(
            Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s2).border(1.5.dp, c.primary, RoundedCornerShape(18.dp)).padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                T("المبلغ", 12.sp, color = c.muted)
                Currency.entries.forEach { cu -> Chip(cu.code, payCur == cu) { payCur = cu } }
            }
            BasicTextField(
                amount, { amount = it }, singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, fontFamily = NotoArabic, textAlign = TextAlign.Center, textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
            )
            val after = if (debt) balance + parsed else balance - parsed
            if (foreign) {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("1 ${payCur.code} =", 12.5.sp, FontWeight.SemiBold, c.muted)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.width(110.dp).height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.outline2, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        BasicTextField(rateText, { rateText = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = NotoArabic, textAlign = TextAlign.Center, textDirection = TextDirection.Ltr),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(6.dp)); T(currency.code, 12.5.sp, FontWeight.SemiBold, c.muted)
                }
                T(if (rateText.isBlank()) "اكتب سعر الصرف" else "\u200F= \u2066${Money.number(parsed)}\u2069 ${currency.labelAr}", 15.sp, FontWeight.ExtraBold, if (Df.c.isDark) c.primaryLight else c.primary)
                T("بسعر الشراء في ${rates.current.city} · آخر تحديث \u2066${rates.current.date}\u2069", 11.sp, color = c.muted)
            }
            T("الرصيد بعدها: ${Money.format(after, currency)}", 11.5.sp, color = c.muted)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!debt && periodic > 0) Chip("${if (landlord) "يومي" else "القسط"} ${Money.number(periodic)}", false, Modifier.weight(1f)) { amount = amt(periodic) }
            if (!debt && balance > 0) Chip("الكل ${Money.number(balance)}", false, Modifier.weight(1f)) { amount = amt(balance) }
            if (!debt && balance > 0) Chip("النصف", false, Modifier.weight(1f)) { amount = amt(balance / 2) }
            if (debt) listOf(500L, 1000L, 2000L, 5000L).forEach { v -> Chip(Money.number(v * 100), false, Modifier.weight(1f)) { amount = v.toString() } }
        }
        // 1.1.6: one-tap amounts for payments too
        if (!debt) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.daftari.app.core.QuickEntry.PAY_CHIPS.forEach { v -> Chip(Money.number(v * 100), amount == v.toString(), Modifier.weight(1f)) { amount = v.toString() } }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0L to "اليوم", 1L to "أمس", 2L to "قبل يومين").forEach { (d, l) -> Chip(l, daysAgo == d, Modifier.weight(1f)) { daysAgo = d } }
        }
        if (debt) {
            Spacer(Modifier.height(12.dp))
            Field("الأغراض (اختياري)", items, { items = it }, singleLine = false, placeholder = "كيس رز، زيت، سكر…")
            Spacer(Modifier.height(10.dp))
            T("موعد السداد", 11.5.sp, FontWeight.Medium, c.muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "بلا", 7L to "أسبوع", 15L to "15 يوم", 30L to "شهر").forEach { (d, l) -> Chip(l, dueIn == d, Modifier.weight(1f)) { dueIn = d } }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            T("طريقة الدفع", 11.5.sp, FontWeight.Medium, c.muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("cash" to "نقداً", "transfer" to "حوالة", "wallet" to "محفظة", "other" to "غير ذلك").forEach { (k, l) ->
                    Chip(l, method == k, Modifier.weight(1f)) { if (method != k) { transferCo = ""; otherCo = false; otherName = "" }; method = k }
                }
            }
            val walletPay = method == "wallet"
            if (method == "transfer" || walletPay) {
                Spacer(Modifier.height(12.dp))
                T(if (walletPay) "المحفظة" else "شركة الحوالة", 11.5.sp, FontWeight.Medium, c.muted)
                Spacer(Modifier.height(6.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (walletPay) com.daftari.app.core.PayMethods.WALLETS.forEach { w ->
                        WalletChip(w.label, w.label, !otherCo && transferCo == w.label) { transferCo = w.label; otherCo = false }
                    } else com.daftari.app.core.PayMethods.COMPANIES.forEach { co ->
                        WalletChip(co, co, !otherCo && transferCo == co) { transferCo = co; otherCo = false }
                    }
                    WalletChip("أخرى", null, otherCo) { otherCo = true; transferCo = "" }
                }
                if (otherCo) {
                    Spacer(Modifier.height(8.dp))
                    Field(if (walletPay) "اسم المحفظة" else "اسم الشركة", otherName, { otherName = it }, placeholder = if (walletPay) "اكتب اسم المحفظة" else "اكتب اسم شركة الحوالة")
                }
                Spacer(Modifier.height(10.dp))
                Field(if (walletPay) "رقم العملية" else "رقم الحوالة", transferNo, { transferNo = it }, placeholder = "اختياري", keyboard = KeyboardType.Number, ltr = true, icon = R.drawable.ic_receipt)
                transferUsed(transferNo.trim())?.let { T("⚠️ $it", 11.5.sp, FontWeight.SemiBold, c.warn, Modifier.padding(top = 4.dp)) }
                if (walletPay) {
                    Spacer(Modifier.height(8.dp))
                    Field("جوال المرسل", senderPhone, { senderPhone = it }, placeholder = "اختياري", keyboard = KeyboardType.Phone, ltr = true, icon = R.drawable.ic_phone)
                }
                if (onReceipt != null) {
                    Spacer(Modifier.height(10.dp))
                    if (receiptAttached) { ReceiptPreview(receiptThumb, onRemoveReceipt); Spacer(Modifier.height(8.dp)) }
                    else T("إرفاق صورة الإيصال (اختياري)", 12.sp, FontWeight.SemiBold, c.muted, Modifier.padding(bottom = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReceiptSourceButton("التقاط صورة", R.drawable.ic_camera, Modifier.weight(1f)) { (onCamera ?: onReceipt).invoke() }
                        ReceiptSourceButton("اختيار من المعرض", R.drawable.ic_image, Modifier.weight(1f), onClick = onReceipt)
                    }
                }
            }
        }
        if (!debt && landlord) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("اسم المحصل", collector, { collector = it }, Modifier.weight(1f), "اختياري")
                Field("رقم السند", receipt, { receipt = it }, Modifier.weight(1f), "اختياري", KeyboardType.Number, ltr = true)
            }
        }
        Spacer(Modifier.height(10.dp))
        Field("", note, { note = it }, placeholder = "ملاحظة (اختياري)", icon = R.drawable.ic_pencil)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.primarySoft).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DfIcon(R.drawable.ic_si_whatsapp, 18.dp, androidx.compose.ui.graphics.Color(0xFF25D366)); Spacer(Modifier.width(10.dp))
            T(if (debt) "أرسل له تفاصيل الدين عبر واتساب" else "أرسل إيصالاً عبر واتساب", 12.5.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Toggle(notify) { notify = it }
        }
        PrimaryButton(if (debt) "حفظ الدين" else "حفظ الدفعة", Modifier.padding(top = 16.dp).fillMaxWidth(), R.drawable.ic_check,
            enabled = parsed > 0) {
            if (!debt) {
                val withRef = method == "transfer" || method == "wallet"
                onMethod(com.daftari.app.core.PayInfo(method,
                    company = if (withRef) (if (otherCo) otherName.trim() else transferCo) else "",
                    ref = if (withRef) transferNo.trim() else "",
                    phone = if (method == "wallet") senderPhone.trim() else ""))
            }
            val date = LocalDate.now().minusDays(daysAgo)
            onFx(if (foreign) com.daftari.app.core.Fx(payCur.code, typed, rateText.asciiDigits().trim()) else null)
            onSave(parsed, date, note.trim(), if (debt) items.trim() else collector.trim(), receipt.trim(), dueIn?.let { date.plusDays(it) }, notify)
        }
        Spacer(Modifier.height(4.dp))
        T(Dates.full(LocalDate.now().minusDays(daysAgo)), 11.sp, color = c.muted2, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}

@Suppress("unused") @Composable private fun keep() = GhostButton("") {}

/** 1.1.6: «عُبّئ: …» — the fields the voice filled, in words, so nothing is filled silently. */
internal fun voiceFilled(v: com.daftari.app.voice.VoicePrefill, debt: Boolean): String {
    val parts = buildList {
        if (v.amount.isNotBlank()) add("المبلغ ${Money.parse(v.amount)?.let { Money.number(it) } ?: v.amount}")
        v.currency?.let { add("العملة ${it.labelAr}") }
        if (v.note.isNotBlank()) add((if (debt) "الأغراض: " else "الملاحظة: ") + v.note)
    }
    return if (parts.isEmpty()) "لم أفهم مبلغاً؛ اكتبه أو أعد المحاولة." else "عُبّئ: " + parts.joinToString(" · ")
}
