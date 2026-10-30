package com.daftari.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daftari.app.core.Plan
import com.daftari.app.core.cur
import com.daftari.app.ui.backup.BackupActions
import com.daftari.app.ui.backup.BackupPasswordDialog
import com.daftari.app.ui.backup.BackupPreviewSheet
import com.daftari.app.ui.backup.BackupScreen
import com.daftari.app.ui.backup.BackupUi
import com.daftari.app.ui.importer.MigratePreview
import com.daftari.app.ui.importer.MigrateStart
import com.daftari.app.ui.log.LogDetailScreen
import com.daftari.app.ui.log.LogExportSheet
import com.daftari.app.ui.log.LogListScreen
import com.daftari.app.ui.log.RestoreConfirm
import com.daftari.app.ui.log.SecureGate
import com.daftari.app.ui.settings.RatesScreen
import com.daftari.app.data.AppSettings
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartySummary
import com.daftari.app.domain.Role
import com.daftari.app.export.ExportFormat
import com.daftari.app.export.Report
import com.daftari.app.export.ReportRenderer
import com.daftari.app.notify.Alerts
import com.daftari.app.notify.DailyWorker
import com.daftari.app.security.Biometric
import com.daftari.app.share.Share
import com.daftari.app.ui.auth.GoogleAuth
import com.daftari.app.ui.auth.GoogleResult
import com.daftari.app.ui.auth.EntryMode
import com.daftari.app.ui.auth.EntryScreen
import com.daftari.app.ui.auth.SetPasswordScreen
import com.daftari.app.core.Gate
import com.daftari.app.ui.auth.SetupScreen
import com.daftari.app.ui.chat.ChatScreen
import com.daftari.app.ui.common.DaftariClip
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.T
import com.daftari.app.ui.extra.ImportScreen
import com.daftari.app.ui.extra.PasswordPrompt
import com.daftari.app.ui.extra.PasswordSetup
import com.daftari.app.ui.extra.ReportKind
import com.daftari.app.ui.extra.ReportsMenu
import com.daftari.app.ui.home.HomeActions
import com.daftari.app.ui.home.HomeScreen
import com.daftari.app.ui.parties.DetailsActions
import com.daftari.app.ui.parties.MoneySheet
import com.daftari.app.ui.parties.PartiesScreen
import com.daftari.app.ui.parties.PartyDetailsScreen
import com.daftari.app.ui.parties.PartyEditScreen
import com.daftari.app.ui.reports.Range
import com.daftari.app.ui.reports.ReportScreen
import com.daftari.app.ui.settings.SettingsActions
import com.daftari.app.ui.settings.SettingsScreen
import com.daftari.app.ui.theme.DaftariTheme
import com.daftari.app.ui.theme.Df
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Screens pushed above the bottom tabs. */
sealed interface Route {
    data class Details(val id: Long) : Route
    data class Edit(val id: Long?) : Route
    data class ReportOf(val kind: String, val partyId: Long? = null) : Route
    data object Import : Route
    data object Paywall : Route
    data object Password : Route
    data object Reports : Route
    data object Log : Route
    data class LogDetail(val seq: Long) : Route
    data object Backup : Route
    data object Migrate : Route
    data object Rates : Route
}

/** A pending double-lock (fingerprint + PIN) and what to do after it. */
private data class GateReq(val title: String, val subtitle: String, val onOk: () -> Unit)


class MainActivity : FragmentActivity() {
    companion object { const val EXTRA_PARTY = "party_id" }

    private val openParty = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openParty.value = intent?.getLongExtra(EXTRA_PARTY, 0L)?.takeIf { it > 0 }
        Alerts.ensureChannels(this)
        DailyWorker.schedule(this)
        setContent {
            val vm: AppViewModel = viewModel()
            val s by vm.settings.collectAsStateWithLifecycle()
            DaftariTheme(s?.theme ?: com.daftari.app.data.ThemeMode.DARK_BLUE) {
                Box(Modifier.fillMaxSize().background(Df.c.bg)) { Root(vm, s, this@MainActivity, openParty) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getLongExtra(EXTRA_PARTY, 0L).takeIf { it > 0 }?.let { openParty.value = it }
    }
}

fun rangeDates(r: Range, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (r) {
    Range.MONTH -> today.withDayOfMonth(1) to today
    Range.LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
    Range.YEAR -> today.withDayOfYear(1) to today
    Range.ALL -> LocalDate.of(2000, 1, 1) to today
}

@Composable
private fun Root(vm: AppViewModel, s: AppSettings?, act: FragmentActivity, openParty: androidx.compose.runtime.MutableState<Long?>) {
    val ctx = LocalContext.current
    val toast by vm.toast.collectAsState()
    LaunchedEffect(toast) { toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.toast.value = null } }
    var splashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshServerInfo(); delay(1750); /* one full cycle of the v2 clip (1.67 s) */ splashDone = true }
    val unlocked by vm.unlocked.collectAsState()
    val resetting by vm.resettingPassword.collectAsState()
    val restart = { com.daftari.app.core.AppRestart.restart(act) }
    AnimatedContent(
        targetState = if (s == null || !splashDone) -1 else com.daftari.app.core.AuthGate.of(s, unlocked, resetting).ordinal,
        transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root",
    ) { stage ->
        when (stage) {
            -1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DaftariClip(280.dp, loop = false) }
            Gate.FIRST_RUN.ordinal, Gate.SETUP.ordinal -> OnboardingFlow(vm, s!!, act, stage == Gate.SETUP.ordinal)
            Gate.SET_PASSWORD.ordinal -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                SetPasswordScreen(s!!.googleEmail, Biometric.available(act), resetting,
                    onEnableBiometric = { done -> Biometric.prompt(act, "تفعيل البصمة", "ضع إصبعك للتأكيد") { ok -> done(ok) } },
                    onSave = { pw, bio -> vm.setAppPassword(pw, bio, restart) })
                BackupOverlays(vm)
            }
            Gate.RETURNING.ordinal -> ReturningScreen(vm, s!!, act, restart)
            else -> MainShell(vm, s!!, act, openParty)
        }
    }
}

/** Every launch after the first: the same entry design, the app password + fingerprint, and the role cards (محل / سوق, same account). */
@Composable
private fun ReturningScreen(vm: AppViewModel, s: AppSettings, act: FragmentActivity, restart: () -> Unit) {
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(s.role) }
    val canBio = s.biometricOn && Biometric.available(act)
    val note = when {
        role == s.role -> null
        vm.workspaceExists(role) -> "سيُفتح دفتر «${role.labelAr}» بعد الدخول"
        else -> "سيُنشأ دفتر جديد منفصل لـ«${role.labelAr}» بالحساب نفسه بعد الدخول"
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        EntryScreen(EntryMode.RETURNING, role, { role = it }, roleNote = note, account = s.googleEmail,
            hasBiometric = canBio, autoBiometric = canBio,
            onBiometric = { Biometric.prompt(act, "فتح دفتري", "استخدم البصمة") { ok -> if (ok) vm.unlockAs(role, restart) } },
            onPassword = { pw -> vm.tryPassword(pw).also { if (it == null) vm.unlockAs(role, restart) } },
            onForgot = {
                if (s.googleEmail.isBlank()) vm.say("لاستعادة كلمة السر يلزم ربط حساب Google، ولم يُربط بعد على هذا الجوال.")
                else scope.launch {
                    when (val r = GoogleAuth.signIn(act, com.daftari.app.backup.GoogleDrive.webClientId, "التحقق بحساب Google")) {
                        is GoogleResult.Ok -> vm.forgotVerified(r.email)
                        is GoogleResult.Failed -> vm.say(r.message)
                        GoogleResult.Cancelled -> Unit
                    }
                }
            })
        BackupOverlays(vm)
    }
}

@Composable
private fun OnboardingFlow(vm: AppViewModel, s: AppSettings, act: FragmentActivity, setup: Boolean) {
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(if (s.onboarded) s.role else Role.SHOP_OWNER) }
    var googleError by remember { mutableStateOf<String?>(null) }
    var googleFailed by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openImport(uri) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openBackup(uri) }
    val importState by vm.importState.collectAsState()
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val imp = importState
        if (imp != null) {
            MigratePreview(imp.tables, imp.index, imp.plan, imp.remembered, imp.done, onTable = { vm.importSelectTable(it) }, onField = { i, f -> vm.importSetField(i, f) },
                onLayout = { vm.importSetLayout(it) }, onConfirm = { vm.importConfirm() }, onBack = { vm.importState.value = null },
                onFinish = { vm.importState.value = null; if (setup) { vm.finishSetup(s.role, "", s.ownerName.ifBlank { s.googleName }, 0, com.daftari.app.core.Currency.YER, false); vm.startTrialIfNeeded() } })
            BackupOverlays(vm)
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
        if (!setup) EntryScreen(EntryMode.FIRST_RUN, role, { role = it },
            googleReady = com.daftari.app.backup.GoogleDrive.webClientId.isNotBlank(),
            googleError = googleError,
            onWithoutGoogle = if (googleFailed) ({ vm.continueWithoutGoogle(role) }) else null,
            onGoogle = {
                googleError = null
                scope.launch {
                    when (val r = GoogleAuth.signIn(act, com.daftari.app.backup.GoogleDrive.webClientId)) {
                        is GoogleResult.Ok -> vm.googleSignedIn(r.email, r.name, role)
                        is GoogleResult.Failed -> { googleError = r.message; googleFailed = true }
                        GoogleResult.Cancelled -> Unit
                    }
                }
            })
        else SetupScreen(s.role, {}, s.ownerName.ifBlank { s.googleName }, showRole = false,
            onDone = { business, owner, shops, currency, demo -> vm.finishSetup(s.role, business, owner, shops, currency, demo); vm.startTrialIfNeeded() },
            onImport = { importLauncher.launch(arrayOf("*/*")) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.daftari.app.ui.common.GhostButton("استرجاع نسخة", Modifier.weight(1f), R.drawable.ic_arrow_down_left, height = 44.dp) { restoreLauncher.launch(arrayOf("*/*")) }
            com.daftari.app.ui.common.GhostButton("انتقل من تطبيق آخر", Modifier.weight(1f), R.drawable.ic_file_spreadsheet, height = 44.dp) { importLauncher.launch(arrayOf("*/*")) }
        }
        }
        BackupOverlays(vm)
    }
}

private data class Tab(val label: String, val icon: Int)

@Composable
private fun MainShell(vm: AppViewModel, s: AppSettings, act: FragmentActivity, openParty: androidx.compose.runtime.MutableState<Long?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val d by vm.dashboard.collectAsStateWithLifecycle()
    val info by vm.serverInfo.collectAsState()
    val busy by vm.busy.collectAsState()
    val role = s.role
    val landlord = role == Role.LANDLORD
    var tab by remember { mutableIntStateOf(0) }
    val stack = remember { androidx.compose.runtime.mutableStateListOf<Route>() }
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    // partyId, isDebt — saveable (1.1.3): the sheet must still be open when we come back from the camera, even after rotation or process death
    var money by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Pair<Long, Boolean>?>(null) }
    // 1.1.6 voice + quick entry: the next money sheet's pre-fill (VoicePrefill.encode(), "" = none), the listening dialog,
    // the parsed sentence under review, and the quick customer picker (true = دين جديد, false = تسديد/دفعة)
    var prefill by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var voiceListen by remember { mutableStateOf(false) }
    var voiceDraft by remember { mutableStateOf<com.daftari.app.voice.VoiceDraft?>(null) }
    var quickPick by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pickKeepsPrefill by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var pwPrompt by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    val paywall = { push(Route.Paywall) }
    var gate by remember { mutableStateOf<GateReq?>(null) }
    var bioDone by remember { mutableStateOf(false) }
    // 1.1.5: maintenance / broadcast / subscription status on launch and every resume (never blocks local use)
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refreshRemote(); onPauseOrDispose { } }
    var restoreAsk by remember { mutableStateOf<com.daftari.app.audit.AuditRecord?>(null) }
    var logActions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var logQuery by remember { mutableStateOf("") }
    var exportSheet by remember { mutableStateOf(false) }
    // 1.1.3: the receipt of the open payment sheet = one shrunk JPEG in cacheDir/receipts (path survives rotation / process death)
    var receiptPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // the file the camera app is writing right now (TakePicture target), kept until its result arrives
    var cameraPending by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var receiptThumb by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val logUnlocked by vm.logUnlocked.collectAsState()
    /** fingerprint AND PIN, then [ok] (the log, every restore) */
    fun secure(title: String, subtitle: String, ok: () -> Unit) { bioDone = false; gate = GateReq(title, subtitle, ok) }
    val navImport by vm.navImport.collectAsState()
    LaunchedEffect(navImport) {
        if (navImport) { vm.navImport.value = false; val r = if (vm.importState.value != null) Route.Migrate else Route.Import; if (stack.lastOrNull() != r) push(r) }
    }
    LaunchedEffect(stack.lastOrNull()) { if (stack.none { it is Route.Log || it is Route.LogDetail }) vm.lockLog() }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }
    LaunchedEffect(openParty.value) { openParty.value?.let { push(Route.Details(it)); openParty.value = null } }
    BackHandler(stack.isNotEmpty() || money != null || tab != 0 || gate != null || restoreAsk != null || exportSheet || voiceDraft != null || quickPick != null) {
        when {
            gate != null -> gate = null
            restoreAsk != null -> restoreAsk = null
            exportSheet -> exportSheet = false
            pwPrompt != null -> pwPrompt = null
            voiceDraft != null -> voiceDraft = null
            quickPick != null -> { quickPick = null; pickKeepsPrefill = false; prefill = "" }
            money != null -> { money = null; prefill = "" }
            stack.isNotEmpty() -> pop()
            else -> tab = 0
        }
    }

    /** Fingerprint, or the password prompt, then [ok]. Without any lock set, asks to set a password first. */
    fun confirm(title: String, ok: () -> Unit) {
        if (Biometric.available(act)) Biometric.prompt(act, title, "تأكيد العملية") { good -> if (good) ok() else if (s.hasPassword) pwPrompt = title to ok }
        else if (s.hasPassword) pwPrompt = title to ok
        else { vm.say("اضبط كلمة سر أولاً للتأكيد"); push(Route.Password) }
    }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val top = stack.lastOrNull()
        if (uri != null && top is Route.Details) vm.attach(top.id, uri)
    }
    var smartPick by remember { mutableStateOf(true) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri, smartPick) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openBackup(uri) }
    val chatFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openImport(uri, toChat = true) }
    val migrateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openImport(uri) }
    val saveBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(com.daftari.app.backup.DaftariFile.MIME)) { uri -> if (uri != null) vm.saveBackupTo(uri) }
    LaunchedEffect(receiptPath) {
        val p = receiptPath
        receiptThumb = if (p == null) null else withContext(Dispatchers.IO) { ImageUtil.thumbnail(p)?.asImageBitmap() }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { com.daftari.app.core.ReceiptCapture.sweep(ctx.cacheDir, listOf(receiptPath, cameraPending)) } } }
    fun setReceipt(path: String?) {
        val old = receiptPath
        receiptPath = path
        if (old != null && old != path) com.daftari.app.core.ReceiptCapture.discard(ctx.cacheDir, old)
    }
    /** gallery and camera photos are stored the same way: shrunk to one small upright JPEG */
    fun shrinkReceipt(open: () -> java.io.InputStream?, cleanup: () -> Unit = {}) {
        scope.launch {
            val dest = com.daftari.app.core.ReceiptCapture.newReceiptFile(ctx.cacheDir)
            val ok = withContext(Dispatchers.IO) { runCatching { ImageUtil.compressReceipt(open, dest) }.getOrDefault(false).also { cleanup() } }
            if (ok && money != null) setReceipt(dest.path)
            else { dest.delete(); if (!ok) vm.say("تعذّرت قراءة الصورة، فاختر صورة أخرى.") }
        }
    }
    val receiptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) shrinkReceipt({ ctx.contentResolver.openInputStream(uri) })
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        // cancelled / failed → the empty target file is deleted; taken → shrunk into the sheet, original deleted
        val shot = com.daftari.app.core.ReceiptCapture.settle(taken, cameraPending)
        cameraPending = null
        if (shot != null) shrinkReceipt({ shot.inputStream() }) { shot.delete() }
    }
    fun openCamera() {
        com.daftari.app.core.ReceiptCapture.discard(ctx.cacheDir, cameraPending)
        val f = com.daftari.app.core.ReceiptCapture.newCameraFile(ctx.cacheDir)
        cameraPending = f.path
        try {
            cameraLauncher.launch(com.daftari.app.share.Share.uriFor(ctx, f))
        } catch (e: android.content.ActivityNotFoundException) {
            com.daftari.app.core.ReceiptCapture.discard(ctx.cacheDir, f.path); cameraPending = null
            vm.say("لا يوجد تطبيق للكاميرا على هذا الجهاز، فاختر صورة الإيصال من المعرض.")
            runCatching { receiptLauncher.launch("image/*") }
        }
    }
    val driveAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res -> vm.driveAuthResult(res.data) }
    // 1.1.4: signed in with Google but Drive not linked yet → ask for drive.appdata on the same account once (the daily copy is mandatory)
    LaunchedEffect(s.googleEmail, s.driveAccount) {
        if (com.daftari.app.backup.GoogleDrive.webClientId.isNotBlank()) vm.driveAutoConnect { pi -> driveAuthLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()) }
    }
    val contactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
        if (uri != null) scope.launch {
            picked = withContext(Dispatchers.IO) { readContact(ctx, uri) }
        }
    }
    val contactPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) contactLauncher.launch(null) else vm.say("نحتاج إذن جهات الاتصال") }

    val summaries = d?.summaries.orEmpty()
    fun summaryOf(id: Long): PartySummary? = summaries.firstOrNull { it.party.id == id }
    val tabs = listOf(Tab("الرئيسية", R.drawable.ic_house), Tab(role.partyLabel, if (landlord) R.drawable.ic_store else R.drawable.ic_users),
        Tab("مدين", R.drawable.ic_sparkles), Tab("التقارير", R.drawable.ic_file_text), Tab("الإعدادات", R.drawable.ic_settings))

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        val top = stack.lastOrNull()
        if (top == null) {
            Column(Modifier.fillMaxSize()) {
                val st by vm.appStatus.collectAsState()
                val hidden by vm.maintenanceHidden.collectAsState()
                val mt = st?.maintenance
                androidx.compose.animation.AnimatedVisibility(mt != null && mt.on && !mt.fullScreen && !hidden) {
                    com.daftari.app.ui.subs.MaintenanceBanner(mt?.message.orEmpty()) { vm.maintenanceHidden.value = true }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> d?.let { dash ->
                            HomeScreen(dash, role, s.businessName, s.ownerName, s.currency, LocalDate.now(), HomeActions(
                                onSearch = { tab = 1 }, onQuickPay = { prefill = ""; quickPick = false }, onQuickDebt = { prefill = ""; quickPick = true }, onVoice = { voiceListen = true },
                                onNewParty = { if (vm.gate(Plan.Feature.UNLIMITED, summaries.size, paywall)) push(Route.Edit(null)) },
                                onStatements = { push(Route.ReportOf(ReportKind.ALL_TOTALS.name)) }, onChat = { tab = 2 },
                                onParty = { push(Route.Details(it)) }, onSeeAll = { tab = 1 },
                                onRemind = { ps -> Share.whatsappText(ctx, ps.party.phone, vm.reminderText(ps.party, ps.balance)) },
                            ))
                        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.daftari.app.ui.common.ClipLoading() }
                        1 -> {
                            val entryText by remember { vm.repo.entries }.collectAsState(initial = emptyList())
                            val text = remember(entryText) { entryText.groupBy { it.partyId }.mapValues { (_, l) -> l.joinToString(" ") { it.note + " " + it.items } } }
                            PartiesScreen(summaries, role, s.currency, onOpen = { push(Route.Details(it)) },
                                onAdd = { if (vm.gate(Plan.Feature.UNLIMITED, summaries.size, paywall)) push(Route.Edit(null)) },
                                onImport = { push(Route.Import) }, entryText = text)
                        }
                        2 -> {
                            val msgs by vm.chat.collectAsState(); val chatBusy by vm.chatBusy.collectAsState()
                            ChatScreen(msgs, chatBusy, role,
                                onSend = { q -> if (vm.gate(Plan.Feature.AI_CHAT, onPaywall = paywall)) vm.ask(q) },
                                onConfirm = { m -> m.action?.let { a ->
                                    if (a.offline && a.type !in setOf("undo", "import_backup")) vm.runAction(m.id, a, true)
                                    else confirm("تأكيد طلب مدين") { vm.runAction(m.id, a, true) }
                                } },
                                onChoice = { m, a -> vm.pickChoice(m.id, a) },
                                onAttach = { chatFileLauncher.launch(arrayOf("*/*")) },
                                onCancel = { m -> m.action?.let { a -> vm.runAction(m.id, a, false) } },
                                onExportTable = { t -> vm.export({ com.daftari.app.export.ReportBuilder.custom(t.title, t.columns, t.rows, s.businessName, s.ownerName, LocalDate.now()) }, ExportFormat.PDF) { f, fmt -> Share.file(ctx, f, fmt.mime) } },
                                onClear = { vm.clearChat() })
                        }
                        3 -> ReportsMenu(onBack = { tab = 0 }) { k ->
                            if (k == ReportKind.ALL_TOTALS || vm.gate(Plan.Feature.REPORTS, onPaywall = paywall)) push(Route.ReportOf(k.name))
                        }
                        else -> SettingsScreen(s, info, SettingsActions(
                            onTheme = { m -> vm.updateSettings { it.copy(theme = m) } },
                            onCurrency = { cur -> vm.updateSettings { it.copy(currency = cur) } },
                            onEditBusiness = { vm.updateSettings { it.copy(onboarded = false) } },
                            onLock = { on ->
                                if (!on) vm.updateSettings { it.copy(biometricOn = false) }
                                else if (!Biometric.available(act)) vm.say("لا توجد بصمة مسجّلة على هذا الجوال")
                                else Biometric.prompt(act, "تفعيل البصمة", "ضع إصبعك للتأكيد") { ok -> if (ok) vm.updateSettings { it.copy(biometricOn = true) } }
                            },
                            onSwitchRole = { target ->
                                val fresh = !vm.workspaceExists(target)
                                confirm(if (fresh) "إنشاء دفتر «${target.labelAr}» منفصل بالحساب نفسه" else "التبديل إلى دفتر «${target.labelAr}»") {
                                    vm.switchWorkspace(target) { com.daftari.app.core.AppRestart.restart(act) }
                                }
                            },
                            onPassword = { push(Route.Password) },
                            onBackup = { if (vm.gate(Plan.Feature.BACKUP, onPaywall = paywall)) vm.backupNow() },
                            onImport = { push(Route.Import) },
                            onAccount = { },
                            onSignOut = { confirm("تسجيل الخروج من Google") { vm.googleSignOut() } },
                            onWipe = { confirm("مسح كل البيانات") { vm.wipeAll {} } },
                            onPro = paywall,
                            onAlerts = { on -> vm.updateSettings { it.copy(alertsEnabled = on) } },
                            onExportDb = { vm.exportDatabase { f -> Share.file(ctx, f, "application/json", "نسخة دفتري") } },
                            onDrive = { if (vm.gate(Plan.Feature.BACKUP, onPaywall = paywall)) vm.exportDatabase { f -> Share.toDrive(ctx, f) } },
                            onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            onReports = { tab = 3 },
                            onLog = { push(Route.Log) },
                            onBackupScreen = { push(Route.Backup) },
                            onMigrate = { vm.importState.value = null; push(Route.Migrate) },
                            onRates = { vm.ratesStatus.value = null; push(Route.Rates) },
                            onDeveloper = { Share.telegramDev(ctx) },
                        ))
                    }
                }
                BottomBar(tabs, tab) { tab = it }
            }
        } else {
            Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                when (top) {
                    is Route.Details -> {
                        val sum = summaryOf(top.id)
                        val entries by remember(top.id) { vm.repo.entriesFor(top.id) }.collectAsState(initial = emptyList())
                        val atts by remember(top.id) { vm.repo.attachments(top.id) }.collectAsState(initial = emptyList())
                        if (sum == null) LaunchedEffect(top.id) { delay(400); if (summaryOf(top.id) == null) pop() }
                        else PartyDetailsScreen(sum, entries, atts, role, s.currency, DetailsActions(
                            onBack = { pop() }, onEdit = { push(Route.Edit(top.id)) },
                            onCall = { Share.call(ctx, sum.party.phone) },
                            onWhatsApp = { Share.whatsappText(ctx, sum.party.phone, vm.reminderText(sum.party, sum.balance)) },
                            onSms = { Share.sms(ctx, sum.party.phone, vm.reminderText(sum.party, sum.balance)) },
                            onStatement = { push(Route.ReportOf("PARTY", top.id)) },
                            onPay = { prefill = ""; money = top.id to false }, onDebt = { prefill = ""; money = top.id to true },
                            onPayFull = { prefill = com.daftari.app.voice.VoicePrefill(amount = com.daftari.app.core.QuickEntry.fullBalanceText(sum.balance)).encode(); money = top.id to false },
                            onDeleteEntry = { e -> confirm("حذف العملية") { vm.deleteEntry(e.id) } },
                            onAttach = { attachLauncher.launch(arrayOf("image/*", "application/pdf")) },
                            onOpenAttachment = { a -> Share.open(ctx, vm.attachmentFile(a.fileName), a.mime) },
                            onDeleteAttachment = { a -> confirm("حذف المرفق") { scope.launch { vm.repo.deleteAttachment(a) } } },
                        ))
                    }
                    is Route.Edit -> {
                        var initial by remember(top.id) { mutableStateOf<Party?>(null) }
                        var loaded by remember(top.id) { mutableStateOf(top.id == null) }
                        LaunchedEffect(top.id) { if (top.id != null) { initial = vm.repo.findParty(top.id); loaded = true } }
                        if (loaded) PartyEditScreen(initial, role, s.currency, onBack = { picked = null; pop() },
                            onSave = { p -> vm.saveParty(p) { id -> picked = null; pop(); if (top.id == null) push(Route.Details(id)) } },
                            onDelete = top.id?.let { id -> { confirm("حذف الحساب وكل عملياته") { vm.deleteParty(id) { stack.clear() } } } },
                            categories = summaries.map { it.party.category }.filter { it.isNotBlank() }.distinct(),
                            pickedContact = picked,
                            onPickContact = {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) contactLauncher.launch(null)
                                else contactPerm.launch(Manifest.permission.READ_CONTACTS)
                            })
                    }
                    is Route.ReportOf -> ReportRoute(vm, s, top, onBack = { pop() }, paywall = paywall)
                    Route.Import -> {
                        val res by vm.importResult.collectAsState()
                        ImportScreen(res, busy != null, info?.aiReady == true, role, s.currency,
                            onBack = { vm.importResult.value = null; pop() },
                            onPick = { smart -> if (!smart || vm.gate(Plan.Feature.SMART_IMPORT, onPaywall = paywall)) { smartPick = smart; importLauncher.launch(arrayOf("*/*")) } },
                            onConfirm = { rows -> if (vm.gate(Plan.Feature.UNLIMITED, summaries.size + rows.size - 1, paywall)) vm.confirmImport(rows, s.businessName, s.ownerName) { vm.importResult.value = null; pop(); tab = 1 } })
                    }
                    Route.Paywall -> SubscriptionRoute(vm, s, act, onBack = { pop() })
                    Route.Password -> PasswordSetup(onBack = { pop() }) { pw -> vm.setPassword(pw); vm.say("تم حفظ كلمة السر ✅"); pop() }
                    Route.Reports -> ReportsMenu(onBack = { pop() }) { k -> push(Route.ReportOf(k.name)) }
                    Route.Log -> {
                        if (!logUnlocked) {
                            LaunchedEffect(Unit) { if (gate == null) secure("السجل", "السجل محمي: يُفتح بالبصمة والرمز معاً") { vm.logUnlocked.value = true; vm.verifyLog() } }
                            Box(Modifier.fillMaxSize())
                        } else {
                            val all by vm.logRecords.collectAsState()
                            val ver by vm.logVerify.collectAsState()
                            val shown = remember(all, logActions, logQuery) { vm.filterLog(all, AppViewModel.LogFilter(actions = logActions, query = logQuery)) }
                            LogListScreen(shown, ver, logActions, logQuery, { logActions = it }, { logQuery = it }, onOpen = { push(Route.LogDetail(it.seq)) },
                                onExport = { exportSheet = true }, onBack = { pop() })
                        }
                    }
                    is Route.LogDetail -> {
                        val all by vm.logRecords.collectAsState()
                        val r = all.firstOrNull { it.seq == top.seq }
                        if (r == null || !logUnlocked) LaunchedEffect(Unit) { pop() }
                        else LogDetailScreen(r, all.firstOrNull { it.refSeq == r.seq && it.action == com.daftari.app.audit.Action.RESTORE }, onRestore = { restoreAsk = r }, onBack = { pop() })
                    }
                    Route.Backup -> {
                        val files by vm.driveFiles.collectAsState(); val err by vm.driveError.collectAsState(); val setup by vm.driveSetupMissing.collectAsState()
                        LaunchedEffect(s.driveAccount) { if (s.driveAccount.isNotBlank()) vm.driveList() }
                        var pwDialog by remember { mutableStateOf(false) }
                        BackupScreen(BackupUi(s.driveAccount, s.backupMinuteOfDay, s.lastDriveBackupAt, s.lastBackupAt, files, err, setup, com.daftari.app.backup.GoogleDrive.webClientId.isNotBlank(), s.backupEncrypted),
                            BackupActions(onBack = { pop() },
                                onConnect = {
                                    scope.launch {
                                        // 1) Google sign-in with the WEB client id picks the account (and gives its email); 2) Drive asks for drive.appdata on THAT account
                                        val webId = com.daftari.app.backup.GoogleDrive.webClientId
                                        var account: String? = null
                                        if (webId.isNotBlank()) when (val r = GoogleAuth.signIn(act, webId)) {
                                            is GoogleResult.Ok -> account = r.email
                                            GoogleResult.Cancelled -> return@launch
                                            is GoogleResult.Failed -> if (r.kind == com.daftari.app.ui.auth.GoogleErrors.Kind.DEVELOPER || r.kind == com.daftari.app.ui.auth.GoogleErrors.Kind.NETWORK || r.kind == com.daftari.app.ui.auth.GoogleErrors.Kind.PLAY_SERVICES) {
                                                vm.driveFailed(r.message, r.kind == com.daftari.app.ui.auth.GoogleErrors.Kind.DEVELOPER); return@launch
                                            } // otherwise Drive's own chooser below can still add/pick an account
                                        }
                                        vm.driveConnect(account ?: s.email.takeIf { "@" in it }) { pi -> driveAuthLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()) }
                                    }
                                },
                                onBackupNow = { vm.driveBackupNow() }, onRefresh = { vm.driveList() },
                                onTime = { android.app.TimePickerDialog(act, { _, h, m -> vm.setBackupTime(h * 60 + m) }, s.backupHour, s.backupMinute, false).show() },
                                onRestore = { f -> vm.driveRestore(f) }, onSaveToDrive = { saveBackupLauncher.launch(vm.backupFileName()) },
                                onImportFile = { restoreLauncher.launch(arrayOf("*/*")) }, onPassword = { pwDialog = true }))
                        if (pwDialog) BackupPasswordDialog(if (s.backupEncrypted) "تغيير كلمة سر النسخ" else "كلمة سر النسخ", "تحمي ملف النسخة إذا وصل إلى أحد. لا تُحفظ في أي مكان — إذا نسيتها فلن تُفتح النسخ المشفّرة.", true,
                            onDone = { vm.setBackupPassword(it); pwDialog = false }, onClear = if (s.backupEncrypted) ({ vm.setBackupPassword(null); pwDialog = false }) else null, onDismiss = { pwDialog = false })
                    }
                    Route.Migrate -> {
                        val st by vm.importState.collectAsState()
                        val imp = st
                        if (imp == null) MigrateStart(onPick = { migrateLauncher.launch(arrayOf("*/*")) }, onBack = { pop() })
                        else MigratePreview(imp.tables, imp.index, imp.plan, imp.remembered, imp.done, onTable = { vm.importSelectTable(it) }, onField = { i, f -> vm.importSetField(i, f) },
                            onLayout = { vm.importSetLayout(it) }, onConfirm = { vm.importConfirm() }, onBack = { vm.importState.value = null; pop() }, onFinish = { vm.importState.value = null; pop(); tab = 1 })
                    }
                    Route.Rates -> {
                        val status by vm.ratesStatus.collectAsState()
                        RatesScreen(s.rates, status, onSave = { vm.saveRates(it) }, onFetch = { vm.fetchRates() }, onBack = { pop() })
                    }
                }
            }
        }

        money?.let { (pid, debt) ->
            val sum = summaryOf(pid)
            val pf = com.daftari.app.voice.VoicePrefill.decode(prefill)
            if (sum != null) Box(Modifier.fillMaxSize().background(Df.c.bg.copy(alpha = 0.6f)).clickable { money = null; prefill = "" }, contentAlignment = Alignment.BottomCenter) {
                // keyed so a switch دين ⇄ تسديد (1.1.6) opens a fresh sheet with its own pre-fill
                androidx.compose.runtime.key(pid, debt, prefill) { Box(Modifier.clickable(enabled = false) {}) {
                    MoneySheet(debt, if (debt) "دين جديد" else "تسديد", sum.party.title, sum.balance, sum.party.periodicAmount, sum.party.cur(s.currency), landlord,
                        onClose = { money = null; prefill = ""; setReceipt(null); vm.pendingReceipt = null }, rates = s.rates, onFx = { vm.pendingFx = it }, onMethod = { vm.pendingMethod = it },
                        onReceipt = { receiptLauncher.launch("image/*") }, receiptAttached = receiptPath != null,
                        onCamera = { openCamera() }, receiptThumb = receiptThumb, onRemoveReceipt = { setReceipt(null) },
                        transferUsed = { vm.transferWarning(it) },
                        initialCurrency = pf?.currency, initialAmount = pf?.amount.orEmpty(), voice = pf,
                        onSwitchKind = { v -> prefill = v.encode(); money = pid to !debt }) { amount, date, note, extra1, extra2, due, _ ->
                        // the view model copies the photo into the payment's attachments, then deletes the temporary file
                        if (!debt) vm.pendingReceipt = receiptPath?.let { Uri.fromFile(java.io.File(it)) } else setReceipt(null)
                        receiptPath = null
                        if (debt) vm.addCharge(pid, amount, date, note, extra1, due) else vm.addPayment(pid, amount, date, note, extra1, extra2)
                        money = null; prefill = ""
                    }
                } }
            }
        }
        // ---- 1.1.6: the mic on the home screen → listen → review what was understood → the money sheet pre-filled
        if (voiceListen) com.daftari.app.voice.VoiceCaptureDialog(
            "سجّل على محمد صالح ألفين وخمسمئة",
            onText = { text ->
                voiceListen = false
                val book = summaries.flatMap { ps ->
                    listOf(com.daftari.app.voice.VoiceCustomer(ps.party.id, ps.party.title)) +
                        listOfNotNull(com.daftari.app.voice.VoiceCustomer(ps.party.id, ps.party.name).takeIf { ps.party.name.isNotBlank() && ps.party.name != ps.party.title })
                }
                voiceDraft = com.daftari.app.voice.VoiceEntryParser.parse(text, book)
            },
            onDismiss = { voiceListen = false },
        )
        voiceDraft?.let { d ->
            Box(Modifier.fillMaxSize().background(Df.c.bg.copy(alpha = 0.6f)).clickable { voiceDraft = null }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.clickable(enabled = false) {}) {
                    com.daftari.app.voice.VoiceReviewSheet(
                        d, landlord, s.currency, titleOf = { id -> summaryOf(id)?.party?.title },
                        onContinue = { id, debt -> prefill = com.daftari.app.voice.VoicePrefill.of(d).encode(); voiceDraft = null; money = id to debt },
                        onNewCustomer = { name, debt ->
                            if (landlord) { picked = name to ""; voiceDraft = null; push(Route.Edit(null)) }
                            else if (vm.gate(Plan.Feature.UNLIMITED, summaries.size, paywall)) {
                                val pfText = com.daftari.app.voice.VoicePrefill.of(d).encode()
                                voiceDraft = null
                                vm.saveParty(Party(kind = com.daftari.app.domain.PartyKind.CUSTOMER, name = name.trim(), currency = d.currency?.code.orEmpty())) { id ->
                                    prefill = pfText; money = id to debt
                                }
                            }
                        },
                        onPickFromList = { debt ->
                            if (debt == null) vm.say("اختر نوع العملية أولاً")
                            else { prefill = com.daftari.app.voice.VoicePrefill.of(d).encode(); pickKeepsPrefill = true; voiceDraft = null; quickPick = debt }
                        },
                        onRetry = { voiceDraft = null; voiceListen = true },
                        onClose = { voiceDraft = null },
                    )
                }
            }
        }
        // ---- 1.1.6: quick customer picker for «دين جديد / تسديد» on the home screen (recent 5 + search + mic)
        quickPick?.let { debt ->
            val allEntries by remember { vm.repo.entries }.collectAsState(initial = emptyList())
            val recent = remember(allEntries, summaries) { com.daftari.app.core.QuickEntry.recentPartyIds(allEntries, summaries.map { it.party.id }.toSet()) }
            val close = { quickPick = null; pickKeepsPrefill = false; prefill = "" }
            Box(Modifier.fillMaxSize().background(Df.c.bg.copy(alpha = 0.6f)).clickable { close() }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.clickable(enabled = false) {}) {
                    com.daftari.app.ui.parties.QuickPickSheet(
                        if (debt) "دين جديد" else if (landlord) "تسجيل دفعة" else "تسديد", summaries, recent, s.currency,
                        onPick = { id -> if (!pickKeepsPrefill) prefill = ""; pickKeepsPrefill = false; quickPick = null; money = id to debt },
                        onVoice = { close(); voiceListen = true },
                        onNew = { close(); if (vm.gate(Plan.Feature.UNLIMITED, summaries.size, paywall)) push(Route.Edit(null)) },
                        onClose = close,
                    )
                }
            }
        }
        busy?.let { b -> if (stack.lastOrNull() != Route.Import) Box(Modifier.fillMaxSize().background(Df.c.bg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) { com.daftari.app.ui.common.ClipLoading(b) } }
        pwPrompt?.let { (title, ok) -> PasswordPrompt(title, onDismiss = { pwPrompt = null }) { pw -> vm.checkPassword(pw).also { good -> if (good) { pwPrompt = null; ok() } } } }
        restoreAsk?.let { r ->
            RestoreConfirm(r, onConfirm = { restoreAsk = null; secure("تأكيد الاسترجاع", "الاسترجاع يتطلب البصمة والرمز") { vm.restoreFromLog(r.seq) } }, onDismiss = { restoreAsk = null })
        }
        if (exportSheet) {
            val all by vm.logRecords.collectAsState(); val ver by vm.logVerify.collectAsState()
            LogExportSheet(all.size, summaries.map { it.party.name }.distinct(), logActions,
                preview = { f, t, p, a -> vm.filterLog(all, AppViewModel.LogFilter(f, t, p, a)).size },
                onExport = { f, t, p, a, fmt ->
                    exportSheet = false
                    val filter = AppViewModel.LogFilter(f, t, p, a)
                    val rows = vm.filterLog(all, filter)
                    val format = when (fmt) { "XLSX" -> ExportFormat.XLSX; "DOCX" -> ExportFormat.DOCX; "PNG" -> ExportFormat.PNG; else -> ExportFormat.PDF }
                    vm.export({ vm.logReport(rows, filter, ver) }, format) { file, ff -> Share.file(ctx, file, ff.mime, "كشف السجل") }
                }, onDismiss = { exportSheet = false })
        }
        BackupOverlays(vm)
        ServerOverlays(vm, onRenew = { if (stack.lastOrNull() != Route.Paywall) push(Route.Paywall) })
        gate?.let { g0 ->
            val bio = Biometric.state(act)
            SecureGate(g0.title, g0.subtitle, bio, bioDone, s.hasPassword,
                onBiometric = { Biometric.promptStrict(act, g0.title) { ok -> if (ok) bioDone = true } },
                onEnroll = { runCatching { act.startActivity(Intent(if (Build.VERSION.SDK_INT >= 30) android.provider.Settings.ACTION_BIOMETRIC_ENROLL else android.provider.Settings.ACTION_SECURITY_SETTINGS)) } },
                onPin = { pin -> if (!(bioDone || bio == com.daftari.app.ui.log.Bio.NO_HARDWARE)) "استخدم البصمة أولاً" else vm.tryPassword(pin).also { e -> if (e == null) { gate = null; g0.onOk() } } },
                lockedNow = { vm.pinGuard.remainingMs().takeIf { it > 0 }?.let { vm.pinGuard.lockedText(it) } },
                onSetPin = { pin -> vm.setPassword(pin); vm.say("تم ضبط الرمز ✅"); gate = null; g0.onOk() },
                onCancel = { gate = null; if (stack.lastOrNull() == Route.Log && !vm.logUnlocked.value) pop() })
        }
    }
}

/** Backup preview (counts, checksum) and «كلمة سر النسخ» for an encrypted file — shown wherever a backup was opened. */
@Composable
private fun BackupOverlays(vm: AppViewModel) {
    val preview by vm.backupPreview.collectAsState()
    val needs by vm.backupNeedsPassword.collectAsState()
    val result by vm.backupResult.collectAsState()
    preview?.let { p ->
        val debts = p.data.entries.count { it.type == com.daftari.app.domain.EntryType.CHARGE }
        BackupPreviewSheet(p.fileName, p.data.parties.size, debts, p.data.entries.size - debts, p.data.log.size, p.data.reminders.size,
            com.daftari.app.core.Dates.short(java.time.Instant.ofEpochMilli(p.data.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()), p.warnings,
            p.manifest?.encrypted == true, onRestore = { vm.applyBackup() }, onDismiss = { vm.backupPreview.value = null })
    }
    needs?.let { (name, _) ->
        BackupPasswordDialog("النسخة مشفّرة 🔒", "اكتب «كلمة سر النسخ» التي ضبطتها وقت النسخ ($name)", false, onDone = { vm.openBackupWithPassword(it) }, onClear = null, onDismiss = { vm.backupNeedsPassword.value = null })
    }
    result?.let { r ->
        Box(Modifier.fillMaxSize().background(Df.c.bg.copy(alpha = 0.75f)).clickable { vm.backupResult.value = null }, contentAlignment = Alignment.Center) {
            com.daftari.app.ui.common.DfCard(Modifier.padding(24.dp).fillMaxWidth(), border = Df.c.ok) {
                Column(Modifier.padding(18.dp)) {
                    T(r, 14.sp, FontWeight.SemiBold, lineHeight = 24.sp)
                    com.daftari.app.ui.common.PrimaryButton("تم", Modifier.padding(top = 14.dp).fillMaxWidth()) { vm.backupResult.value = null }
                }
            }
        }
    }
}


private fun readContact(ctx: android.content.Context, uri: Uri): Pair<String, String>? {
    val cr = ctx.contentResolver
    var name = ""; var id = ""
    cr.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) { id = c.getString(0) ?: ""; name = c.getString(1) ?: "" }
    }
    if (id.isEmpty()) return null
    var phone = ""
    cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", arrayOf(id), null)?.use { c -> if (c.moveToFirst()) phone = c.getString(0) ?: "" }
    return name to (com.daftari.app.core.Phone.normalize(phone) ?: phone)
}

@Composable
private fun BottomBar(tabs: List<Tab>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Df.c
    Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                val tint = if (on) (if (c.isDark) c.primaryLight else c.primary) else c.muted
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onSelect(i) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(width = 46.dp, height = 28.dp).clip(CircleShape).then(if (on) Modifier.border(1.dp, c.outline2, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                        DfIcon(t.icon, 20.dp, tint)
                    }
                    Spacer(Modifier.height(2.dp))
                    T(t.label, 10.5.sp, if (on) FontWeight.Bold else FontWeight.Medium, tint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ReportRoute(vm: AppViewModel, s: AppSettings, r: Route.ReportOf, onBack: () -> Unit, paywall: () -> Unit) {
    val ctx = LocalContext.current
    var range by remember { mutableStateOf(if (r.kind == ReportKind.CATEGORIES.name) Range.ALL else Range.MONTH) }
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var report by remember { mutableStateOf<Report?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val party = r.partyId
    val build: suspend () -> Report? = {
        val (from, to) = rangeDates(range)
        when (r.kind) {
            "PARTY" -> vm.partyReport(party!!, from, to)
            ReportKind.ALL_DETAILS.name -> vm.entriesReport(from, to)
            ReportKind.MONTHLY.name -> vm.monthlyReport(from, to)
            ReportKind.CATEGORIES.name -> vm.categoryReport()
            else -> vm.allReport(from, to)
        }
    }
    LaunchedEffect(range, r) {
        preview = null
        val rep = build(); report = rep
        preview = rep?.let { withContext(Dispatchers.Default) { ReportRenderer(ctx).bitmap(it, 60) } }
    }
    val title = when (r.kind) { "PARTY" -> "كشف حساب"; else -> ReportKind.entries.firstOrNull { it.name == r.kind }?.labelAr ?: "كشف" }
    val sum = party?.let { id -> vm.dashboard.value?.summaries?.firstOrNull { it.party.id == id } }
    fun allowed(f: ExportFormat) = f == ExportFormat.PDF || vm.gate(Plan.Feature.EXPORT_ALL, onPaywall = paywall)
    ReportScreen(title, report, preview, range, format, canSendToParty = sum != null && sum.party.phone.isNotBlank(),
        onBack = onBack, onRange = { range = it }, onFormat = { if (allowed(it)) format = it },
        onShare = { vm.export(build, format) { f, fmt -> Share.file(ctx, f, fmt.mime, report?.title.orEmpty()) } },
        onOpen = { vm.export(build, format) { f, fmt -> Share.open(ctx, f, fmt.mime) } },
        onWhatsApp = { sum?.let { ps -> vm.export(build, ExportFormat.PDF) { f, fmt -> Share.whatsappFile(ctx, ps.party.phone, f, fmt.mime, vm.reminderText(ps.party, ps.balance)) } } },
        onSms = { sum?.let { ps -> Share.sms(ctx, ps.party.phone, vm.reminderText(ps.party, ps.balance)) } },
    )
}


/** 1.1.5 «الاشتراكات»: server plans and methods, the receipt from the camera (1.1.3 TakePicture flow + compression) or the gallery. */
@Composable
private fun SubscriptionRoute(vm: AppViewModel, s: AppSettings, act: FragmentActivity, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val load by vm.subLoad.collectAsState()
    val flow by vm.subFlow.collectAsState()
    val status by vm.subStatus.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.openSubscriptions(act) }
    fun shrink(open: () -> java.io.InputStream?, cleanup: () -> Unit = {}) {
        scope.launch {
            val dest = com.daftari.app.core.ReceiptCapture.newReceiptFile(ctx.cacheDir)
            val ok = withContext(Dispatchers.IO) { runCatching { ImageUtil.compressReceipt(open, dest) }.getOrDefault(false).also { cleanup() } }
            if (ok) vm.setSubReceipt(dest.path) else { dest.delete(); vm.say("تعذّر قراءة الصورة، جرّب صورة أخرى") }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) shrink({ ctx.contentResolver.openInputStream(uri) }) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = com.daftari.app.core.ReceiptCapture.settle(taken, vm.subFlow.value.cameraPending)
        vm.updateSubFlow { it.copy(cameraPending = null) }
        if (shot != null) shrink({ shot.inputStream() }) { shot.delete() }
    }
    fun openCamera() {
        val f = com.daftari.app.core.ReceiptCapture.newCameraFile(ctx.cacheDir)
        vm.updateSubFlow { it.copy(cameraPending = f.path) }
        try { camera.launch(com.daftari.app.share.Share.uriFor(ctx, f)) }
        catch (e: Exception) { com.daftari.app.core.ReceiptCapture.discard(ctx.cacheDir, f.path); vm.updateSubFlow { it.copy(cameraPending = null) }; vm.say("لا يوجد تطبيق للكاميرا على هذا الجهاز، فاختر صورة الإيصال من المعرض.") }
    }
    val thumb = remember(flow.receiptPath) {
        flow.receiptPath?.let { p -> runCatching { android.graphics.BitmapFactory.decodeFile(p, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull() }
    }
    com.daftari.app.ui.subs.SubscriptionFlow(
        com.daftari.app.ui.subs.SubUi(load, flow, status?.plan, status?.pending, Plan.statusText(s), thumb, busy != null, s.googleEmail),
        com.daftari.app.ui.subs.SubActions(
            onBack = onBack, onRetry = { vm.openSubscriptions(act) }, onFlow = { vm.updateSubFlow(it) },
            onCopy = { label, text ->
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                if (Build.VERSION.SDK_INT < 33) vm.say("نُسخ $label")
            },
            onCamera = { openCamera() }, onGallery = { gallery.launch("image/*") }, onRemoveReceipt = { vm.setSubReceipt(null) },
            onSubmit = { vm.submitSubscription(act) }, onRefreshStatus = { vm.refreshSubStatus(act) },
            onActivationCode = { code -> vm.activatePro(code) { onBack() } },
        ),
    )
}

/** Panel-driven: full-screen maintenance, a broadcast, «تم الاشتراك» / rejection, «انتهى اشتراكك». */
@Composable
private fun ServerOverlays(vm: AppViewModel, onRenew: () -> Unit) {
    val st by vm.appStatus.collectAsState()
    val notice by vm.subNotice.collectAsState()
    val bc by vm.broadcastNotice.collectAsState()
    val expired by vm.expiredNotice.collectAsState()
    var retrying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mt = st?.maintenance
    if (mt != null && mt.fullScreen) {
        com.daftari.app.ui.subs.MaintenanceScreen(mt.message, retrying) {
            retrying = true
            vm.refreshRemote()
            scope.launch { delay(1500); retrying = false }
        }
        return
    }
    notice?.let { n -> com.daftari.app.ui.subs.SubDecisionDialog(n, onClose = { vm.dismissSubNotice() }, onRenew = { vm.dismissSubNotice(); onRenew() }) }
    if (notice == null) bc?.let { b -> com.daftari.app.ui.subs.BroadcastDialog(b) { vm.dismissBroadcast() } }
    if (notice == null && bc == null) expired?.let { name -> com.daftari.app.ui.subs.ExpiredDialog(name, onRenew = { vm.expiredNotice.value = null; onRenew() }, onClose = { vm.expiredNotice.value = null }) }
}
