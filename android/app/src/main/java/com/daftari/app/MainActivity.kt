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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daftari.app.core.Plan
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
import com.daftari.app.ui.auth.LoginScreen
import com.daftari.app.ui.auth.OtpScreen
import com.daftari.app.ui.auth.SetupScreen
import com.daftari.app.ui.chat.ChatScreen
import com.daftari.app.ui.common.DaftariClip
import com.daftari.app.ui.common.DfIcon
import com.daftari.app.ui.common.T
import com.daftari.app.ui.extra.ImportScreen
import com.daftari.app.ui.extra.LockScreen
import com.daftari.app.ui.extra.PasswordPrompt
import com.daftari.app.ui.extra.PasswordSetup
import com.daftari.app.ui.extra.PaywallScreen
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
}

private enum class Onboard { LOGIN, OTP, SETUP }

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
    LaunchedEffect(Unit) { vm.refreshServerInfo(); delay(1900); splashDone = true }
    val unlocked by vm.unlocked.collectAsState()
    AnimatedContent(
        targetState = when {
            s == null || !splashDone -> 0
            !s.onboarded -> 1
            s.lockEnabled && !unlocked -> 2
            else -> 3
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root",
    ) { stage ->
        when (stage) {
            0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DaftariClip(280.dp, loop = false) }
            1 -> OnboardingFlow(vm, act)
            2 -> LockScreen(
                hasBiometric = Biometric.available(act), hasPassword = s!!.hasPassword,
                onBiometric = { Biometric.prompt(act, "فتح دفتري", "استخدم البصمة") { ok -> if (ok) vm.unlocked.value = true } },
                onPassword = { pw -> vm.checkPassword(pw).also { if (it) vm.unlocked.value = true } },
            )
            else -> MainShell(vm, s!!, act, openParty)
        }
    }
}

@Composable
private fun OnboardingFlow(vm: AppViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Onboard.LOGIN) }
    var role by remember { mutableStateOf(Role.SHOP_OWNER) }
    var defaultOwner by remember { mutableStateOf("") }
    val info by vm.serverInfo.collectAsState()
    val phone by vm.otpPhone.collectAsState()
    val channel by vm.otpChannel.collectAsState()
    val devCode by vm.otpDevCode.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri, smart = info?.aiReady == true) }
    BackHandler(step != Onboard.LOGIN) { step = if (step == Onboard.SETUP) Onboard.LOGIN else Onboard.LOGIN }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        when (step) {
            Onboard.LOGIN -> LoginScreen(
                role = role, onRole = { role = it }, serverReady = info != null,
                googleReady = (info?.googleClientId?.isNotBlank() == true) || BuildConfig.GOOGLE_CLIENT_ID.isNotBlank(),
                onSendOtp = { p, ch -> vm.requestOtp(p, ch) { step = Onboard.OTP } },
                onGoogle = {
                    scope.launch {
                        val id = info?.googleClientId?.takeIf { it.isNotBlank() } ?: BuildConfig.GOOGLE_CLIENT_ID
                        when (val r = GoogleAuth.signIn(act, id)) {
                            is GoogleResult.Ok -> vm.googleSignedIn(r.idToken, r.email, r.name, role) { defaultOwner = r.name; step = Onboard.SETUP }
                            is GoogleResult.Failed -> vm.say(r.message)
                            GoogleResult.Cancelled -> Unit
                        }
                    }
                },
                onOffline = { vm.continueOffline(role) { step = Onboard.SETUP } },
            )
            Onboard.OTP -> OtpScreen(phone, channel, devCode, onBack = { step = Onboard.LOGIN },
                onVerify = { code, email -> vm.verifyOtp(code, role, email) { step = Onboard.SETUP } },
                onResend = { ch -> vm.requestOtp(phone, ch) {} })
            Onboard.SETUP -> SetupScreen(role, { role = it }, defaultOwner,
                onDone = { business, owner, shops, currency, demo -> vm.finishSetup(role, business, owner, shops, currency, demo); vm.startTrialIfNeeded() },
                onImport = { importLauncher.launch(arrayOf("*/*")) })
        }
    }
    @Suppress("UNUSED_EXPRESSION") ctx
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
    var money by remember { mutableStateOf<Pair<Long, Boolean>?>(null) } // partyId, isDebt
    var pwPrompt by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    val paywall = { push(Route.Paywall) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }
    LaunchedEffect(openParty.value) { openParty.value?.let { push(Route.Details(it)); openParty.value = null } }
    BackHandler(stack.isNotEmpty() || money != null || tab != 0) {
        when {
            pwPrompt != null -> pwPrompt = null
            money != null -> money = null
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
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirm("استعادة نسخة تحذف البيانات الحالية") { vm.restoreFrom(uri) {} } }
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
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> d?.let { dash ->
                            HomeScreen(dash, role, s.businessName, s.ownerName, s.currency, LocalDate.now(), HomeActions(
                                onSearch = { tab = 1 }, onQuickPay = { tab = 1; vm.say("اختر الحساب ثم «تسديد»") }, onQuickDebt = { tab = 1; vm.say("اختر الزبون ثم «دين جديد»") },
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
                                onConfirm = { m -> m.action?.let { a -> confirm("تأكيد طلب مدين") { vm.runAction(m.id, a, true) } } },
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
                            onLock = { on -> if (on && !s.hasPassword && !Biometric.available(act)) push(Route.Password) else vm.updateSettings { it.copy(lockEnabled = on) } },
                            onPassword = { push(Route.Password) },
                            onBackup = { if (vm.gate(Plan.Feature.BACKUP, onPaywall = paywall)) vm.backupNow() },
                            onImport = { push(Route.Import) },
                            onAccount = { },
                            onSignOut = { vm.signOut() },
                            onWipe = { confirm("مسح كل البيانات") { vm.wipeAll {} } },
                            onPro = paywall,
                            onAlerts = { on -> vm.updateSettings { it.copy(alertsEnabled = on) } },
                            onExportDb = { vm.exportDatabase { f -> Share.file(ctx, f, "application/json", "نسخة دفتري") } },
                            onDrive = { if (vm.gate(Plan.Feature.BACKUP, onPaywall = paywall)) vm.exportDatabase { f -> Share.toDrive(ctx, f) } },
                            onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            onReports = { tab = 3 },
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
                            onPay = { money = top.id to false }, onDebt = { money = top.id to true },
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
                    Route.Paywall -> PaywallScreen(Plan.statusText(s), s.currency, onBack = { pop() },
                        onActivate = { code -> vm.activatePro(code) { pop() } },
                        onContact = { Share.whatsappText(ctx, AppInfo_SUPPORT, "السلام عليكم، أبغى أشترك في دفتري برو. رقمي: ${s.phone}") })
                    Route.Password -> PasswordSetup(onBack = { pop() }) { pw -> vm.setPassword(pw); vm.say("تم حفظ كلمة السر ✅"); pop() }
                    Route.Reports -> ReportsMenu(onBack = { pop() }) { k -> push(Route.ReportOf(k.name)) }
                }
            }
        }

        money?.let { (pid, debt) ->
            val sum = summaryOf(pid)
            if (sum != null) Box(Modifier.fillMaxSize().background(Df.c.bg.copy(alpha = 0.6f)).clickable { money = null }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.clickable(enabled = false) {}) {
                    MoneySheet(debt, if (debt) "دين جديد" else "تسديد", sum.party.title, sum.balance, sum.party.periodicAmount, s.currency, landlord,
                        onClose = { money = null }) { amount, date, note, extra1, extra2, due, _ ->
                        if (debt) vm.addCharge(pid, amount, date, note, extra1, due) else vm.addPayment(pid, amount, date, note, extra1, extra2)
                        money = null
                    }
                }
            }
        }
        busy?.let { b -> if (stack.lastOrNull() != Route.Import) Box(Modifier.fillMaxSize().background(Df.c.bg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) { com.daftari.app.ui.common.ClipLoading(b) } }
        pwPrompt?.let { (title, ok) -> PasswordPrompt(title, onDismiss = { pwPrompt = null }) { pw -> vm.checkPassword(pw).also { good -> if (good) { pwPrompt = null; ok() } } } }
    }
}

private const val AppInfo_SUPPORT = com.daftari.app.core.AppInfo.SUPPORT_WHATSAPP

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
