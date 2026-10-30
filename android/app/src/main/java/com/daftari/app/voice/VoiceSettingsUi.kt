/*
 * دفتري · voice/VoiceSettingsUi.kt
 * إعدادات الإدخال الصوتي: حالة نموذج الصوت دون إنترنت (منزّل/يُنزَّل/غير منزّل) مع التنزيل والحذف واختيار «أساسي/خفيف»،
 * واختيار «محرك الصوت»، ومفتاح «إرسال تلقائي بعد التحدث» لمحادثة مدين، وتراخيص whisper.cpp ونموذج Whisper.
 * Settings rows for voice (1.1.7): the offline model's status with download / delete and the «أساسي»/«خفيف» choice, the
 * «محرك الصوت» (تلقائي = الإنترنت أولاً / دون إنترنت فقط), the chat-only «إرسال تلقائي بعد التحدث» switch (debt / payment entries are never auto-saved), and the licence notice.
 */
package com.daftari.app.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftari.app.R
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.common.Toggle
import com.daftari.app.ui.theme.Df

/** The status text of the offline model, pure (unit-tested). */
object VoiceModelStatus {
    fun text(ready: VoiceModel?, state: ModelState): String = when {
        state is ModelState.Downloading -> "جارٍ التنزيل \u2066${state.percent}%\u2069"
        ready != null -> "منزّل · ${ready.label} (${ready.sizeText}) · يعمل دون إنترنت"
        state is ModelState.Failed -> "لم يكتمل التنزيل · يكمل من حيث توقّف"
        else -> "غير منزّل · الصوت يعمل عبر الإنترنت، ونزّله ليعمل دون إنترنت"
    }
}

@Composable
fun VoiceSettingsRows() {
    val c = Df.c
    val ctx = LocalContext.current
    val st by ModelDownloads.state.collectAsState()
    val autoSend by VoicePrefs.chatAutoSend.collectAsState()
    val mode by VoicePrefs.mode.collectAsState()
    var tick by remember { mutableStateOf(0) } // re-read the files after a delete
    val ready = remember(st, tick) { VoiceModels.usable(ctx) }
    var confirmDelete by remember { mutableStateOf(false) }
    remember { VoicePrefs.load(ctx) }
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.s3), contentAlignment = Alignment.Center) {
                DfIcon(R.drawable.ic_mic, 17.dp, if (c.isDark) c.primaryLight else c.text)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("نموذج الصوت دون إنترنت", 13.5.sp, FontWeight.Bold)
                T(VoiceModelStatus.text(ready, st), 11.5.sp, color = if (ready != null) c.ok else c.muted, maxLines = 2)
            }
        }
        val pct = (st as? ModelState.Downloading)?.percent
        if (pct != null) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(c.s2)) {
                Box(Modifier.fillMaxWidth(pct / 100f).height(6.dp).clip(CircleShape).background(c.primary))
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceModel.entries.forEach { m ->
                val on = (ready ?: VoiceModels.chosen(ctx)) == m
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) c.primarySoft else c.s2)
                        .border(1.dp, if (on) c.primary else c.outline, RoundedCornerShape(50))
                        .clickable(enabled = !ModelDownloads.busy) { VoiceModels.choose(ctx, m); tick++ }.padding(horizontal = 12.dp, vertical = 6.dp),
                ) { T("${m.label} · ${m.sizeText}", 12.sp, FontWeight.SemiBold, if (on) (if (c.isDark) c.primaryLight else c.primary) else c.muted) }
            }
        }
        Spacer(Modifier.height(10.dp))
        val chosen = VoiceModels.chosen(ctx)
        when {
            ModelDownloads.busy -> GhostButton("إيقاف التنزيل مؤقتاً", Modifier.fillMaxWidth(), height = 44.dp) { ModelDownloads.stop() }
            ready == null || (ready != chosen) -> PrimaryButton("تنزيل «${chosen.label}» · ${chosen.sizeText}", Modifier.fillMaxWidth(), R.drawable.ic_download, height = 44.dp) { ModelDownloads.start(ctx, chosen) }
            else -> Unit
        }
        if (ready != null && !ModelDownloads.busy) {
            if (ready != chosen) Spacer(Modifier.height(8.dp))
            if (!confirmDelete) GhostButton("حذف نموذج الصوت", Modifier.fillMaxWidth(), R.drawable.ic_trash_2, height = 44.dp) { confirmDelete = true }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("تأكيد الحذف", Modifier.weight(1f), R.drawable.ic_trash_2, bg = c.warn, height = 44.dp) {
                    VoiceModels.delete(ctx); ModelDownloads.state.value = ModelState.Idle; confirmDelete = false; tick++
                }
                GhostButton("إلغاء", Modifier.weight(1f), height = 44.dp) { confirmDelete = false }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
    // 1.1.7: «محرك الصوت» — online first (more accurate) when connected, the phone's model as fallback; or the phone only
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.s3), contentAlignment = Alignment.Center) {
                DfIcon(R.drawable.ic_cloud, 17.dp, if (c.isDark) c.primaryLight else c.text)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("محرك الصوت", 13.5.sp, FontWeight.Bold)
                T(if (mode == VoiceMode.AUTO) "عبر الإنترنت عند الاتصال (أدقّ)، وإلا بنموذج الجوال" else "بنموذج الجوال فقط، ولا يُرسل الصوت إلى أي خادم", 11.5.sp, color = c.muted, maxLines = 2)
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceMode.entries.forEach { m ->
                val on = m == mode
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) c.primarySoft else c.s2)
                        .border(1.dp, if (on) c.primary else c.outline, RoundedCornerShape(50)).clickable { VoicePrefs.setMode(ctx, m) }.padding(horizontal = 12.dp, vertical = 6.dp),
                ) { T(m.label, 12.sp, FontWeight.SemiBold, if (on) (if (c.isDark) c.primaryLight else c.primary) else c.muted) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.s3), contentAlignment = Alignment.Center) {
            DfIcon(R.drawable.ic_send, 17.dp, if (c.isDark) c.primaryLight else c.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T("إرسال تلقائي بعد التحدث", 13.5.sp, FontWeight.Bold)
            T("في محادثة مدين فقط. الدين والتسديد يُراجعان ويُحفظان بيدك دائماً.", 11.5.sp, color = c.muted, maxLines = 3)
        }
        Toggle(autoSend) { VoicePrefs.setChatAutoSend(ctx, it) }
    }
}

/** «التراخيص»: whisper.cpp and the Whisper model. */
@Composable
fun VoiceLicensesNote() {
    val c = Df.c
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.s3), contentAlignment = Alignment.Center) {
                DfIcon(R.drawable.ic_shield_check, 17.dp, if (c.isDark) c.primaryLight else c.text)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("التراخيص", 13.5.sp, FontWeight.Bold)
                T("مكتبات ونماذج مفتوحة المصدر يستخدمها دفتري", 11.5.sp, color = c.muted, maxLines = 1)
            }
            DfIcon(R.drawable.ic_chevron_left, 18.dp, c.muted)
        }
        if (open) T(LICENSES, 11.5.sp, color = c.muted, modifier = Modifier.padding(top = 10.dp), lineHeight = 18.sp)
    }
}

const val LICENSES =
    "• whisper.cpp و ggml — ترخيص MIT، حقوق النشر © 2023–2026 Georgi Gerganov ومساهمو ggml. يعمل داخل التطبيق لتحويل الكلام إلى نص على الجوال.\n" +
        "• نماذج Whisper (base / tiny) — من OpenAI بترخيص MIT، حقوق النشر © 2022 OpenAI؛ بصيغة ggml من مستودع ggerganov/whisper.cpp على Hugging Face، تُنزَّل عند الطلب ولا تأتي مع التطبيق.\n" +
        "MIT: يُسمح بالاستخدام والنسخ والتعديل والتوزيع مع إبقاء إشعار حقوق النشر، والبرنامج مقدَّم «كما هو» دون أي ضمان."
