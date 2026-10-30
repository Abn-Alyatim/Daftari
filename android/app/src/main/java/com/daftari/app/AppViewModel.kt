package com.daftari.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daftari.app.chat.ChatContext
import com.daftari.app.chat.ChatMessage
import com.daftari.app.chat.LocalAnswers
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.core.Phone
import com.daftari.app.core.cur
import com.daftari.app.data.ApiException
import com.daftari.app.data.AppSettings
import com.daftari.app.data.ChatAction
import com.daftari.app.data.ChatRequest
import com.daftari.app.data.ChatTurn
import com.daftari.app.data.Dashboard
import com.daftari.app.data.Demo
import com.daftari.app.data.ImportRequest
import com.daftari.app.data.PasswordHasher
import com.daftari.app.core.AuthGate
import com.daftari.app.core.WorkspaceProfile
import com.daftari.app.data.ServerInfo
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Period
import com.daftari.app.domain.Role
import com.daftari.app.export.ExportFormat
import com.daftari.app.export.Report
import com.daftari.app.export.ReportBuilder
import com.daftari.app.importer.ImportResult
import com.daftari.app.importer.ImportRow
import com.daftari.app.importer.SheetImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate

/** One ViewModel for the whole app: the data is small (one person's ledger) and every screen reads the same flows. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val g = (app as DaftariApp).graph
    val repo = g.repo

    val settings: StateFlow<AppSettings?> = g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private fun s(): AppSettings = settings.value ?: AppSettings()

    val dashboard: StateFlow<Dashboard?> = g.settings.settings.map { Triple(it.lateAfterDays, it.currency, it.ratesJson) }.flatMapLatest { (late, cur, rj) ->
        val rates = com.daftari.app.core.Rates.parse(rj)
        repo.dashboard({ LocalDate.now() }, { late }) { p, v ->
            // totals are shown in the default currency: other accounts convert at the table's rate (their own amounts never change)
            if (p.currency.isBlank() || p.currency == cur.code) v else rates.rate(p.currency, cur.code)?.let { com.daftari.app.core.Rates.convert(v, com.daftari.app.core.Rates.plain(it)) } ?: v
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val serverInfo = MutableStateFlow<ServerInfo?>(null)
    val busy = MutableStateFlow<String?>(null)
    val toast = MutableStateFlow<String?>(null)
    val unlocked = MutableStateFlow(false)

    init {
        refreshServerInfo()
        if (g.workspaces.consumeGrant(android.os.SystemClock.elapsedRealtime())) unlocked.value = true
        viewModelScope.launch {
            val st = g.settings.current()
            if (st.onboarded) g.workspaces.initLegacy(st.role)
            if (st.bookId.isBlank()) g.settings.update { it.copy(bookId = java.util.UUID.randomUUID().toString()) }
            if (!st.currencyFilled) { repo.fillLegacyCurrency(st.currency.code); g.settings.update { it.copy(currencyFilled = true) } }
            if (st.driveAccount.isNotBlank()) com.daftari.app.backup.DriveWorker.schedule(getApplication(), st.backupMinuteOfDay, replace = false)
        }
    }

    fun refreshServerInfo() = viewModelScope.launch {
        serverInfo.value = runCatching { g.api.info() }.getOrNull()
    }

    fun say(m: String) { toast.value = m }

    // ---------- auth ----------
    // ---------- 1.1.4 account: Google first, then the app password (+ fingerprint), one book per role ----------
    /** the role he picked on the first-run screen when it differs from the role his existing data belongs to (opened after the password step). */
    private var pendingRole: Role? = null
    /** «نسيت كلمة المرور؟» verified with the owning Google account: the set-password screen is shown again. */
    val resettingPassword = MutableStateFlow(false)

    /** First-run Google sign-in succeeded. Existing data (an upgrade from 1.1.3) stays attached to the role it was set up with. */
    fun googleSignedIn(email: String, name: String, role: Role) = viewModelScope.launch {
        val st = g.settings.current()
        val dataRole = if (st.onboarded) st.role else role
        g.workspaces.initLegacy(dataRole)
        pendingRole = role.takeIf { it != dataRole }
        val sameOwner = AuthGate.sameAccount(st.googleEmail.ifBlank { st.driveAccount }, email)
        g.settings.update {
            it.copy(googleEmail = email, googleName = name, email = email, signedIn = true, authLocal = false, role = dataRole,
                ownerName = it.ownerName.ifBlank { name }, passwordHash = "", lockEnabled = true,
                driveAccount = if (sameOwner) it.driveAccount else "")
        }
    }

    /** Google sign-in is impossible on this phone (shown only after a failed attempt): continue with the app password only. */
    fun continueWithoutGoogle(role: Role) = viewModelScope.launch {
        val st = g.settings.current()
        val dataRole = if (st.onboarded) st.role else role
        g.workspaces.initLegacy(dataRole)
        pendingRole = role.takeIf { it != dataRole }
        g.settings.update { it.copy(authLocal = true, role = dataRole, passwordHash = "", lockEnabled = true) }
    }

    /** The set-password step (first run, or after «نسيت كلمة المرور؟»). */
    fun setAppPassword(pw: String, biometric: Boolean, restart: () -> Unit) = viewModelScope.launch {
        g.settings.update { it.copy(passwordHash = PasswordHasher.hash(pw), lockEnabled = true, biometricOn = biometric) }
        pinGuard.reset()
        resettingPassword.value = false
        unlocked.value = true
        pendingRole?.let { r -> pendingRole = null; switchWorkspace(r, restart) }
    }

    /** «نسيت كلمة المرور؟»: the Google account he just picked must be the one that owns this دفتري. */
    fun forgotVerified(pickedEmail: String): Boolean {
        val ok = AuthGate.sameAccount(s().googleEmail, pickedEmail)
        if (ok) resettingPassword.value = true else say("هذا الحساب ($pickedEmail) ليس الحساب المرتبط بدفتري (${s().googleEmail}).")
        return ok
    }

    /** Returning screen: unlocked with the password or the fingerprint, in the role picked on that screen. */
    fun unlockAs(role: Role, restart: () -> Unit) {
        if (role == s().role) unlocked.value = true else viewModelScope.launch { unlocked.value = true; switchWorkspace(role, restart) }
    }

    fun workspaceExists(role: Role): Boolean = role == s().role || g.workspaces.has(role)

    /**
     * Opens the other role's book under the same account, with no new sign-in: parks this book's profile, (best effort) sends it to
     * Drive, loads the other one (a new, empty book the first time → the setup screen), then restarts so every store reopens on its files.
     */
    fun switchWorkspace(target: Role, restart: () -> Unit) = viewModelScope.launch {
        val st = g.settings.current()
        if (target == st.role) return@launch
        g.workspaces.initLegacy(st.role)
        busy.value = "جارٍ فتح دفتر «${target.labelAr}»…"
        try {
            if (st.driveAccount.isNotBlank()) withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    runCatching {
                        (com.daftari.app.backup.GoogleDrive.authorize(getApplication(), st.driveAccount) as? com.daftari.app.backup.GoogleDrive.Auth.Token)?.let { t ->
                            com.daftari.app.backup.GoogleDrive.upload(t.token, com.daftari.app.core.Workspaces.backupFileName(st.role, com.daftari.app.backup.DaftariFile.fileName()), com.daftari.app.backup.BackupMaker.bytes(g))
                        }
                    }
                }
            }
            g.workspaces.saveProfile(st.role, WorkspaceProfile.of(st))
            val next = g.workspaces.profile(target) ?: WorkspaceProfile(ownerName = st.ownerName, currency = st.currency.code, bookId = java.util.UUID.randomUUID().toString())
            g.settings.update { next.applyTo(it, target) }
            g.workspaces.setActive(target)
            g.workspaces.grantUnlock(android.os.SystemClock.elapsedRealtime() + 30_000)
        } finally { busy.value = null }
        restart()
    }

    /** Settings «تسجيل الخروج من Google»: back to the first-run screen; the data stays on the phone. */
    fun googleSignOut() = viewModelScope.launch {
        g.settings.update { it.copy(googleEmail = "", googleName = "", signedIn = false, authToken = "", authLocal = false) }
        unlocked.value = false
        com.daftari.app.ui.auth.GoogleAuth.signOut(getApplication())
    }

    fun finishSetup(role: Role, business: String, owner: String, shopCount: Int, currency: com.daftari.app.core.Currency, demo: Boolean) = viewModelScope.launch {
        if (demo) {
            val seed = Demo.forRole(role, LocalDate.now())
            val ids = seed.parties.map { repo.saveParty(it) }
            seed.entries(ids).forEach { repo.addEntry(it) }
        }
        val (db, dow) = Demo.businessFor(role)
        g.settings.update {
            it.copy(onboarded = true, role = role, businessName = business.ifBlank { if (demo) db else "" }, ownerName = owner.ifBlank { if (demo) dow else it.ownerName },
                shopCount = if (demo && role == Role.LANDLORD) 27 else shopCount, currency = currency, demoData = demo)
        }
        unlocked.value = true
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) = viewModelScope.launch { g.settings.update(block) }

    fun setPassword(pw: String) = viewModelScope.launch { g.settings.update { it.copy(passwordHash = PasswordHasher.hash(pw)) } }
    /** 1.1.1: one persistent throttle for every PIN prompt (app lock, «السجل», restore). */
    val pinGuard = com.daftari.app.core.PinThrottle(object : com.daftari.app.core.PinThrottle.Store {
        private val p = getApplication<Application>().getSharedPreferences("pin_guard", android.content.Context.MODE_PRIVATE)
        override fun getLong(key: String) = p.getLong(key, 0)
        override fun putLong(key: String, value: Long) { p.edit().putLong(key, value).commit() }
    })
    /** Throttled PIN check: null = correct, otherwise the Arabic error to show. */
    fun tryPassword(pw: String): String? {
        val r = pinGuard.attempt { s().hasPassword && PasswordHasher.verify(pw, s().passwordHash) }
        return pinGuard.message(r)
    }
    fun checkPassword(pw: String): Boolean = tryPassword(pw) == null

    fun signOut() = googleSignOut()

    fun wipeAll(onDone: () -> Unit) = viewModelScope.launch {
        // the account (Google, password, fingerprint, Drive, backup time) survives: only this book is emptied → its setup screen
        repo.wipe(); g.settings.update { AppSettings(theme = it.theme, role = it.role, googleEmail = it.googleEmail, googleName = it.googleName, email = it.email, signedIn = it.signedIn,
            authLocal = it.authLocal, passwordHash = it.passwordHash, lockEnabled = it.lockEnabled, biometricOn = it.biometricOn, driveAccount = it.driveAccount,
            backupMinuteOfDay = it.backupMinuteOfDay, trialStartedAt = it.trialStartedAt, proUntil = it.proUntil, ratesJson = it.ratesJson) }; onDone()
    }

    // ---------- ledger ----------
    fun saveParty(p: Party, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        if (p.name.isBlank()) return@launch say("اكتب الاسم")
        val id = repo.saveParty(if (p.currency.isBlank()) p.copy(currency = s().currency.code) else p); onSaved(if (p.id == 0L) id else p.id)
        say("تم الحفظ ✅")
    }

    fun deleteParty(id: Long, onDone: () -> Unit) = viewModelScope.launch { repo.deleteParty(id); say("تم الحذف"); onDone() }

    /** set by the money sheet right before it saves */
    var pendingFx: com.daftari.app.core.Fx? = null
    var pendingMethod: com.daftari.app.core.PayInfo? = null
    var pendingReceipt: Uri? = null

    fun addPayment(partyId: Long, amount: Long, date: LocalDate, note: String, collector: String = "", receipt: String = "") = viewModelScope.launch {
        if (amount <= 0) return@launch say("اكتب المبلغ")
        val fx = pendingFx; val mt = pendingMethod; val rc = pendingReceipt
        pendingFx = null; pendingMethod = null; pendingReceipt = null
        val entryId = repo.addEntry(Entry(partyId = partyId, type = EntryType.PAYMENT, amount = amount, date = date, note = note, collector = collector, receiptNo = receipt,
            currency = fx?.currency.orEmpty(), origAmount = fx?.origAmount ?: 0, rate = fx?.rate.orEmpty(),
            method = mt?.method.orEmpty(), transferNo = mt?.ref.orEmpty(), transferCo = mt?.company.orEmpty(), senderPhone = mt?.phone.orEmpty()))
        // 1.1.1: the receipt photo belongs to this payment (entry_id), not only to the customer
        if (rc != null) runCatching {
            val label = when (mt?.method) { "wallet" -> "إيصال محفظة ${mt.company} ${mt.ref}"; "transfer" -> "إيصال حوالة ${mt.company} ${mt.ref}"; else -> "إيصال دفعة" }.replace(Regex("\\s+"), " ").trim()
            repo.attach(partyId, rc, label, getApplication<Application>().contentResolver.getType(rc) ?: "image/jpeg", entryId)
        }
        // 1.1.3: the sheet's shrunk copy (camera or gallery) lives in cacheDir/receipts until it is attached
        if (rc != null && rc.scheme == "file") com.daftari.app.core.ReceiptCapture.discard(getApplication<Application>().cacheDir, rc.path)
        say("تم تسجيل الدفعة ✅")
    }

    /** «رقم الحوالة مستخدم» warning text, or null. */
    private val transferSeen = androidx.compose.runtime.mutableStateMapOf<String, String>()
    fun transferWarning(no: String): String? {
        if (no.length < 3) return null
        transferSeen[no]?.let { return it.ifBlank { null } }
        viewModelScope.launch { val e = repo.entryByTransfer(no); transferSeen[no] = e?.let { "هذا الرقم مسجّل من قبل (${Money.number(it.amount)} بتاريخ ${Dates.short(it.date)})" } ?: "" }
        return null
    }

    fun addCharge(partyId: Long, amount: Long, date: LocalDate, note: String, items: String, due: LocalDate?) = viewModelScope.launch {
        if (amount <= 0) return@launch say("اكتب المبلغ")
        val fx = pendingFx; pendingFx = null; pendingMethod = null
        repo.addEntry(Entry(partyId = partyId, type = EntryType.CHARGE, amount = amount, date = date, note = note, items = items, dueDate = due,
            currency = fx?.currency.orEmpty(), origAmount = fx?.origAmount ?: 0, rate = fx?.rate.orEmpty()))
        val p = repo.findParty(partyId)
        if (p != null && p.creditLimit > 0) {
            val bal = Ledger.balance(p, repo.allEntries().filter { it.partyId == partyId }, LocalDate.now())
            if (bal >= p.creditLimit) {
                say("⚠️ وصل حد الدين: ${Money.format(bal, s().currency)} من ${Money.format(p.creditLimit, s().currency)}")
                if (s().alertsEnabled) com.daftari.app.notify.Alerts.limitReached(getApplication(), p.id, p.name, Money.format(bal, s().currency), Money.format(p.creditLimit, s().currency))
            } else if (bal >= p.creditLimit * 8 / 10) say("تم ✅ · اقترب من حد الدين (${Money.format(bal, s().currency)} من ${Money.format(p.creditLimit, s().currency)})")
            else say("تم تسجيل الدين ✅")
        } else say("تم تسجيل الدين ✅")
    }

    fun deleteEntry(id: Long) = viewModelScope.launch { repo.deleteEntry(id); say("تم الحذف") }

    fun attach(partyId: Long, uri: Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "مرفق"
        runCatching { repo.attach(partyId, uri, name, mime) }.onSuccess { say("تم إرفاق الملف") }.onFailure { say("تعذّرت قراءة الملف") }
    }

    fun attachmentFile(name: String) = File(File(getApplication<Application>().filesDir, "attachments"), name)

    // ---------- reports ----------
    suspend fun partyReport(partyId: Long, from: LocalDate, to: LocalDate): Report? {
        val p = repo.findParty(partyId) ?: return null
        val st = Ledger.statement(p, repo.allEntries(), from, to)
        return ReportBuilder.partyStatement(st, s().role, s().businessName, s().ownerName, s().currency)
    }

    suspend fun allReport(from: LocalDate, to: LocalDate): Report {
        val d = dashboard.first { it != null }!!
        return ReportBuilder.allParties(d.summaries, repo.allEntries(), s().role, s().businessName, s().ownerName, s().currency, from, to)
    }

    fun export(report: suspend () -> Report?, format: ExportFormat, then: (File, ExportFormat) -> Unit) = viewModelScope.launch {
        busy.value = "جارٍ تجهيز الملف…"
        try {
            val r = report() ?: return@launch
            val f = g.exporter.export(r, format)
            then(f, format)
        } catch (e: Exception) { say("تعذّر تجهيز الملف") } finally { busy.value = null }
    }

    fun reminderText(p: Party, balance: Long): String {
        val st = s()
        val who = st.businessName.ifBlank { st.ownerName }
        return "السلام عليكم ${p.name}،\nتذكير ودّي من $who: المتبقي عليك ${Money.plain(balance, st.currency)}" +
            (if (p.kind == PartyKind.SHOP && p.shopNumber.isNotBlank()) " (محل ${p.shopNumber})" else "") + ".\nشكراً لتعاونك 🌷"
    }

    // ---------- chat «مدين» ----------
    val chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatBusy = MutableStateFlow(false)

    /** a .daftari opened in the chat: «مدين» answers about it and can import it */
    val chatBackup = MutableStateFlow<Pair<String, com.daftari.app.backup.BackupData>?>(null)

    private suspend fun offlineCtx(): com.daftari.app.chat.OfflineAssistant.Ctx {
        val d = dashboard.first { it != null }!!
        val st = s()
        return com.daftari.app.chat.OfflineAssistant.Ctx(d, repo.allEntries(), st.role, st.currency, st.rates, LocalDate.now(), g.audit.last(), chatBackup.value?.second)
    }

    fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty()) return
        chat.value = chat.value + ChatMessage(true, question)
        viewModelScope.launch {
            chatBusy.value = true
            val st = s()
            val today = LocalDate.now()
            val x = offlineCtx()
            // 1) the on-phone engine first: instant, works with no internet, and never writes without a confirmation card
            val offline = runCatching { com.daftari.app.chat.OfflineAssistant.respond(question, x) }.getOrNull()
            val reply: ChatMessage = offline ?: try {
                // 2) only what the offline engine did not understand goes to the online AI (when signed in and online)
                if (!st.signedIn || !g.api.configured) throw ApiException("not_signed", "")
                val history = chat.value.takeLast(12).map { ChatTurn(if (it.fromUser) "user" else "assistant", it.text) }
                val r = g.api.chat(ChatRequest(history, ChatContext.build(x.d, st.role, st.businessName, st.ownerName, st.currency, today)))
                ChatMessage(false, r.reply, r.table, r.action)
            } catch (e: ApiException) {
                LocalAnswers.answer(question, x.d, st.role, st.currency, today) ?: ChatMessage(false, "لم أفهم الطلب 🤔\n" + com.daftari.app.chat.OfflineAssistant.HELP)
            }
            chat.value = chat.value + reply
            chatBusy.value = false
        }
    }

    fun clearChat() { chat.value = emptyList(); chatBackup.value = null }

    /** «أي محمد تقصد؟» → the picked customer's confirmation card (or answer). */
    fun pickChoice(msgId: Long, a: ChatAction) = viewModelScope.launch {
        chat.value = chat.value.map { if (it.id == msgId) it.copy(choices = emptyList()) else it }
        val x = offlineCtx()
        val s0 = x.d.summaries.firstOrNull { it.party.id == a.partyId }
        val msg = when (a.type) {
            "balance" -> s0?.let { com.daftari.app.chat.OfflineAssistant.balance(it, x) }
            "statement" -> s0?.let { com.daftari.app.chat.OfflineAssistant.statement(it, x) }
            else -> ChatMessage(false, "تأكّد قبل التسجيل 👇", action = a)
        } ?: ChatMessage(false, "لم أجد الحساب")
        chat.value = chat.value + msg
    }

    /** Runs a change the assistant proposed, after the user confirmed its card. */
    fun runAction(msgId: Long, a: ChatAction, confirmed: Boolean) = viewModelScope.launch {
        if (!confirmed) { mark(msgId, false); return@launch }
        val via = com.daftari.app.audit.Via.ASSISTANT
        val today = LocalDate.now()
        val st = s()
        if (a.offline) {
            val day = if (a.day > 0) LocalDate.ofEpochDay(a.day) else today
            val msg: String = when (a.type) {
                "add_debt", "add_payment", "add_party_add_debt", "add_party_add_payment" -> {
                    var pid = a.partyId
                    if (a.type.startsWith("add_party_")) {
                        val landlord = st.role == Role.LANDLORD
                        pid = repo.saveParty(Party(kind = if (landlord) PartyKind.SHOP else PartyKind.CUSTOMER, name = a.name.ifBlank { a.party }, currency = st.currency.code), via)
                    }
                    val pay = a.type.endsWith("payment")
                    if (pay && (a.method == "transfer" || a.method == "wallet") && a.transferNo.isNotBlank()) repo.entryByTransfer(a.transferNo)?.let { dup ->
                        chat.value = chat.value + ChatMessage(false, "⚠️ تنبيه: ${if (a.method == "wallet") "رقم العملية" else "رقم الحوالة"} ${a.transferNo} مسجّل من قبل (${Money.number(dup.amount)} بتاريخ ${Dates.short(dup.date)}). سجّلتُها على أي حال، ويمكنك التراجع بكلمة «تراجع».")
                    }
                    repo.addEntry(Entry(partyId = pid, type = if (pay) EntryType.PAYMENT else EntryType.CHARGE, amount = a.minor, date = day,
                        note = if (pay) (when (a.method) { "transfer" -> "حوالة"; "wallet" -> "محفظة ${a.note}".trim(); else -> "عن طريق مدين" }) else "", items = a.items,
                        currency = a.origCurrency, origAmount = a.origMinor, rate = a.rate, method = a.method, transferNo = a.transferNo, transferCo = a.note, senderPhone = a.senderPhone), via)
                    val p = repo.findParty(pid)
                    val cur = p?.cur(st.currency) ?: st.currency
                    val bal = p?.let { Ledger.balance(it, repo.allEntries().filter { e -> e.partyId == pid }, today) } ?: 0
                    (if (pay) "تم تسجيل سداد ${Money.format(a.minor, cur)} من ${a.party} ✅" else "تم تسجيل دين ${Money.format(a.minor, cur)} على ${a.party} ✅") + "\nرصيده الآن: ${Money.format(bal, cur)}"
                }
                "add_party" -> {
                    val landlord = st.role == Role.LANDLORD
                    repo.saveParty(Party(kind = if (landlord) PartyKind.SHOP else PartyKind.CUSTOMER, name = a.name, phone = Phone.normalize(a.phone) ?: a.phone, currency = st.currency.code), via)
                    "تمت إضافة ${a.name} ✅"
                }
                "reminder" -> {
                    g.reminders.add(a.partyId, a.remindAt, a.note.ifBlank { a.party })
                    "حسناً، سأذكّرك ⏰ ${a.lines.firstOrNull()?.removePrefix("الموعد: ").orEmpty()}"
                }
                "undo" -> when (val r = g.audit.bySeq(a.auditSeq)?.let { repo.undo(it) } ?: com.daftari.app.data.LedgerRepository.RestoreResult.Failed("لم أجد العملية")) {
                    is com.daftari.app.data.LedgerRepository.RestoreResult.Done -> r.message
                    is com.daftari.app.data.LedgerRepository.RestoreResult.Failed -> r.message
                }
                "import_backup" -> {
                    val b = chatBackup.value?.second
                    if (b == null) "افتح ملف النسخة أولاً" else applyBackupNow(b, chatBackup.value!!.first)
                }
                else -> "لم أفهم العملية"
            }
            mark(msgId, true)
            chat.value = chat.value + ChatMessage(false, msg)
            return@launch
        }
        val parties = repo.allParties()
        val p = parties.firstOrNull { it.shopNumber.isNotBlank() && it.shopNumber == a.party.trim() }
            ?: parties.firstOrNull { it.name == a.party.trim() } ?: parties.firstOrNull { a.party.isNotBlank() && it.name.contains(a.party.trim()) }
        val amount = Money.parse(a.amount.toString()) ?: 0
        val msg = when (a.type) {
            "add_payment" -> p?.let { repo.addPayment(it.id, amount, today, a.note.ifBlank { "عن طريق مدين" }, via = via); "تم تسجيل دفعة ${Money.format(amount, it.cur(st.currency))} لـ ${it.name} ✅" }
            "add_debt", "add_charge" -> p?.let { repo.addCharge(it.id, amount, today, a.note.ifBlank { "عن طريق مدين" }, via = via); "تم تسجيل دين ${Money.format(amount, it.cur(st.currency))} على ${it.name} ✅" }
            "add_party" -> {
                val landlord = st.role == Role.LANDLORD
                repo.saveParty(Party(kind = if (landlord) PartyKind.SHOP else PartyKind.CUSTOMER, name = a.name.ifBlank { a.party }, phone = a.phone,
                    activity = a.activity, shopNumber = a.shopNumber, periodicAmount = Money.parse(a.periodicAmount.toString()) ?: 0,
                    period = if (landlord && a.periodicAmount > 0) Period.DAILY else Period.NONE, currency = st.currency.code), via)
                "تمت الإضافة ✅"
            }
            "edit_party" -> p?.let {
                repo.saveParty(it.copy(name = a.name.ifBlank { it.name }, phone = a.phone.ifBlank { it.phone }, activity = a.activity.ifBlank { it.activity },
                    periodicAmount = if (a.periodicAmount > 0) Money.parse(a.periodicAmount.toString()) ?: it.periodicAmount else it.periodicAmount), via)
                "تم التعديل ✅"
            }
            "delete_party" -> p?.let { repo.deleteParty(it.id, via); "تم حذف ${it.name} (يمكن استعادته من «السجل»)" }
            "delete_entry" -> { repo.deleteEntry(a.entryId, via); "تم حذف العملية (يمكن استعادتها من «السجل»)" }
            else -> "لم أفهم العملية"
        } ?: "لم أجد «${a.party}» في دفترك"
        mark(msgId, true)
        chat.value = chat.value + ChatMessage(false, msg)
    }

    private fun mark(id: Long, done: Boolean) { chat.value = chat.value.map { if (it.id == id) it.copy(actionDone = done) else it } }

    // ---------- import ----------
    val importResult = MutableStateFlow<ImportResult?>(null)

    fun importFile(uri: Uri, smart: Boolean) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
        val mime = ctx.contentResolver.getType(uri) ?: ""
        busy.value = "جارٍ قراءة الملف…"
        try {
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            val local = SheetImporter.parse(name, bytes)
            val isImage = mime.startsWith("image/")
            val isPdf = mime.contains("pdf") || name.lowercase().endsWith(".pdf")
            if (!smart && local.rows.isNotEmpty()) { importResult.value = local; return@launch }
            busy.value = "«مدين» يرتّب البيانات…"
            val images = when {
                isImage -> listOf(ImageUtil.jpegBase64(bytes))
                isPdf -> ImageUtil.pdfPagesBase64(ctx, bytes, 3)
                else -> emptyList()
            }
            try {
                val r = g.api.smartImport(ImportRequest(name, mime, local.rawText.take(60_000), images, s().role.name))
                importResult.value = ImportResult(
                    r.rows.filter { it.name.isNotBlank() }.map { ImportRow(it.name, it.shopNumber, it.phone, it.activity, Money.parse(it.amount.toString()) ?: 0, Money.parse(it.arrears.toString()) ?: 0, it.note, it.doubtful, it.reason) },
                    r.businessName.ifBlank { local.businessName }, r.ownerName.ifBlank { local.ownerName }, r.warnings,
                )
            } catch (e: ApiException) {
                if (local.rows.isNotEmpty()) { importResult.value = local.copy(warnings = local.warnings + "الاستيراد الذكي: ${if (e.code == "ai_not_configured") "الخدمة قيد التجهيز" else e.message} — هذه قراءة بدون ذكاء") }
                else say(if (e.code == "ai_not_configured" || e.code == "not_signed" || e.code == "no_server") "الاستيراد الذكي للصور وPDF: الخدمة قيد التجهيز" else e.message)
            }
        } catch (e: Exception) { say("تعذّرت قراءة الملف") } finally { busy.value = null }
    }

    fun confirmImport(rows: List<ImportRow>, business: String, owner: String, onDone: () -> Unit) = viewModelScope.launch {
        val landlord = s().role == Role.LANDLORD
        val today = LocalDate.now()
        var n = 0
        rows.filter { it.include && it.name.isNotBlank() }.forEach { r ->
            repo.saveParty(Party(
                kind = if (landlord) PartyKind.SHOP else PartyKind.CUSTOMER, shopNumber = r.shopNumber, name = r.name.trim(),
                phone = Phone.normalize(r.phone) ?: r.phone, activity = r.activity, periodicAmount = if (landlord) r.amount else 0,
                period = if (landlord && r.amount > 0) Period.DAILY else Period.NONE, startDate = today, notes = r.note,
                openingBalance = r.arrears + (if (!landlord) r.amount else 0),
            ))
            n++
        }
        g.settings.update { it.copy(businessName = it.businessName.ifBlank { business }, ownerName = it.ownerName.ifBlank { owner }, shopCount = if (landlord && it.shopCount == 0) n else it.shopCount) }
        importResult.value = null
        say("تم استيراد $n ✅"); onDone()
    }

    // ---------- backup ----------
    fun backupNow() = viewModelScope.launch {
        busy.value = "جارٍ النسخ الاحتياطي…"
        try {
            val parties = repo.allParties(); val entries = repo.allEntries()
            val snap = buildJsonObject {
                put("version", 1); put("at", System.currentTimeMillis())
                put("parties", buildJsonArray { parties.forEach { p -> add(buildJsonObject {
                    put("id", p.id); put("kind", p.kind.name); put("shop", p.shopNumber); put("name", p.name); put("phone", p.phone); put("activity", p.activity)
                    put("periodic", p.periodicAmount); put("period", p.period.name); put("start", p.startDate.toString()); put("notes", p.notes)
                    put("opening", p.openingBalance); put("units", p.unitsCount); put("limit", p.creditLimit)
                }) } })
                put("entries", buildJsonArray { entries.forEach { e -> add(buildJsonObject {
                    put("id", e.id); put("party", e.partyId); put("type", e.type.name); put("amount", e.amount); put("date", e.date.toString()); put("note", e.note)
                    put("collector", e.collector); put("receipt", e.receiptNo); put("items", e.items); put("due", e.dueDate?.toString() ?: "")
                }) } })
            }
            g.api.backup(snap)
            g.settings.update { it.copy(lastBackupAt = System.currentTimeMillis()) }
            say("تم النسخ الاحتياطي ✅")
        } catch (e: ApiException) { say(if (e.code == "no_server") "النسخ الاحتياطي يحتاج الخادم — بياناتك محفوظة على جوالك" else e.message) } finally { busy.value = null }
    }

    @Suppress("unused") private fun unusedKeep(e: Entry, t: EntryType, d: Dates) = Unit

    // ---- Free / Pro ----
    fun isPro(): Boolean = com.daftari.app.core.Plan.isPro(s())
    fun startTrialIfNeeded() = viewModelScope.launch { if (s().trialStartedAt == 0L) g.settings.update { it.copy(trialStartedAt = System.currentTimeMillis()) } }
    /** Returns true when allowed; otherwise opens the paywall via [onPaywall]. */
    fun gate(f: com.daftari.app.core.Plan.Feature, partyCount: Int = 0, onPaywall: () -> Unit): Boolean {
        val ok = com.daftari.app.core.Plan.allows(s(), f, partyCount)
        if (!ok) onPaywall()
        return ok
    }
    fun activatePro(code: String, onDone: () -> Unit) = viewModelScope.launch {
        val c = code.trim().uppercase()
        if (c.length < 6) return@launch say("اكتب كود التفعيل كاملاً")
        busy.value = "جارٍ التحقق من الكود…"
        try {
            val r = g.api.activate(c)
            g.settings.update { it.copy(proUntil = r.proUntil) }
            say("تم تفعيل برو ✅"); onDone()
        } catch (e: ApiException) { say(if (e.code == "no_server") "التفعيل يحتاج الخادم — قيد التجهيز" else e.message) } finally { busy.value = null }
    }

    // ---- extra reports ----
    suspend fun entriesReport(from: LocalDate, to: LocalDate): com.daftari.app.export.Report =
        com.daftari.app.export.ReportBuilder.allEntries(repo.allParties(), repo.allEntries(), s().role, s().businessName, s().ownerName, s().currency, from, to)
    suspend fun monthlyReport(from: LocalDate, to: LocalDate): com.daftari.app.export.Report =
        com.daftari.app.export.ReportBuilder.monthly(repo.allEntries(), s().businessName, s().ownerName, s().currency, from, to)
    suspend fun categoryReport(): com.daftari.app.export.Report {
        val d = dashboard.first { it != null }!!
        return com.daftari.app.export.ReportBuilder.byCategory(d.summaries, s().businessName, s().ownerName, s().currency, LocalDate.now())
    }

    // ---- database file: export / restore / daily local copy ----
    fun exportDatabase(then: (java.io.File) -> Unit) = viewModelScope.launch {
        val bytes = com.daftari.app.backup.BackupMaker.bytes(g)
        val f = withContext(Dispatchers.IO) {
            val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            java.io.File(dir, backupFileName()).apply { writeBytes(bytes) }
        }
        withContext(Dispatchers.IO) { com.daftari.app.notify.DailyWorker.writeLocalBackup(getApplication(), bytes) }
        then(f)
    }

    /** older entry point: any backup file goes through the verified .daftari reader (legacy 1.0 JSON is migrated). */
    fun restoreFrom(uri: Uri, onDone: () -> Unit) { openBackup(uri); onDone() }


    // =================================================================== «السجل» (permanent log)
    val logRecords = g.audit.records.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val logVerify = MutableStateFlow<com.daftari.app.audit.Verify?>(null)
    /** both fingerprint and PIN passed for this visit to the log */
    val logUnlocked = MutableStateFlow(false)

    fun verifyLog() = viewModelScope.launch { logVerify.value = withContext(Dispatchers.IO) { g.audit.verify() } }

    fun lockLog() { logUnlocked.value = false }

    fun restoreFromLog(seq: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        val r = g.audit.bySeq(seq) ?: return@launch say("لم أجد العملية")
        when (val res = repo.restoreFromLog(r)) {
            is com.daftari.app.data.LedgerRepository.RestoreResult.Done -> { say(res.message); onDone() }
            is com.daftari.app.data.LedgerRepository.RestoreResult.Failed -> say(res.message)
        }
        verifyLog()
    }

    data class LogFilter(val from: LocalDate? = null, val to: LocalDate? = null, val party: String = "", val actions: Set<String> = emptySet(), val query: String = "")

    fun filterLog(all: List<com.daftari.app.audit.AuditRecord>, f: LogFilter): List<com.daftari.app.audit.AuditRecord> {
        val zone = java.time.ZoneId.systemDefault()
        val q = com.daftari.app.chat.ArabicText.normalize(f.query)
        val pn = com.daftari.app.chat.ArabicText.normalize(f.party)
        return all.filter { r ->
            val d = java.time.Instant.ofEpochMilli(r.at).atZone(zone).toLocalDate()
            (f.from == null || !d.isBefore(f.from)) && (f.to == null || !d.isAfter(f.to)) &&
                (pn.isBlank() || com.daftari.app.chat.ArabicText.normalize(r.partyName).contains(pn)) &&
                (f.actions.isEmpty() || r.action in f.actions || (r.action in setOf("WIPE", "BACKUP_RESTORE") && "DELETE" in f.actions)) &&
                (q.isBlank() || com.daftari.app.chat.ArabicText.normalize(r.title + " " + r.partyName + " " + r.before + " " + r.after).contains(q))
        }
    }

    /** «كشف السجل» as PDF / Excel / Word / image, with the filter applied. */
    fun logReport(rows: List<com.daftari.app.audit.AuditRecord>, f: LogFilter, verify: com.daftari.app.audit.Verify?): com.daftari.app.export.Report =
        com.daftari.app.export.LogReport.build(rows, f.from, f.to, f.party, f.actions, verify, s().businessName, s().ownerName, LocalDate.now())

    // =================================================================== backups (.daftari)
    data class BackupPreview(val data: com.daftari.app.backup.BackupData, val manifest: com.daftari.app.backup.Manifest?, val warnings: List<String>, val fileName: String)
    val backupPreview = MutableStateFlow<BackupPreview?>(null)
    /** a file waiting for its «كلمة سر النسخ»: (name, bytes) */
    val backupNeedsPassword = MutableStateFlow<Pair<String, ByteArray>?>(null)
    val driveFiles = MutableStateFlow<List<com.daftari.app.backup.DriveFile>>(emptyList())
    val driveError = MutableStateFlow<String?>(null)
    val driveSetupMissing = MutableStateFlow(false)
    val backupResult = MutableStateFlow<String?>(null)

    private fun nameOf(uri: Uri): String = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"

    private suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() } }

    fun setBackupPassword(pw: String?) = viewModelScope.launch {
        if (pw.isNullOrBlank()) { g.backupKeys.clear(); g.settings.update { it.copy(backupEncrypted = false) }; say("أصبحت النسخ بلا تشفير"); return@launch }
        withContext(Dispatchers.Default) { g.backupKeys.set(pw) }
        g.settings.update { it.copy(backupEncrypted = true) }
        say("تم ضبط كلمة سر النسخ 🔒 احفظها، فستحتاجها إذا غيّرت جوالك")
    }

    /** «حفظ نسخة في Drive» / «حفظ نسخة» through the system picker (works today with no setup). */
    fun saveBackupTo(uri: Uri) = viewModelScope.launch {
        busy.value = "جارٍ تجهيز النسخة…"
        try {
            val bytes = com.daftari.app.backup.BackupMaker.bytes(g)
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) } }
            g.settings.update { it.copy(lastBackupAt = System.currentTimeMillis()) }
            say("تم حفظ النسخة ✅")
        } catch (e: Exception) { say("تعذّر حفظ النسخة") } finally { busy.value = null }
    }

    /** «Daftari-shop-…» / «Daftari-market-…»: each role's copies stay apart. */
    fun backupFileName() = com.daftari.app.core.Workspaces.backupFileName(s().role, com.daftari.app.backup.DaftariFile.fileName())

    /** «استيراد نسخة» / a .daftari from Drive / the chat: verify, then show the counts preview. */
    fun openBackupBytes(name: String, bytes: ByteArray, password: String? = null, toChat: Boolean = false): Boolean {
        return when (val r = com.daftari.app.backup.DaftariFile.read(bytes, password, g.backupKeys.get())) {
            is com.daftari.app.backup.ReadResult.Ok -> {
                backupNeedsPassword.value = null
                if (toChat) {
                    chatBackup.value = name to r.data
                    chat.value = chat.value + ChatMessage(false, com.daftari.app.chat.OfflineAssistant.backupSummary(r.data, name, r.warnings) + "\n\nهل تريد استرجاعها؟",
                        action = ChatAction(type = "import_backup", lines = listOf("${r.data.parties.size} زبون · ${r.data.entries.size} عملية · السجل ${r.data.log.size}", "دمج دون تكرار"), offline = true))
                } else backupPreview.value = BackupPreview(r.data, r.manifest, r.warnings, name)
                true
            }
            is com.daftari.app.backup.ReadResult.NeedsPassword -> { backupNeedsPassword.value = name to bytes; pendingBackupToChat = toChat; false }
            is com.daftari.app.backup.ReadResult.Bad -> {
                if (toChat) chat.value = chat.value + ChatMessage(false, "⚠️ ${r.messageAr}") else say(r.messageAr)
                false
            }
        }
    }
    private var pendingBackupToChat = false

    fun openBackupWithPassword(pw: String): Boolean {
        val (name, bytes) = backupNeedsPassword.value ?: return false
        val r = com.daftari.app.backup.DaftariFile.read(bytes, pw, null)
        if (r is com.daftari.app.backup.ReadResult.Bad) { say(r.messageAr); return false }
        return openBackupBytes(name, bytes, pw, pendingBackupToChat)
    }

    fun openBackup(uri: Uri, toChat: Boolean = false) = viewModelScope.launch {
        try { openBackupBytes(nameOf(uri), readBytes(uri), toChat = toChat) } catch (e: Exception) { say("تعذّرت قراءة الملف") }
    }

    private suspend fun applyBackupNow(b: com.daftari.app.backup.BackupData, fileName: String): String {
        val st = s()
        val same = b.bookId.isNotBlank() && b.bookId == st.bookId
        val stats = repo.mergeBackup(b, same, "استعادة نسخة «$fileName» (${b.parties.size} زبون، ${b.entries.size} عملية)")
        g.reminders.merge(b.reminders)
        // the rate table travels with the backup (2.2); a phone that already has its own table keeps it
        if (b.ratesJson.isNotBlank() && st.ratesJson.isBlank()) g.settings.update { it.copy(ratesJson = com.daftari.app.core.Rates.parse(b.ratesJson).toJson()) }
        if (!st.onboarded || st.businessName.isBlank()) g.settings.update {
            it.copy(onboarded = true, businessName = it.businessName.ifBlank { b.business }, ownerName = it.ownerName.ifBlank { b.owner },
                role = runCatching { Role.valueOf(b.role) }.getOrDefault(it.role), currency = com.daftari.app.core.Currency.of(b.currency),
                bookId = b.bookId.ifBlank { it.bookId }, currencyFilled = true)
        }
        unlocked.value = true
        // reconciliation: what the phone holds now vs the file
        val ps = repo.allParties(); val es = repo.allEntries()
        val fileBal = b.parties.sumOf { p -> Ledger.balance(p, b.entries.filter { it.partyId == p.id }, LocalDate.now()) }
        val ids = b.parties.map { it.id }.toSet()
        val nowBal = if (same || stats.partiesUpdated + stats.partiesAdded == ps.size) ps.filter { it.id in ids }.sumOf { p -> Ledger.balance(p, es.filter { it.partyId == p.id }, LocalDate.now()) } else null
        return "تمت الاستعادة ✅\n• زبائن جدد: ${stats.partiesAdded}" + (if (stats.partiesUpdated > 0) "، محدَّثون: ${stats.partiesUpdated}" else "") +
            "\n• عمليات مضافة: ${stats.entriesAdded}" + (if (stats.entriesSkipped > 0) "، موجودة مسبقاً (لم تتكرر): ${stats.entriesSkipped}" else "") +
            (if (stats.logAdopted) "\n• استُعيد السجل كاملاً بختمه (${b.log.size} عملية)" else "") +
            (nowBal?.let { "\n• إجمالي النسخة ${Money.number(fileBal)} = الموجود الآن ${Money.number(it)} " + if (it == fileBal) "✓" else "⚠️ يختلف" } ?: "")
    }

    fun applyBackup(onDone: () -> Unit = {}) = viewModelScope.launch {
        val p = backupPreview.value ?: return@launch
        busy.value = "جارٍ استرجاع النسخة…"
        try { backupResult.value = applyBackupNow(p.data, p.fileName); backupPreview.value = null; onDone() }
        catch (e: Exception) { say("لم تكتمل الاستعادة: ${e.message}") } finally { busy.value = null }
    }

    // ---- Google Drive (hidden app folder)
    /** [account] = the email he just picked with Google sign-in (null when sign-in was skipped: Google then shows its own chooser). */
    fun driveConnect(account: String?, launch: (android.app.PendingIntent) -> Unit) = viewModelScope.launch {
        driveError.value = null; driveSetupMissing.value = false
        driveHint = account
        handleDriveAuth(com.daftari.app.backup.GoogleDrive.authorize(getApplication(), account), launch)
    }
    private var driveHint: String? = null

    /** Google sign-in on the backup screen failed for a reason that also blocks Drive (shown in the same red box). */
    fun driveFailed(message: String, setupMissing: Boolean) { driveError.value = message; driveSetupMissing.value = setupMissing }

    fun driveAuthResult(data: android.content.Intent?) = viewModelScope.launch {
        handleDriveAuth(com.daftari.app.backup.GoogleDrive.fromIntent(getApplication(), data)) {}
    }

    private suspend fun handleDriveAuth(a: com.daftari.app.backup.GoogleDrive.Auth, launch: (android.app.PendingIntent) -> Unit) {
        when (a) {
            is com.daftari.app.backup.GoogleDrive.Auth.NeedsUi -> launch(a.pending)
            com.daftari.app.backup.GoogleDrive.Auth.Cancelled -> Unit
            is com.daftari.app.backup.GoogleDrive.Auth.Failed -> { driveError.value = a.message; driveSetupMissing.value = a.setupMissing }
            is com.daftari.app.backup.GoogleDrive.Auth.Token -> {
                driveError.value = null
                val email = com.daftari.app.backup.GoogleDrive.email(a.token).ifBlank { driveHint.orEmpty() }.ifBlank { "حساب Google" }
                g.settings.update { it.copy(driveAccount = email) }
                com.daftari.app.backup.DriveWorker.schedule(getApplication(), s().backupMinuteOfDay, replace = true)
                driveSetupMissing.value = false
                runCatching { driveFiles.value = mine(com.daftari.app.backup.GoogleDrive.list(a.token)) }
            }
        }
    }

    private fun mine(l: List<com.daftari.app.backup.DriveFile>) = l.filter { com.daftari.app.core.Workspaces.belongs(it.name, s().role, g.workspaces.legacy()) }

    private suspend fun driveToken(): String? = when (val a = com.daftari.app.backup.GoogleDrive.authorize(getApplication(), s().driveAccount)) {
        is com.daftari.app.backup.GoogleDrive.Auth.Token -> a.token
        is com.daftari.app.backup.GoogleDrive.Auth.Failed -> { driveError.value = a.message; driveSetupMissing.value = a.setupMissing; null }
        else -> { driveError.value = "اضغط «ربط حساب Google» من جديد"; null }
    }

    fun driveBackupNow() = viewModelScope.launch {
        busy.value = "جارٍ رفع النسخة إلى Google Drive…"
        try {
            val t = driveToken() ?: return@launch
            com.daftari.app.backup.GoogleDrive.upload(t, backupFileName(), com.daftari.app.backup.BackupMaker.bytes(g))
            com.daftari.app.backup.GoogleDrive.prune(t, s().role, g.workspaces.legacy())
            g.settings.update { it.copy(lastDriveBackupAt = System.currentTimeMillis()) }
            driveFiles.value = mine(com.daftari.app.backup.GoogleDrive.list(t))
            say("تم الرفع إلى Google Drive ✅")
        } catch (e: Exception) { driveError.value = e.message ?: "تعذّر الرفع" } finally { busy.value = null }
    }

    fun driveList() = viewModelScope.launch { runCatching { driveToken()?.let { driveFiles.value = mine(com.daftari.app.backup.GoogleDrive.list(it)) } }.onFailure { driveError.value = it.message } }

    fun driveRestore(f: com.daftari.app.backup.DriveFile) = viewModelScope.launch {
        busy.value = "جارٍ تنزيل النسخة…"
        try { val t = driveToken() ?: return@launch; openBackupBytes(f.name, com.daftari.app.backup.GoogleDrive.download(t, f.id)) }
        catch (e: Exception) { driveError.value = e.message } finally { busy.value = null }
    }

    /** The daily Drive copy cannot be switched off (1.1.4) — only its time moves; the schedule realigns at once. */
    fun setBackupTime(minuteOfDay: Int) = viewModelScope.launch {
        g.settings.update { it.copy(backupMinuteOfDay = minuteOfDay.coerceIn(0, 24 * 60 - 1)) }
        if (s().driveAccount.isNotBlank()) com.daftari.app.backup.DriveWorker.schedule(getApplication(), minuteOfDay, replace = true)
        say("النسخ التلقائي يومياً الساعة ${com.daftari.app.core.BackupClock.label(minuteOfDay)}")
    }

    /** Asked once per launch: right after he signs in with Google, Drive is requested for the same account (the daily copy is mandatory). */
    private var driveAsked = false
    fun driveAutoConnect(launch: (android.app.PendingIntent) -> Unit) {
        val st = s()
        if (driveAsked || st.googleEmail.isBlank() || st.driveAccount.isNotBlank()) return
        driveAsked = true; driveConnect(st.googleEmail, launch)
    }

    // =================================================================== «انتقل من تطبيق آخر» (statement / database import)
    data class ImportState(
        val tables: List<com.daftari.app.importer.SourceTable>, val index: Int, val plan: com.daftari.app.importer.ImportPlan,
        val remembered: Boolean = false, val done: String? = null,
    )
    val importState = MutableStateFlow<ImportState?>(null)
    private val mappingPrefs by lazy { getApplication<Application>().getSharedPreferences("daftari_import_mappings", android.content.Context.MODE_PRIVATE) }

    private suspend fun plan(t: com.daftari.app.importer.SourceTable, m: com.daftari.app.importer.Mapping) =
        withContext(Dispatchers.Default) { com.daftari.app.importer.StatementImporter.build(t, m, repo.allParties(), repo.allEntries(), LocalDate.now()) }

    private fun remembered(t: com.daftari.app.importer.SourceTable): com.daftari.app.importer.Mapping? {
        val raw = mappingPrefs.getString(t.signature, null) ?: return null
        val parts = raw.split(";"); if (parts.size < 2) return null
        val fields = parts[0].split(",").mapNotNull { runCatching { com.daftari.app.importer.Field.valueOf(it) }.getOrNull() }
        if (fields.size != t.header.size) return null
        val g0 = com.daftari.app.importer.StatementImporter.guess(t)
        return g0.copy(fields = fields, layout = runCatching { com.daftari.app.importer.Layout.valueOf(parts[1]) }.getOrDefault(g0.layout))
    }

    /** Any file: .daftari → backup preview; CSV / Excel / JSON / SQLite → the import preview; Word / PDF / photo → the older reader. */
    fun openImport(uri: Uri, toChat: Boolean = false) = viewModelScope.launch {
        val name = nameOf(uri)
        busy.value = "جارٍ قراءة الملف…"
        try {
            val bytes = readBytes(uri)
            val lower = name.lowercase()
            if (com.daftari.app.backup.DaftariFile.isZip(bytes) && lower.endsWith(".daftari") || lower.endsWith(".daftari") ||
                (lower.endsWith(".json") && String(bytes.copyOf(minOf(bytes.size, 4000)), Charsets.UTF_8).let { it.contains("\"daftari-backup\"") || it.contains("\"app\":\"daftari\"") })) {
                openBackupBytes(name, bytes, toChat = toChat); return@launch
            }
            if (lower.endsWith(".docx") || lower.endsWith(".pdf") || (getApplication<Application>().contentResolver.getType(uri) ?: "").startsWith("image/")) {
                busy.value = null; importFile(uri, smart = false); navImport.value = true; return@launch
            }
            val tables = withContext(Dispatchers.IO) {
                if (com.daftari.app.importer.SqliteSource.isSqlite(bytes)) com.daftari.app.importer.SqliteSource.read(name, bytes, getApplication<Application>().cacheDir)
                else com.daftari.app.importer.StatementImporter.read(name, bytes)
            }
            if (tables.isEmpty()) { say("لم أجد جدول بيانات في الملف"); return@launch }
            val t = tables.first()
            val saved = remembered(t)
            val p = plan(t, saved ?: com.daftari.app.importer.StatementImporter.guess(t))
            importState.value = ImportState(tables, 0, p, remembered = saved != null)
            navImport.value = true
            if (toChat) chat.value = chat.value + ChatMessage(false, "فتحت «$name»: ${p.customers.size} زبون، ${p.toImport.size} عملية، المجموع ${Money.number(p.fileNet)}" +
                (p.fileTotal?.let { "\nإجمالي الملف ${Money.number(it)} " + if (p.totalMatches == true) "✓ مطابق" else "⚠️ غير مطابق" } ?: "") + "\nفتحتُ لك المعاينة لمراجعتها والتأكد منها 👇")
        } catch (e: Exception) { say("تعذّرت قراءة الملف") } finally { busy.value = null }
    }
    val navImport = MutableStateFlow(false)

    fun importSelectTable(i: Int) = viewModelScope.launch {
        val st = importState.value ?: return@launch
        val t = st.tables.getOrNull(i) ?: return@launch
        importState.value = st.copy(index = i, plan = plan(t, remembered(t) ?: com.daftari.app.importer.StatementImporter.guess(t)))
    }

    fun importSetField(col: Int, f: com.daftari.app.importer.Field) = viewModelScope.launch {
        val st = importState.value ?: return@launch
        val m = st.plan.mapping
        val fields = m.fields.toMutableList().also { it[col] = f }
        val months = if (f == com.daftari.app.importer.Field.MONTH) m.months + (col to (com.daftari.app.importer.StatementImporter.monthOf(st.plan.table.header[col]) ?: 1)) else m.months - col
        importState.value = st.copy(plan = plan(st.plan.table, m.copy(fields = fields, months = months)))
    }

    fun importSetLayout(l: com.daftari.app.importer.Layout) = viewModelScope.launch {
        val st = importState.value ?: return@launch
        importState.value = st.copy(plan = plan(st.plan.table, st.plan.mapping.copy(layout = l)))
    }

    fun importConfirm() = viewModelScope.launch {
        val st = importState.value ?: return@launch
        val p = st.plan
        busy.value = "جارٍ الاستيراد…"
        try {
            mappingPrefs.edit().putString(p.table.signature, p.mapping.fields.joinToString(",") { it.name } + ";" + p.mapping.layout.name).apply()
            val via = com.daftari.app.audit.Via.IMPORT
            val landlord = s().role == Role.LANDLORD
            val ids = HashMap<String, Long>()
            p.customers.forEach { c ->
                ids[com.daftari.app.importer.StatementImporter.key(c.name)] = c.existingId
                    ?: repo.saveParty(Party(kind = if (landlord) PartyKind.SHOP else PartyKind.CUSTOMER, name = c.name.trim(), phone = Phone.normalize(c.phone) ?: c.phone, currency = s().currency.code), via)
            }
            var n = 0
            p.toImport.forEach { t ->
                val pid = ids[com.daftari.app.importer.StatementImporter.key(t.name)] ?: return@forEach
                repo.addEntry(com.daftari.app.core.PayMethods.fromNote(Entry(partyId = pid, type = t.type, amount = t.amount, date = t.date, note = t.note, items = t.items)), via); n++
            }
            // reconciliation
            val ps = repo.allParties(); val es = repo.allEntries()
            val imported = es.filter { e -> e.partyId in ids.values }.let { _ -> p.toImport.sumOf { if (it.type == EntryType.PAYMENT) -it.amount else it.amount } }
            val lines = buildList {
                add("تم الاستيراد ✅")
                add("• زبائن: ${p.customers.size} (جدد ${p.newCustomers})")
                add("• عمليات مضافة: $n" + (p.txns.size - p.toImport.size).let { if (it > 0) "، متخطاة (مكررة/موجودة): $it" else "" })
                add("• مجموع المستورد: ${Money.number(imported)}")
                p.fileTotal?.let { add("• إجمالي الملف: ${Money.number(it)} " + if (it == p.fileNet) "✓ مطابق" else "⚠️ غير مطابق (الفرق ${Money.number(it - p.fileNet)})") }
                if (p.skippedRows > 0) add("• أسطر لم تُقرأ: ${p.skippedRows}")
            }
            importState.value = st.copy(done = lines.joinToString("\n"))
            @Suppress("UNUSED_VARIABLE") val unused = ps
        } catch (e: Exception) { say("لم يكتمل الاستيراد") } finally { busy.value = null }
    }

    // =================================================================== exchange rates
    val ratesStatus = MutableStateFlow<String?>(null)
    fun saveRates(r: com.daftari.app.core.Rates) = viewModelScope.launch { g.settings.update { it.copy(ratesJson = r.toJson()) }; ratesStatus.value = "تم الحفظ ✓"; say("تم حفظ الأسعار ✅") }

    /** «تحديث من النت»: ye-rial.com tables; on failure the saved rates stay and we say so. */
    fun fetchRates() = viewModelScope.launch {
        ratesStatus.value = "جارٍ جلب الأسعار…"
        val got = withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url("https://ye-rial.com/").header("User-Agent", "Mozilla/5.0 Daftari").build()
                okhttp3.OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { it.body?.string().orEmpty() }
            }.getOrNull()?.let { com.daftari.app.core.Rates.parseYeRial(it, LocalDate.now().toString()) }
        }
        if (got == null) { ratesStatus.value = "تعذّر جلب الأسعار (الإنترنت أو الموقع) — بقيت آخر أسعار محفوظة"; return@launch }
        var r = s().rates; got.forEach { r = r.with(it) }
        g.settings.update { it.copy(ratesJson = r.toJson()) }
        ratesStatus.value = "تم التحديث من الإنترنت ✓ (${LocalDate.now()})"
    }
}
