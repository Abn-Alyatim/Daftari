package com.daftari.app.ui.subs

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.core.Subs
import com.daftari.app.data.ActivePlan
import com.daftari.app.data.Broadcast
import com.daftari.app.data.PayMethod
import com.daftari.app.data.SubConfig
import com.daftari.app.data.SubPlan
import com.daftari.app.data.SubRequestDto
import com.daftari.app.ui.common.DfCard
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.Field
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.IconBox
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.TopBar
import com.daftari.app.ui.theme.Df
import kotlinx.coroutines.delay

/** 1.1.5 «الاشتراكات»: the plan → «التالي» → حوالة / محافظ إلكترونية → the number and name to pay to → the receipt → «قيد المراجعة». */

sealed interface SubLoad {
    data object Idle : SubLoad
    data object Loading : SubLoad
    data class Ready(val config: SubConfig) : SubLoad
    data class Failed(val message: String) : SubLoad
}

enum class SubStep(val labelAr: String) { PLAN("الخطة"), METHOD("طريقة الدفع"), PAY("التحويل"), RECEIPT("الإيصال") }

data class SubFlowState(
    val step: SubStep = SubStep.PLAN,
    val planId: String = "",
    val tab: String = Subs.TRANSFER,
    val methodId: String = "",
    /** the exchange / wallet name typed under «أخرى». */
    val other: String = "",
    val txn: String = "",
    val name: String = "",
    val receiptPath: String? = null,
    val cameraPending: String? = null,
) {
    /** What still blocks «إرسال الطلب» (فصحى), or null. */
    fun problem(method: PayMethod?): String? = when {
        method == null -> "اختر طريقة الدفع"
        method.other && other.isBlank() -> if (method.kind == Subs.WALLET) "اكتب اسم المحفظة" else "اكتب اسم شركة الصرافة"
        receiptPath == null -> "أرفق صورة الإيصال"
        !Subs.isTxnValid(txn) -> "اكتب رقم العملية كما في الإيصال"
        !Subs.isFourPartName(name) -> "اكتب الاسم الرباعي كاملاً"
        else -> null
    }
}

data class SubUi(
    val load: SubLoad,
    val flow: SubFlowState,
    val plan: ActivePlan? = null,
    val pending: SubRequestDto? = null,
    val statusText: String = "",
    val receipt: ImageBitmap? = null,
    val busy: Boolean = false,
    val account: String = "",
)

data class SubActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onFlow: ((SubFlowState) -> SubFlowState) -> Unit = {},
    val onCopy: (label: String, text: String) -> Unit = { _, _ -> },
    val onCamera: () -> Unit = {},
    val onGallery: () -> Unit = {},
    val onRemoveReceipt: () -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onRefreshStatus: () -> Unit = {},
    val onActivationCode: (String) -> Unit = {},
)

/** Each method's real logo (Play listings / official accounts, see README); «أخرى» → null (neutral icon). */
@DrawableRes fun methodLogo(m: PayMethod): Int? = if (m.other) null else when (m.id) {
    "kuraimi" -> R.drawable.transfer_kuraimi
    "hazmi" -> R.drawable.transfer_hazmi
    "najm" -> R.drawable.transfer_najm
    "jaib" -> R.drawable.wallet_jaib
    "jawali" -> R.drawable.wallet_jawali
    "onecash" -> R.drawable.wallet_onecash
    "floosak" -> R.drawable.wallet_floosak
    "hasib" -> R.drawable.wallet_hasib
    else -> com.daftari.app.ui.parties.walletIcon(m.label)
}

@Composable
fun SubscriptionFlow(ui: SubUi, a: SubActions) {
    val c = Df.c
    val f = ui.flow
    val back: () -> Unit = {
        if (ui.pending == null && f.step != SubStep.PLAN) a.onFlow { it.copy(step = SubStep.entries[it.step.ordinal - 1]) } else a.onBack()
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("الاشتراكات", subtitle = ui.statusText.ifBlank { null }, centered = true, start = { IconBox(R.drawable.ic_arrow_right, back) })
        when (val l = ui.load) {
            SubLoad.Idle, SubLoad.Loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { com.daftari.app.ui.common.ClipLoading("جارٍ تحميل الخطط…", 110.dp) }
            is SubLoad.Failed -> Unavailable(l.message, a.onRetry)
            is SubLoad.Ready -> {
                if (ui.pending != null) PendingView(ui.pending, ui.busy, a.onRefreshStatus)
                else {
                    StepDots(f.step)
                    AnimatedContent(f.step, Modifier.weight(1f), label = "step", transitionSpec = {
                        val fwd = targetState.ordinal > initialState.ordinal
                        // RTL: the next step comes in from the left
                        (slideInHorizontally(tween(260)) { w -> if (fwd) -w / 3 else w / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(220)) { w -> if (fwd) w / 3 else -w / 3 } + fadeOut(tween(160)))
                    }) { step ->
                        when (step) {
                            SubStep.PLAN -> PlanStep(l.config, f, ui.plan, a)
                            SubStep.METHOD -> MethodStep(l.config, f, a)
                            SubStep.PAY -> PayStep(l.config, f, a)
                            SubStep.RECEIPT -> ReceiptStep(l.config, f, ui, a)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: SubStep) {
    val c = Df.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SubStep.entries.forEach { s ->
            val on = s.ordinal <= step.ordinal
            val col by animateColorAsState(if (on) c.primary else c.outline2, tween(300), label = "dot")
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(col))
                Spacer(Modifier.height(5.dp))
                T(s.labelAr, 10.5.sp, if (s == step) FontWeight.Bold else FontWeight.Medium, if (s == step) (if (c.isDark) c.primaryLight else c.primary) else c.muted2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StepColumn(bottom: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { content(); Spacer(Modifier.height(16.dp)) }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { bottom() }
    }
}

// ------------------------------------------------------------------ 1) the plan

@Composable
private fun PlanStep(cfg: SubConfig, f: SubFlowState, current: ActivePlan?, a: SubActions) {
    val c = Df.c
    val accent = if (c.isDark) c.primaryLight else c.primary
    var codeOpen by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    StepColumn(bottom = { PrimaryButton("التالي", Modifier.fillMaxWidth(), R.drawable.ic_chevron_left, enabled = cfg.plans.any { it.id == f.planId }) { a.onFlow { it.copy(step = SubStep.METHOD) } } }) {
        if (current != null && current.active) {
            DfCard(Modifier.fillMaxWidth().padding(top = 8.dp), border = c.primary) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfIcon(R.drawable.ic_badge_check, 22.dp, accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T("اشتراكك الحالي: ${current.name}", 13.5.sp, FontWeight.Bold)
                        T("ينتهي في ${Subs.dateText(current.endsAt)} · متبقٍّ ${current.daysLeft} يوم", 11.5.sp, color = c.muted)
                    }
                    PlanBadge(current.badge)
                }
            }
            T("يمكنك التجديد الآن، وتُضاف المدة الجديدة بعد نهاية اشتراكك الحالي.", 11.5.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        }
        T("اختر الخطة", 15.sp, FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        if (cfg.plans.isEmpty()) {
            com.daftari.app.ui.common.EmptyState(R.drawable.ic_badge_check, "لا توجد خطط متاحة حالياً", "سيضيف المطوّر الخطط وأسعارها قريباً، ثم تظهر هنا مباشرة دون تحديث التطبيق.")
        }
        cfg.plans.forEachIndexed { i, p ->
            val on = p.id == f.planId
            val border by animateColorAsState(if (on) c.primary else c.outline, tween(200), label = "plan")
            DfCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), border = border, bg = c.bg, onClick = { a.onFlow { it.copy(planId = p.id) } }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Radio(on)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(p.name, 15.sp, FontWeight.Bold)
                            if (p.days >= 365 && cfg.plans.size > 1 && i == cfg.plans.lastIndex) { Spacer(Modifier.width(8.dp)); com.daftari.app.ui.common.Pill("الأوفر", c.ok, c.okSoft) }
                        }
                        T(Subs.durationText(p.days), 12.sp, color = c.muted)
                    }
                    T(Subs.priceText(p), 16.sp, FontWeight.ExtraBold)
                }
            }
        }
        if (cfg.note.isNotBlank()) T(cfg.note, 12.sp, color = c.muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(10.dp))
        DfCard(Modifier.fillMaxWidth(), bg = c.bg) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.daftari.app.core.Plan.Feature.entries.take(5).forEach { ft ->
                    Row(verticalAlignment = Alignment.CenterVertically) { DfIcon(R.drawable.ic_check, 15.dp, c.ok); Spacer(Modifier.width(8.dp)); T(ft.labelAr, 12.5.sp, FontWeight.Medium) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        T(if (codeOpen) "إخفاء كود التفعيل" else "لديك كود تفعيل؟", 12.5.sp, FontWeight.SemiBold, accent,
            Modifier.clickable { codeOpen = !codeOpen }.padding(vertical = 6.dp))
        AnimatedVisibility(codeOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Field("", code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(24) }, placeholder = "DF-XXXXXXXXXX", ltr = true, icon = R.drawable.ic_key_round)
                Spacer(Modifier.height(8.dp))
                GhostButton("تفعيل بالكود", Modifier.fillMaxWidth(), R.drawable.ic_badge_check, height = 46.dp) { if (code.length >= 6) a.onActivationCode(code) }
            }
        }
    }
}

@Composable
private fun Radio(on: Boolean) {
    val c = Df.c
    val s by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "radio")
    Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) c.primary else c.outline2, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(11.dp).scale(s).clip(CircleShape).background(c.primary))
    }
}

// ------------------------------------------------------------------ 2) حوالة / محافظ إلكترونية

@Composable
private fun MethodStep(cfg: SubConfig, f: SubFlowState, a: SubActions) {
    val c = Df.c
    val picked = cfg.methods.firstOrNull { it.id == f.methodId }
    val ok = picked != null && (!picked.other || f.other.isNotBlank())
    StepColumn(bottom = { PrimaryButton("التالي", Modifier.fillMaxWidth(), R.drawable.ic_chevron_left, enabled = ok) { a.onFlow { it.copy(step = SubStep.PAY) } } }) {
        T("كيف ستدفع؟", 15.sp, FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MethodTab("حوالة", R.drawable.ic_arrow_right_left, f.tab == Subs.TRANSFER, Modifier.weight(1f)) { a.onFlow { it.copy(tab = Subs.TRANSFER) } }
            MethodTab("محافظ إلكترونية", R.drawable.ic_wallet, f.tab == Subs.WALLET, Modifier.weight(1f)) { a.onFlow { it.copy(tab = Subs.WALLET) } }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(f.tab, label = "tab", transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }) { tab ->
            val list = cfg.methods.filter { it.kind == tab }
            Column {
                if (list.isEmpty()) T(if (tab == Subs.WALLET) "لا توجد محافظ متاحة حالياً" else "لا توجد شركات حوالة متاحة حالياً", 13.sp, color = c.muted, modifier = Modifier.padding(vertical = 20.dp))
                list.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { m -> MethodCard(m, m.id == f.methodId, Modifier.weight(1f)) { a.onFlow { it.copy(methodId = m.id) } } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        AnimatedVisibility(picked?.other == true && picked.kind == f.tab, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Field(if (f.tab == Subs.WALLET) "اسم المحفظة" else "اسم شركة الصرافة", f.other, { v -> a.onFlow { it.copy(other = v.take(40)) } },
                Modifier.padding(top = 4.dp), placeholder = if (f.tab == Subs.WALLET) "مثلاً: موبايل موني" else "مثلاً: العمقي", icon = if (f.tab == Subs.WALLET) R.drawable.ic_wallet else R.drawable.ic_arrow_right_left)
        }
    }
}

@Composable
private fun MethodTab(label: String, @DrawableRes icon: Int, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Df.c
    val accent = if (c.isDark) c.primaryLight else c.primary
    val border by animateColorAsState(if (on) c.primary else c.outline, tween(200), label = "tab")
    val fg by animateColorAsState(if (on) accent else c.muted, tween(200), label = "tabfg")
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.height(54.dp).clip(shape).background(c.bg).border(if (on) 1.5.dp else 1.dp, border, shape).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        DfIcon(icon, 19.dp, fg)
        Spacer(Modifier.width(8.dp))
        T(label, 13.5.sp, if (on) FontWeight.Bold else FontWeight.SemiBold, fg, maxLines = 1)
    }
}

@Composable
private fun MethodCard(m: PayMethod, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Df.c
    val border by animateColorAsState(if (on) c.primary else c.outline, tween(200), label = "m")
    val lift by animateFloatAsState(if (on) 1.04f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "lift")
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.graphicsLayer { scaleX = lift; scaleY = lift }.clip(shape).background(c.bg).border(if (on) 1.5.dp else 1.dp, border, shape).clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MethodLogo(m, 48.dp)
            Spacer(Modifier.height(8.dp))
            T(m.label, 12.5.sp, FontWeight.SemiBold, maxLines = 1, align = TextAlign.Center)
        }
        if (on) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
            DfIcon(R.drawable.ic_check, 12.dp, c.onPrimary)
        }
    }
}

@Composable
fun MethodLogo(m: PayMethod, size: androidx.compose.ui.unit.Dp) {
    val c = Df.c
    val logo = methodLogo(m)
    val shape = RoundedCornerShape(size * 0.28f)
    if (logo != null) Image(painterResource(logo), m.label, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(size).clip(shape).border(1.dp, c.outline2, shape), contentAlignment = Alignment.Center) {
        if (m.other) DfIcon(if (m.kind == Subs.WALLET) R.drawable.ic_wallet else R.drawable.ic_arrow_right_left, size * 0.45f, c.muted)
        else T(m.label.take(1), (size.value * 0.4f).sp, FontWeight.Bold, c.muted)
    }
}

// ------------------------------------------------------------------ 3) the number and the name to pay to

@Composable
private fun PayStep(cfg: SubConfig, f: SubFlowState, a: SubActions) {
    val c = Df.c
    val m = cfg.methods.firstOrNull { it.id == f.methodId } ?: return
    val plan = cfg.plans.firstOrNull { it.id == f.planId }
    StepColumn(bottom = { PrimaryButton("حوّلت المبلغ، التالي", Modifier.fillMaxWidth(), R.drawable.ic_chevron_left) { a.onFlow { it.copy(step = SubStep.RECEIPT) } } }) {
        Spacer(Modifier.height(10.dp))
        DfCard(Modifier.fillMaxWidth(), bg = c.bg) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                MethodLogo(m, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(if (m.other) f.other.ifBlank { m.label } else m.label, 15.sp, FontWeight.Bold)
                    T(if (m.kind == Subs.WALLET) "محفظة إلكترونية" else "حوالة", 11.5.sp, color = c.muted)
                }
                if (plan != null) Column(horizontalAlignment = Alignment.End) {
                    T(Subs.priceText(plan), 16.sp, FontWeight.ExtraBold)
                    T(plan.name, 11.sp, color = c.muted)
                }
            }
        }
        T(if (m.kind == Subs.WALLET) "حوّل المبلغ إلى هذه المحفظة" else "أرسل الحوالة إلى هذا المستلم", 14.sp, FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        CopyRow(if (m.kind == Subs.WALLET) "رقم المحفظة" else "رقم المستلم", m.number, ltr = true, big = true) { a.onCopy("الرقم", m.number) }
        Spacer(Modifier.height(10.dp))
        if (m.accountName.isNotBlank()) CopyRow("الاسم الرباعي للمستلم", m.accountName) { a.onCopy("الاسم", m.accountName) }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            DfIcon(R.drawable.ic_info, 16.dp, c.muted)
            Spacer(Modifier.width(8.dp))
            T("بعد التحويل احتفظ بالإيصال: ستحتاج إلى صورته ورقم العملية في الخطوة التالية.", 12.sp, color = c.muted, lineHeight = 19.sp)
        }
    }
}

@Composable
fun CopyRow(label: String, value: String, ltr: Boolean = false, big: Boolean = false, onCopy: () -> Unit) {
    val c = Df.c
    val accent = if (c.isDark) c.primaryLight else c.primary
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    DfCard(Modifier.fillMaxWidth(), bg = c.bg) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(label, 11.sp, FontWeight.Medium, c.muted)
                Spacer(Modifier.height(2.dp))
                T(if (ltr) "\u2066$value\u2069" else value, if (big) 22.sp else 15.sp, FontWeight.ExtraBold, maxLines = 2)
            }
            val shape = RoundedCornerShape(12.dp)
            Row(Modifier.clip(shape).border(1.dp, if (copied) c.ok else c.outline2, shape).clickable { onCopy(); copied = true }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(copied, label = "copy", transitionSpec = { (fadeIn(tween(150)) togetherWith fadeOut(tween(120))) }) { done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DfIcon(if (done) R.drawable.ic_check else R.drawable.ic_copy, 15.dp, if (done) c.ok else accent)
                        Spacer(Modifier.width(6.dp))
                        T(if (done) "نُسخ" else "نسخ", 12.5.sp, FontWeight.Bold, if (done) c.ok else accent)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 4) the receipt

@Composable
private fun ReceiptStep(cfg: SubConfig, f: SubFlowState, ui: SubUi, a: SubActions) {
    val c = Df.c
    val m = cfg.methods.firstOrNull { it.id == f.methodId }
    val problem = f.problem(m)
    StepColumn(bottom = { PrimaryButton(if (ui.busy) "جارٍ الإرسال…" else "إرسال الطلب", Modifier.fillMaxWidth(), R.drawable.ic_send, enabled = problem == null && !ui.busy, onClick = a.onSubmit) }) {
        T("صورة الإيصال", 15.sp, FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
        val shape = RoundedCornerShape(18.dp)
        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp).clip(shape).border(1.dp, c.outline2, shape), contentAlignment = Alignment.Center) {
            if (ui.receipt != null) {
                Image(ui.receipt, "صورة الإيصال", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { IconBox(R.drawable.ic_x, a.onRemoveReceipt, 34.dp, bg = c.bg) }
            } else Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DfIcon(R.drawable.ic_receipt, 30.dp, c.muted)
                Spacer(Modifier.height(6.dp))
                T("صوّر الإيصال أو اختره من المعرض", 12.5.sp, color = c.muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("تصوير الإيصال", Modifier.weight(1f), R.drawable.ic_camera, height = 48.dp, onClick = a.onCamera)
            GhostButton("من المعرض", Modifier.weight(1f), R.drawable.ic_image, height = 48.dp, onClick = a.onGallery)
        }
        Spacer(Modifier.height(16.dp))
        Field("رقم العملية", f.txn, { v -> a.onFlow { it.copy(txn = v.take(40)) } }, placeholder = "كما يظهر في الإيصال", keyboard = KeyboardType.Ascii, ltr = true, icon = R.drawable.ic_hash)
        if (f.txn.isNotEmpty() && !Subs.isTxnValid(f.txn)) Hint("رقم العملية: أرقام وحروف إنجليزية فقط")
        Spacer(Modifier.height(12.dp))
        Field("الاسم الرباعي للمحوِّل", f.name, { v -> a.onFlow { it.copy(name = v.take(120)) } }, placeholder = "الاسم كما في الإيصال", icon = R.drawable.ic_user)
        if (f.name.isNotBlank() && !Subs.isFourPartName(f.name)) Hint("اكتب أربعة أسماء: الاسم واسم الأب والجد واللقب")
        Spacer(Modifier.height(12.dp))
        T("يصل طلبك إلى المطوّر للمراجعة، وتظهر لك النتيجة هنا عند فتح التطبيق.", 11.5.sp, color = c.muted, lineHeight = 18.sp)
    }
}

@Composable private fun Hint(s: String) = T(s, 11.5.sp, FontWeight.Medium, Df.c.warn, Modifier.padding(top = 5.dp))

// ------------------------------------------------------------------ قيد المراجعة

@Composable
fun PendingView(r: SubRequestDto, busy: Boolean, onRefresh: () -> Unit) {
    val c = Df.c
    val spin = rememberInfiniteTransition(label = "hg")
    val angle by spin.animateFloat(0f, 180f, infiniteRepeatable(tween(1400, delayMillis = 900, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "a")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, c.warn.copy(alpha = 0.6f), RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
            DfIcon(R.drawable.ic_hourglass, 36.dp, c.warn, Modifier.rotate(angle))
        }
        Spacer(Modifier.height(14.dp))
        T("طلبك قيد المراجعة", 19.sp, FontWeight.ExtraBold)
        T("سيُفعَّل اشتراكك بعد التحقق من التحويل، وتظهر لك رسالة عند فتح التطبيق.", 12.5.sp, color = c.muted, align = TextAlign.Center, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp, start = 10.dp, end = 10.dp))
        Spacer(Modifier.height(18.dp))
        DfCard(Modifier.fillMaxWidth(), bg = c.bg) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "الخطة" to "${r.planName} · ${Subs.durationText(r.days)}",
                    "المبلغ" to Subs.priceText(r.price, r.currency),
                    "طريقة الدفع" to "${if (r.methodKind == Subs.WALLET) "محفظة" else "حوالة"} ${r.methodLabel}",
                    "رقم العملية" to "\u2066${r.txnNo}\u2069",
                    "الاسم الرباعي" to r.payerName,
                    "أُرسل في" to Subs.dateText(r.createdAt),
                ).forEach { (k, v) -> KeyVal(k, v) }
            }
        }
        Spacer(Modifier.height(16.dp))
        GhostButton(if (busy) "جارٍ التحديث…" else "تحديث الحالة", Modifier.fillMaxWidth(), R.drawable.ic_refresh_cw, height = 48.dp, onClick = onRefresh)
    }
}

@Composable
private fun KeyVal(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        T(k, 12.5.sp, color = Df.c.muted, modifier = Modifier.width(96.dp))
        T(v, 13.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Unavailable(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        com.daftari.app.ui.common.EmptyState(R.drawable.ic_cloud, "الاشتراكات غير متاحة الآن", message, "إعادة المحاولة", onRetry, R.drawable.ic_refresh_cw)
    }
}

// ------------------------------------------------------------------ badge, dialogs, maintenance

/** «برو» next to the account name while the approved plan runs. */
@Composable
fun PlanBadge(text: String) {
    val c = Df.c
    val accent = if (c.isDark) c.primaryLight else c.primary
    val shimmer = rememberInfiniteTransition(label = "badge")
    val glow by shimmer.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "g")
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).border(1.dp, c.primary.copy(alpha = glow), shape).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        DfIcon(R.drawable.ic_sparkles, 12.dp, accent)
        Spacer(Modifier.width(4.dp))
        T(text, 11.sp, FontWeight.ExtraBold, accent, maxLines = 1)
    }
}

/** A centred card over a dim scrim, the app's dialog look (black card, grey border). */
@Composable
fun DfDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Df.c
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s by animateFloatAsState(if (shown) 1f else 0.92f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "dlg")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.Center) {
        DfCard(Modifier.padding(22.dp).fillMaxWidth().scale(s).clickable(remember { MutableInteractionSource() }, null) {}, bg = c.bg, border = c.outline2, radius = 24.dp) {
            content()
        }
    }
}

@Composable
private fun DialogHead(@DrawableRes icon: Int, tint: Color, title: String) {
    val c = Df.c
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); go = true }
    val s by animateFloatAsState(if (go) 1f else 0.4f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow), label = "head")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).scale(s).clip(CircleShape).border(1.5.dp, tint, CircleShape), contentAlignment = Alignment.Center) { DfIcon(icon, 30.dp, tint) }
        Spacer(Modifier.height(12.dp))
        T(title, 18.sp, FontWeight.ExtraBold, align = TextAlign.Center)
    }
}

/** «تم الاشتراك» with the plan, duration, payer, details and end date — or the rejection with its reason. */
@Composable
fun SubDecisionDialog(r: SubRequestDto, onClose: () -> Unit, onRenew: () -> Unit = {}) {
    val c = Df.c
    DfDialog(onClose) {
        Column(Modifier.padding(20.dp)) {
            if (r.status == "approved") {
                DialogHead(R.drawable.ic_badge_check, c.ok, "تم الاشتراك")
                T("تم تفعيل «${r.planName}» لمدة ${Subs.durationText(r.days)}، شكراً لك.", 13.sp, color = c.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp), lineHeight = 20.sp)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.outline, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Subs.approvedLines(r).forEach { (k, v) -> KeyVal(k, if (k == "رقم العملية") "\u2066$v\u2069" else v) }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton("حسناً", Modifier.fillMaxWidth(), onClick = onClose)
            } else {
                DialogHead(R.drawable.ic_triangle_alert, c.bad, "لم يُقبل طلب الاشتراك")
                T("طلب «${r.planName}» برقم العملية \u2066${r.txnNo}\u2069", 12.5.sp, color = c.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, c.bad.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                    T("السبب", 11.sp, FontWeight.Medium, c.muted)
                    T(r.rejectReason.ifBlank { "لم يُذكر سبب" }, 14.sp, FontWeight.SemiBold, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton("إرسال طلب جديد", Modifier.fillMaxWidth(), onClick = onRenew)
                Spacer(Modifier.height(8.dp))
                GhostButton("إغلاق", Modifier.fillMaxWidth(), height = 46.dp, onClick = onClose)
            }
        }
    }
}

@Composable
fun ExpiredDialog(planName: String, onRenew: () -> Unit, onClose: () -> Unit) {
    val c = Df.c
    DfDialog(onClose) {
        Column(Modifier.padding(20.dp)) {
            DialogHead(R.drawable.ic_clock, c.warn, "انتهى اشتراكك")
            T("انتهت مدة «$planName». جدّد الاشتراك لتستمر ميزات برو، وبياناتك كلها باقية كما هي.", 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp))
            PrimaryButton("تجديد الاشتراك", Modifier.fillMaxWidth(), onClick = onRenew)
            Spacer(Modifier.height(8.dp))
            GhostButton("لاحقاً", Modifier.fillMaxWidth(), height = 46.dp, onClick = onClose)
        }
    }
}

@Composable
fun BroadcastDialog(b: Broadcast, onClose: () -> Unit) {
    val c = Df.c
    DfDialog(onClose) {
        Column(Modifier.padding(20.dp)) {
            DialogHead(R.drawable.ic_megaphone, if (c.isDark) c.primaryLight else c.primary, b.title.ifBlank { "رسالة من فريق دفتري" })
            T(b.body, 14.sp, align = TextAlign.Center, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp))
            PrimaryButton("حسناً", Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

/** A thin notice at the top while the server is in maintenance (banner mode): local work goes on, subscriptions wait. */
@Composable
fun MaintenanceBanner(message: String, onClose: () -> Unit) {
    val c = Df.c
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(shape).background(c.bg).border(1.dp, c.warn.copy(alpha = 0.7f), shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        DfIcon(R.drawable.ic_wrench, 17.dp, c.warn)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T("صيانة مؤقتة", 12.sp, FontWeight.Bold, c.warn)
            T(message.ifBlank { "بعض الخدمات متوقفة قليلاً، ويمكنك متابعة عملك على دفترك." }, 12.sp, color = c.text, lineHeight = 18.sp, maxLines = 3)
        }
        Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { DfIcon(R.drawable.ic_x, 15.dp, c.muted) }
    }
}

/** Full-screen maintenance (the developer chose «شاشة كاملة» in Madyan Panel). */
@Composable
fun MaintenanceScreen(message: String, retrying: Boolean, onRetry: () -> Unit) {
    val c = Df.c
    val t = rememberInfiniteTransition(label = "mt")
    val float by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
    val tilt by t.animateFloat(-14f, 14f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w")
    Column(Modifier.fillMaxSize().background(c.bg).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.logo_mark), null, Modifier.size(112.dp).graphicsLayer { translationY = float * density })
            Box(Modifier.align(Alignment.BottomStart).size(46.dp).clip(CircleShape).background(c.bg).border(1.5.dp, c.warn, CircleShape), contentAlignment = Alignment.Center) {
                DfIcon(R.drawable.ic_wrench, 22.dp, c.warn, Modifier.rotate(tilt))
            }
        }
        Spacer(Modifier.height(22.dp))
        T("دفتري في صيانة قصيرة", 21.sp, FontWeight.ExtraBold, align = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        T(message.ifBlank { "نعمل على تحسين الخدمة، وسنعود خلال وقت قصير." }, 14.sp, color = c.muted, align = TextAlign.Center, lineHeight = 23.sp)
        Spacer(Modifier.height(10.dp))
        T("بياناتك محفوظة على جوالك ولن تتأثر.", 12.sp, color = c.muted2, align = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(if (retrying) "جارٍ التحقق…" else "إعادة المحاولة", Modifier.fillMaxWidth(), R.drawable.ic_refresh_cw, enabled = !retrying, onClick = onRetry)
    }
}
