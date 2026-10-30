/*
 * دفتري · voice/VoiceUi.kt
 * واجهة الإدخال الصوتي: زر الميكروفون، ونافذة «أستمع إليك…» مع النص المباشر، وطلب إذن الميكروفون عند أول ضغطة فقط،
 * ورسائل الأخطاء بالفصحى مع زر تنزيل حزمة العربية دون إنترنت. الكتابة اليدوية متاحة دائماً.
 * Voice UI: the mic button and the listening dialog (live transcript → final text). RECORD_AUDIO is asked only when the mic
 * is first tapped, after a فصحى explanation; a refusal is handled without blocking anything. The dialog only hands back
 * text — the caller parses it and pre-fills the form; nothing is saved here.
 */
package com.daftari.app.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.daftari.app.R
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.GhostButton
import com.daftari.app.ui.common.PrimaryButton
import com.daftari.app.ui.common.T
import com.daftari.app.ui.theme.Df

/** Round mic button in the app's IconBox style (primary tint so it reads as the voice action). */
@Composable
fun MicButton(onClick: () -> Unit, size: Dp = 40.dp) {
    val c = Df.c
    Box(
        Modifier.size(size).clip(CircleShape).background(c.primarySoft).border(1.dp, c.primary.copy(alpha = 0.35f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { DfIcon(R.drawable.ic_mic, size * 0.5f, if (c.isDark) c.primaryLight else c.primary) }
}

private sealed interface Phase {
    data object Explain : Phase
    data object Starting : Phase
    data object Listening : Phase
    data class Problem(val p: VoiceProblem) : Phase
    data class PackOpened(val hint: String) : Phase
    data object PackBackground : Phase
}

/**
 * «أستمع إليك…»: asks for the mic permission if needed, listens in Arabic (offline preferred), shows the live words,
 * and calls [onText] once with the final sentence. [example] is a sample sentence for this screen.
 */
@Composable
fun VoiceCaptureDialog(example: String, onText: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Df.c
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf<Phase>(Phase.Starting) }
    var live by remember { mutableStateOf("") }
    val speech = remember { SpeechEngines.pick(ctx) }
    val events = remember {
        object : SpeechEngine.Listener {
            override fun onListening() { phase = Phase.Listening }
            override fun onPartial(text: String) { live = text }
            override fun onFinal(text: String) { live = text; onText(text) }
            override fun onProblem(problem: VoiceProblem) { phase = Phase.Problem(problem) }
        }
    }
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun begin() { live = ""; phase = Phase.Starting; speech.start(events) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else phase = Phase.Problem(VoiceProblem.NO_PERMISSION)
    }
    LaunchedEffect(Unit) { if (granted()) begin() else phase = Phase.Explain }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.sheet).border(1.dp, c.outline2, RoundedCornerShape(26.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val p = phase) {
                Phase.Explain -> {
                    BigMic(false)
                    Spacer(Modifier.height(14.dp))
                    T("الإدخال بالصوت", 17.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    T(
                        "تكلّم بشكل طبيعي وسيكتب دفتري الاسم والمبلغ ونوع العملية بدلاً عنك، ثم تراجعها وتحفظها بنفسك. " +
                            "يحتاج ذلك إلى إذن الميكروفون، ويجري التحويل بمحرّك الكلام في جهازك، ولا يرسل دفتري صوتك إلى أي خادم.",
                        13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("متابعة", Modifier.fillMaxWidth(), R.drawable.ic_mic) { permission.launch(Manifest.permission.RECORD_AUDIO) }
                    Spacer(Modifier.height(8.dp))
                    GhostButton("الكتابة يدوياً", Modifier.fillMaxWidth(), onClick = onDismiss)
                }
                Phase.Starting, Phase.Listening -> {
                    BigMic(p == Phase.Listening)
                    Spacer(Modifier.height(14.dp))
                    T(if (p == Phase.Listening) "أستمع إليك…" else "لحظة…", 17.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (live.isBlank()) T("مثال: «$example»", 13.sp, color = c.muted2, align = TextAlign.Center, lineHeight = 20.sp)
                        else T(live, 16.sp, FontWeight.Bold, align = TextAlign.Center, lineHeight = 24.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("تم", Modifier.weight(1f), R.drawable.ic_check, enabled = p == Phase.Listening) { speech.stop() }
                        GhostButton("إلغاء", Modifier.weight(1f), onClick = onDismiss)
                    }
                }
                is Phase.Problem -> {
                    DfIcon(R.drawable.ic_triangle_alert, 34.dp, c.warn)
                    Spacer(Modifier.height(10.dp))
                    T(p.p.title, 16.sp, FontWeight.ExtraBold, align = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    T(p.p.body, 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp)
                    if (live.isNotBlank()) { Spacer(Modifier.height(8.dp)); T("«$live»", 13.sp, FontWeight.SemiBold, align = TextAlign.Center) }
                    Spacer(Modifier.height(16.dp))
                    if (p.p.offerPack) {
                        if (SpeechPack.canDownloadInBackground(ctx)) PrimaryButton("تنزيل حزمة العربية في الخلفية", Modifier.fillMaxWidth(), R.drawable.ic_download) {
                            phase = if (SpeechPack.startBackground(ctx)) Phase.PackBackground else Phase.PackOpened(SpeechPack.openSettingsOrStore(ctx).hint)
                        } else PrimaryButton("فتح إعدادات الكلام من Google", Modifier.fillMaxWidth(), R.drawable.ic_download) {
                            phase = Phase.PackOpened(SpeechPack.openSettingsOrStore(ctx).hint)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (p.p.offerStore) {
                        PrimaryButton("تثبيت خدمات الكلام من Google", Modifier.fillMaxWidth(), R.drawable.ic_download) {
                            phase = Phase.PackOpened(if (SpeechPack.openStore(ctx)) SpeechPack.Opened.STORE.hint else SpeechPack.Opened.NOTHING.hint)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (p.p.offerSettings) {
                        PrimaryButton("فتح إعدادات التطبيق", Modifier.fillMaxWidth(), R.drawable.ic_settings) {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("حاول مرة أخرى", Modifier.weight(1f), R.drawable.ic_mic) {
                            if (granted()) begin() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        GhostButton("الكتابة يدوياً", Modifier.weight(1f), onClick = onDismiss)
                    }
                }
                Phase.PackBackground -> {
                    val st by SpeechPack.status.collectAsState()
                    DfIcon(if (st == PackStatus.Ready) R.drawable.ic_check else R.drawable.ic_download, 34.dp, if (c.isDark) c.primaryLight else c.primary)
                    Spacer(Modifier.height(10.dp))
                    T("حزمة اللغة العربية", 16.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    T(st.line ?: "بدأ التنزيل في الخلفية.", 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp)
                    val pct = (st as? PackStatus.Downloading)?.percent
                    if (pct != null) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.s2)) {
                            Box(Modifier.fillMaxWidth(pct / 100f).height(6.dp).clip(CircleShape).background(c.primary))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (st == PackStatus.Failed) {
                        PrimaryButton("فتح إعدادات الكلام من Google", Modifier.fillMaxWidth(), R.drawable.ic_settings) { phase = Phase.PackOpened(SpeechPack.openSettingsOrStore(ctx).hint) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (st == PackStatus.Ready) PrimaryButton("تكلّم الآن", Modifier.weight(1f), R.drawable.ic_mic) { if (granted()) begin() else permission.launch(Manifest.permission.RECORD_AUDIO) }
                        GhostButton("متابعة الكتابة", Modifier.weight(1f), onClick = onDismiss)
                    }
                }
                is Phase.PackOpened -> {
                    DfIcon(R.drawable.ic_download, 34.dp, if (c.isDark) c.primaryLight else c.primary)
                    Spacer(Modifier.height(10.dp))
                    T("حزمة اللغة العربية", 16.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    T(p.hint, 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("حاول الآن", Modifier.weight(1f), R.drawable.ic_mic) { if (granted()) begin() else permission.launch(Manifest.permission.RECORD_AUDIO) }
                        GhostButton("الكتابة يدوياً", Modifier.weight(1f), onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigMic(active: Boolean) {
    val c = Df.c
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(if (active) c.primary else c.primarySoft)
            .border(if (active) 6.dp else 1.dp, if (active) c.primary.copy(alpha = 0.25f) else c.outline2, CircleShape),
        contentAlignment = Alignment.Center,
    ) { DfIcon(R.drawable.ic_mic, 34.dp, if (active) c.onPrimary else if (c.isDark) c.primaryLight else c.primary) }
}

/** One line under the form: what was heard and what was filled from it (so nothing is filled silently). */
@Composable
fun HeardLine(transcript: String, filled: String, warning: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Df.c
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.primarySoft).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DfIcon(R.drawable.ic_mic, 15.dp, if (c.isDark) c.primaryLight else c.primary)
            Spacer(Modifier.size(6.dp))
            T("سمعتُ: «$transcript»", 12.5.sp, FontWeight.SemiBold, maxLines = 3)
        }
        if (filled.isNotBlank()) T(filled, 11.5.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
        if (warning != null) T("⚠️ $warning", 11.5.sp, FontWeight.SemiBold, c.warn, Modifier.padding(top = 4.dp))
        if (action != null && onAction != null) T(action, 12.5.sp, FontWeight.Bold, if (c.isDark) c.primaryLight else c.primary, Modifier.padding(top = 6.dp).clickable(onClick = onAction))
    }
}

/** The background download's small status line («جارٍ تنزيل حزمة العربية في الخلفية…»); nothing when idle. */
@Composable
fun PackStatusLine(modifier: Modifier = Modifier) {
    val c = Df.c
    val st by SpeechPack.status.collectAsState()
    val line = st.line ?: return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (st == PackStatus.Failed) c.s2 else c.primarySoft).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfIcon(if (st == PackStatus.Ready) R.drawable.ic_check else R.drawable.ic_download, 15.dp, if (st == PackStatus.Failed) c.warn else if (c.isDark) c.primaryLight else c.primary)
        Spacer(Modifier.size(8.dp))
        T(line, 12.sp, FontWeight.SemiBold, maxLines = 2)
    }
}
