/*
 * دفتري · voice/VoiceUi.kt
 * واجهة الإدخال الصوتي: زر الميكروفون، وعرض تنزيل نموذج الصوت مرة واحدة، ونافذة «أستمع إليك…» ثم «جارٍ التعرّف…»،
 * وطلب إذن الميكروفون عند الحاجة فقط، ورسائل الأخطاء بالفصحى. الكتابة اليدوية متاحة دائماً.
 * Voice UI (1.1.7, offline Whisper only): the mic button; on the first tap without a model, the one-time download offer
 * (size, Wi-Fi hint, «أساسي»/«خفيف», progress %, resumable); then RECORD_AUDIO (asked after a فصحى explanation), the
 * recording dialog with a live level and «تم», «جارٍ التعرّف…» while the phone transcribes, and the text handed back.
 * The caller parses it and pre-fills the form; nothing is saved here.
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** Accessibility label of the mic (TalkBack reads it; the 1.1.7 tests find the mic by it). */
const val MIC_LABEL = "الإدخال الصوتي"

/** Round mic button in the app's IconBox style (primary tint so it reads as the voice action). */
@Composable
fun MicButton(onClick: () -> Unit, size: Dp = 40.dp) {
    val c = Df.c
    Box(
        Modifier.size(size).clip(CircleShape).background(c.primarySoft).border(1.dp, c.primary.copy(alpha = 0.35f), CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = MIC_LABEL },
        contentAlignment = Alignment.Center,
    ) { DfIcon(R.drawable.ic_mic, size * 0.5f, if (c.isDark) c.primaryLight else c.primary) }
}

private sealed interface Phase {
    data object Explain : Phase
    data object Offer : Phase
    data object Downloading : Phase
    data object Starting : Phase
    data object Listening : Phase
    data object Transcribing : Phase
    data class Problem(val p: VoiceProblem) : Phase
}

/**
 * The mic dialog: offers the model download if needed, asks for the mic permission if needed, records, transcribes on
 * the phone and calls [onText] once with the sentence. [example] is a sample sentence for this screen.
 */
@Composable
fun VoiceCaptureDialog(example: String, onText: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Df.c
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf<Phase>(Phase.Starting) }
    var lvl by remember { mutableStateOf(0f) }
    val speech = remember { SpeechEngines.pick(ctx) }
    val events = remember {
        object : SpeechEngine.Listener {
            override fun onListening() { phase = Phase.Listening }
            override fun onLevel(level: Float) { lvl = level }
            override fun onTranscribing() { phase = Phase.Transcribing }
            override fun onFinal(text: String) { ModelDownloads.used(); onText(text) }
            override fun onProblem(problem: VoiceProblem) { phase = Phase.Problem(problem) }
        }
    }
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun begin() { lvl = 0f; phase = Phase.Starting; speech.start(events) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else phase = Phase.Problem(VoiceProblem.NO_PERMISSION)
    }
    /** what the mic does now: model → permission → listen */
    fun next() {
        when (VoiceStarts.decide(WhisperNative.available, VoiceModels.usable(ctx) != null, ModelDownloads.busy, granted(), speech.onlineLikely())) {
            VoiceStart.UNSUPPORTED -> phase = Phase.Problem(VoiceProblem.UNSUPPORTED)
            VoiceStart.OFFER_DOWNLOAD -> phase = Phase.Offer
            VoiceStart.DOWNLOADING -> phase = Phase.Downloading
            VoiceStart.ASK_PERMISSION -> phase = Phase.Explain
            VoiceStart.LISTEN -> begin()
        }
    }
    LaunchedEffect(Unit) { next() }
    val dl by ModelDownloads.state.collectAsState()
    // the download finished while this dialog is open: go on to the permission / recording
    LaunchedEffect(dl) { if (dl == ModelState.Ready && phase == Phase.Downloading) next() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.sheet).border(1.dp, c.outline2, RoundedCornerShape(26.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val p = phase) {
                Phase.Offer -> ModelOffer(onDownload = { m -> ModelDownloads.start(ctx, m); phase = Phase.Downloading }, onClose = onDismiss)
                Phase.Downloading -> ModelProgress(dl, onRetry = { ModelDownloads.start(ctx) }, onClose = onDismiss)
                Phase.Explain -> {
                    BigMic(false)
                    Spacer(Modifier.height(14.dp))
                    T("الإدخال بالصوت", 17.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    T(
                        "تكلّم بشكل طبيعي وسيكتب دفتري ما قلته بدلاً عنك، ثم تراجعه بنفسك. " +
                            "يحتاج ذلك إلى إذن الميكروفون. عند الاتصال بالإنترنت يُرسل التسجيل إلى خادم دفتري لتحويله بدقة أعلى ولا يُحفظ، ودون إنترنت يحوّله نموذج الصوت على جوالك. يمكنك اختيار «دون إنترنت فقط» من الإعدادات.",
                        13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("متابعة", Modifier.fillMaxWidth(), R.drawable.ic_mic) { permission.launch(Manifest.permission.RECORD_AUDIO) }
                    Spacer(Modifier.height(8.dp))
                    GhostButton("الكتابة يدوياً", Modifier.fillMaxWidth(), onClick = onDismiss)
                }
                Phase.Starting, Phase.Listening -> {
                    BigMic(p == Phase.Listening, lvl)
                    Spacer(Modifier.height(14.dp))
                    T(if (p == Phase.Listening) "أستمع إليك…" else "لحظة…", 17.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) { T("مثال: «$example»", 13.sp, color = c.muted2, align = TextAlign.Center, lineHeight = 20.sp) }
                    T("يتوقف التسجيل وحده بعد سكوت قصير، أو اضغط «تم».", 11.5.sp, color = c.muted2, align = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("تم", Modifier.weight(1f), R.drawable.ic_check, enabled = p == Phase.Listening) { speech.stop() }
                        GhostButton("إلغاء", Modifier.weight(1f), onClick = onDismiss)
                    }
                }
                Phase.Transcribing -> {
                    CircularProgressIndicator(Modifier.size(56.dp), color = c.primary, strokeWidth = 4.dp)
                    Spacer(Modifier.height(16.dp))
                    T("جارٍ التعرّف…", 17.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    T("يتحوّل كلامك إلى نص الآن، وقد يستغرق ذلك بضع ثوانٍ.", 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp)
                    Spacer(Modifier.height(16.dp))
                    GhostButton("إلغاء", Modifier.fillMaxWidth(), onClick = onDismiss)
                }
                is Phase.Problem -> {
                    DfIcon(R.drawable.ic_triangle_alert, 34.dp, c.warn)
                    Spacer(Modifier.height(10.dp))
                    T(p.p.title, 16.sp, FontWeight.ExtraBold, align = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    T(p.p.body, 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp)
                    Spacer(Modifier.height(16.dp))
                    if (p.p.offerDownload) {
                        PrimaryButton("تنزيل نموذج الصوت", Modifier.fillMaxWidth(), R.drawable.ic_download) {
                            if (p.p == VoiceProblem.MODEL_BROKEN) VoiceModels.delete(ctx)
                            phase = Phase.Offer
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
                        if (p.p != VoiceProblem.UNSUPPORTED) GhostButton("حاول مرة أخرى", Modifier.weight(1f), R.drawable.ic_mic) { next() }
                        GhostButton("الكتابة يدوياً", Modifier.weight(1f), onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/** The one-time download offer: what it is, the two sizes, a Wi-Fi hint on mobile data, and the button. */
@Composable
fun ModelOffer(onDownload: (VoiceModel) -> Unit, onClose: () -> Unit, mobileData: Boolean? = null) {
    val c = Df.c
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(VoiceModels.chosen(ctx)) }
    val cellular = mobileData ?: remember { ModelDownloads.onMobileData(ctx) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DfIcon(R.drawable.ic_download, 34.dp, if (c.isDark) c.primaryLight else c.primary)
        Spacer(Modifier.height(10.dp))
        T("الإدخال الصوتي دون إنترنت", 17.sp, FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        T(
            "ليحوّل دفتري كلامك إلى نص على جوالك نفسه، نزّل نموذج الصوت مرة واحدة. بعدها يعمل دون إنترنت، ولا يُرسل صوتك إلى أي خادم.",
            13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(12.dp))
        VoiceModel.entries.forEach { m ->
            val on = m == pick
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.primarySoft else c.s2)
                    .border(if (on) 1.5.dp else 1.dp, if (on) c.primary else c.outline, RoundedCornerShape(14.dp)).clickable { pick = m }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(16.dp).clip(CircleShape).border(if (on) 5.dp else 1.5.dp, if (on) c.primary else c.outline2, CircleShape))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    T("${m.label} · ${m.sizeText}" + if (m == VoiceModel.DEFAULT) " · موصى به" else "", 13.5.sp, FontWeight.Bold)
                    T(m.note, 11.5.sp, color = c.muted)
                }
            }
        }
        if (cellular) T("📶 أنت على بيانات الجوال؛ يُفضَّل التنزيل عبر Wi‑Fi.", 12.sp, FontWeight.SemiBold, c.warn, Modifier.padding(top = 10.dp), align = TextAlign.Center)
        else T("يُفضَّل التنزيل عبر Wi‑Fi، ويكمل من حيث توقّف إن انقطع.", 11.5.sp, color = c.muted2, modifier = Modifier.padding(top = 10.dp), align = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        T("تنزيل نموذج الصوت دون إنترنت (${pick.sizeText}) مرة واحدة", 12.5.sp, FontWeight.SemiBold, c.muted, Modifier.padding(bottom = 8.dp), align = TextAlign.Center)
        PrimaryButton("تنزيل · ${pick.sizeText}", Modifier.fillMaxWidth(), R.drawable.ic_download) { onDownload(pick) }
        Spacer(Modifier.height(8.dp))
        GhostButton("الكتابة يدوياً", Modifier.fillMaxWidth(), onClick = onClose)
    }
}

/** Download progress (percent bar), failure with «إعادة المحاولة», and «متابعة الكتابة» (the download goes on). */
@Composable
fun ModelProgress(st: ModelState, onRetry: () -> Unit, onClose: () -> Unit) {
    val c = Df.c
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DfIcon(if (st == ModelState.Ready) R.drawable.ic_check else R.drawable.ic_download, 34.dp, if (st is ModelState.Failed) c.warn else if (c.isDark) c.primaryLight else c.primary)
        Spacer(Modifier.height(10.dp))
        T("نموذج الصوت دون إنترنت", 16.sp, FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        T(st.line ?: "يبدأ التنزيل…", 13.sp, color = c.muted, align = TextAlign.Center, lineHeight = 21.sp)
        val pct = (st as? ModelState.Downloading)?.percent
        if (pct != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.s2)) {
                Box(Modifier.fillMaxWidth(pct / 100f).height(8.dp).clip(CircleShape).background(c.primary))
            }
        }
        Spacer(Modifier.height(16.dp))
        if (st is ModelState.Failed) { PrimaryButton("إعادة المحاولة", Modifier.fillMaxWidth(), R.drawable.ic_download, onClick = onRetry); Spacer(Modifier.height(8.dp)) }
        GhostButton(if (st is ModelState.Downloading) "متابعة الكتابة" else "إغلاق", Modifier.fillMaxWidth(), onClick = onClose)
    }
}

@Composable
private fun BigMic(active: Boolean, level: Float = 0f) {
    val c = Df.c
    val scale = if (active) 1f + level.coerceIn(0f, 1f) * 0.18f else 1f
    Box(
        Modifier.size(76.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(if (active) c.primary else c.primarySoft)
            .border(if (active) 6.dp else 1.dp, if (active) c.primary.copy(alpha = 0.25f) else c.outline2, CircleShape),
        contentAlignment = Alignment.Center,
    ) { DfIcon(R.drawable.ic_mic, 34.dp, if (active) c.onPrimary else if (c.isDark) c.primaryLight else c.primary) }
}

/** One line under the form: what was heard (and by which engine, 1.1.7: «عبر الإنترنت» / «دون إنترنت») and what was filled from it. */
@Composable
fun HeardLine(transcript: String, filled: String, warning: String? = null, action: String? = null, onAction: (() -> Unit)? = null, via: String = "") {
    val c = Df.c
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.primarySoft).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DfIcon(R.drawable.ic_mic, 15.dp, if (c.isDark) c.primaryLight else c.primary)
            Spacer(Modifier.size(6.dp))
            T(if (via.isBlank()) "سمعتُ: «$transcript»" else "سمعتُ ($via): «$transcript»", 12.5.sp, FontWeight.SemiBold, maxLines = 3)
        }
        if (filled.isNotBlank()) T(filled, 11.5.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
        if (warning != null) T("⚠️ $warning", 11.5.sp, FontWeight.SemiBold, c.warn, Modifier.padding(top = 4.dp))
        if (action != null && onAction != null) T(action, 12.5.sp, FontWeight.Bold, if (c.isDark) c.primaryLight else c.primary, Modifier.padding(top = 6.dp).clickable(onClick = onAction))
    }
}

/** The model download's small line under the forms («جارٍ تنزيل نموذج الصوت… 40%»); nothing when idle. */
@Composable
fun ModelStatusLine(modifier: Modifier = Modifier) {
    val c = Df.c
    val st by ModelDownloads.state.collectAsState()
    val line = st.line ?: return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (st is ModelState.Failed) c.s2 else c.primarySoft).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfIcon(if (st == ModelState.Ready) R.drawable.ic_check else R.drawable.ic_download, 15.dp, if (st is ModelState.Failed) c.warn else if (c.isDark) c.primaryLight else c.primary)
        Spacer(Modifier.size(8.dp))
        T(line, 12.sp, FontWeight.SemiBold, maxLines = 2)
    }
}
