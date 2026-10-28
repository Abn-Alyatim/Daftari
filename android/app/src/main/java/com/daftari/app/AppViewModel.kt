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
import com.daftari.app.data.ApiException
import com.daftari.app.data.AppSettings
import com.daftari.app.data.ChatAction
import com.daftari.app.data.ChatRequest
import com.daftari.app.data.ChatTurn
import com.daftari.app.data.Dashboard
import com.daftari.app.data.Demo
import com.daftari.app.data.GoogleLogin
import com.daftari.app.data.ImportRequest
import com.daftari.app.data.OtpVerify
import com.daftari.app.data.PasswordHasher
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

    val dashboard: StateFlow<Dashboard?> = g.settings.settings.map { it.lateAfterDays }.flatMapLatest { late ->
        repo.dashboard({ LocalDate.now() }, { late })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val serverInfo = MutableStateFlow<ServerInfo?>(null)
    val busy = MutableStateFlow<String?>(null)
    val toast = MutableStateFlow<String?>(null)
    val unlocked = MutableStateFlow(false)

    init { refreshServerInfo() }

    fun refreshServerInfo() = viewModelScope.launch {
        serverInfo.value = runCatching { g.api.info() }.getOrNull()
    }

    fun say(m: String) { toast.value = m }

    // ---------- auth ----------
    val otpPhone = MutableStateFlow("")
    val otpChannel = MutableStateFlow("whatsapp")
    val otpDevCode = MutableStateFlow<String?>(null)

    fun requestOtp(rawPhone: String, channel: String, onSent: () -> Unit) {
        val phone = Phone.normalize(rawPhone) ?: return say("اكتب رقم جوال صحيح")
        viewModelScope.launch {
            busy.value = if (channel == "sms") "نرسل الرمز برسالة…" else "نرسل الرمز للواتساب…"
            try {
                val r = g.api.requestOtp(phone, channel)
                otpPhone.value = phone; otpChannel.value = r.channel.ifBlank { channel }; otpDevCode.value = r.devCode
                onSent()
            } catch (e: ApiException) { say(e.message) } finally { busy.value = null }
        }
    }

    fun verifyOtp(code: String, role: Role, email: String, onDone: () -> Unit) = viewModelScope.launch {
        busy.value = "نتحقق من الرمز…"
        try {
            val r = g.api.verifyOtp(OtpVerify(otpPhone.value, code.trim(), role.name, email.trim()))
            g.settings.update { it.copy(signedIn = true, authToken = r.token, phone = r.user.phone.ifBlank { otpPhone.value }, email = r.user.email.ifBlank { email }, role = role, ownerName = it.ownerName.ifBlank { r.user.name }) }
            onDone()
        } catch (e: ApiException) { say(e.message) } finally { busy.value = null }
    }

    fun googleSignedIn(idToken: String, email: String, name: String, role: Role, onDone: () -> Unit) = viewModelScope.launch {
        busy.value = "ندخلك بحساب Google…"
        try {
            val r = g.api.google(GoogleLogin(idToken, role.name))
            g.settings.update { it.copy(signedIn = true, authToken = r.token, email = r.user.email.ifBlank { email }, phone = r.user.phone, role = role, ownerName = it.ownerName.ifBlank { r.user.name.ifBlank { name } }) }
            onDone()
        } catch (e: ApiException) { say(e.message) } finally { busy.value = null }
    }

    /** Works without any server: everything stays on the phone. */
    fun continueOffline(role: Role, onDone: () -> Unit) = viewModelScope.launch {
        g.settings.update { it.copy(role = role) }; onDone()
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
    fun checkPassword(pw: String): Boolean = s().hasPassword && PasswordHasher.verify(pw, s().passwordHash)

    fun signOut() = viewModelScope.launch { g.settings.update { it.copy(signedIn = false, authToken = "") } }

    fun wipeAll(onDone: () -> Unit) = viewModelScope.launch {
        repo.wipe(); g.settings.update { AppSettings(theme = it.theme) }; onDone()
    }

    // ---------- ledger ----------
    fun saveParty(p: Party, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        if (p.name.isBlank()) return@launch say("اكتب الاسم")
        val id = repo.saveParty(p); onSaved(if (p.id == 0L) id else p.id)
        say("تم الحفظ ✅")
    }

    fun deleteParty(id: Long, onDone: () -> Unit) = viewModelScope.launch { repo.deleteParty(id); say("تم الحذف"); onDone() }

    fun addPayment(partyId: Long, amount: Long, date: LocalDate, note: String, collector: String = "", receipt: String = "") = viewModelScope.launch {
        if (amount <= 0) return@launch say("اكتب المبلغ")
        repo.addPayment(partyId, amount, date, note, collector, receipt); say("تم تسجيل الدفعة ✅")
    }

    fun addCharge(partyId: Long, amount: Long, date: LocalDate, note: String, items: String, due: LocalDate?) = viewModelScope.launch {
        if (amount <= 0) return@launch say("اكتب المبلغ")
        repo.addCharge(partyId, amount, date, note, items, due)
        val p = repo.findParty(partyId)
        if (p != null && p.creditLimit > 0) {
            val bal = Ledger.balance(p, repo.allEntries().filter { it.partyId == partyId }, LocalDate.now())
            if (bal >= p.creditLimit) {
                say("⚠️ وصل حد الدين: ${Money.format(bal, s().currency)} من ${Money.format(p.creditLimit, s().currency)}")
                if (s().alertsEnabled) com.daftari.app.notify.Alerts.limitReached(getApplication(), p.id, p.name, Money.format(bal, s().currency), Money.format(p.creditLimit, s().currency))
            } else if (bal >= p.creditLimit * 8 / 10) say("تم ✅ · قرّب من حد الدين (${Money.format(bal, s().currency)} من ${Money.format(p.creditLimit, s().currency)})")
            else say("تم تسجيل الدين ✅")
        } else say("تم تسجيل الدين ✅")
    }

    fun deleteEntry(id: Long) = viewModelScope.launch { repo.deleteEntry(id); say("تم الحذف") }

    fun attach(partyId: Long, uri: Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "مرفق"
        runCatching { repo.attach(partyId, uri, name, mime) }.onSuccess { say("تم إرفاق الملف") }.onFailure { say("ما قدرت أقرأ الملف") }
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
        busy.value = "نجهّز الملف…"
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
            (if (p.kind == PartyKind.SHOP && p.shopNumber.isNotBlank()) " (محل ${p.shopNumber})" else "") + ".\nشاكرين تعاونك 🌷"
    }

    // ---------- chat «مدين» ----------
    val chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatBusy = MutableStateFlow(false)

    fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty()) return
        chat.value = chat.value + ChatMessage(true, question)
        viewModelScope.launch {
            chatBusy.value = true
            val d = dashboard.first { it != null }!!
            val st = s()
            val today = LocalDate.now()
            val reply: ChatMessage = try {
                if (!st.signedIn || !g.api.configured) throw ApiException("not_signed", "")
                val history = chat.value.takeLast(12).map { ChatTurn(if (it.fromUser) "user" else "assistant", it.text) }
                val r = g.api.chat(ChatRequest(history, ChatContext.build(d, st.role, st.businessName, st.ownerName, st.currency, today)))
                ChatMessage(false, r.reply, r.table, r.action)
            } catch (e: ApiException) {
                LocalAnswers.answer(question, d, st.role, st.currency, today) ?: ChatMessage(false, when (e.code) {
                    "offline" -> "ما في اتصال الحين 📶\n" + LocalAnswers.NOT_READY.substringAfter("\n")
                    "rate_limited" -> e.message
                    else -> LocalAnswers.NOT_READY
                })
            }
            chat.value = chat.value + reply
            chatBusy.value = false
        }
    }

    fun clearChat() { chat.value = emptyList() }

    /** Runs a change the assistant proposed, after the fingerprint/password check passed. */
    fun runAction(msgId: Long, a: ChatAction, confirmed: Boolean) = viewModelScope.launch {
        if (!confirmed) { mark(msgId, false); return@launch }
        val parties = repo.allParties()
        val p = parties.firstOrNull { it.shopNumber.isNotBlank() && it.shopNumber == a.party.trim() }
            ?: parties.firstOrNull { it.name == a.party.trim() } ?: parties.firstOrNull { a.party.isNotBlank() && it.name.contains(a.party.trim()) }
        val amount = Money.parse(a.amount.toString()) ?: 0
        val today = LocalDate.now()
        val msg = when (a.type) {
            "add_payment" -> p?.let { repo.addPayment(it.id, amount, today, a.note.ifBlank { "عن طريق مدين" }); "تم تسجيل دفعة ${Money.format(amount, s().currency)} لـ ${it.name} ✅" }
            "add_debt", "add_charge" -> p?.let { repo.addCharge(it.id, amount, today, a.note.ifBlank { "عن طريق مدين" }); "تم تسجيل دين ${Money.format(amount, s().currency)} على ${it.name} ✅" }
            "add_party" -> {
                val landlord = s().role == Role.LANDLORD
                repo.saveParty(Party(kind = if (landlord) PartyKind.SHOP else PartyKind.CUSTOMER, name = a.name.ifBlank { a.party }, phone = a.phone,
                    activity = a.activity, shopNumber = a.shopNumber, periodicAmount = Money.parse(a.periodicAmount.toString()) ?: 0,
                    period = if (landlord && a.periodicAmount > 0) Period.DAILY else Period.NONE))
                "تمت الإضافة ✅"
            }
            "edit_party" -> p?.let {
                repo.saveParty(it.copy(name = a.name.ifBlank { it.name }, phone = a.phone.ifBlank { it.phone }, activity = a.activity.ifBlank { it.activity },
                    periodicAmount = if (a.periodicAmount > 0) Money.parse(a.periodicAmount.toString()) ?: it.periodicAmount else it.periodicAmount))
                "تم التعديل ✅"
            }
            "delete_party" -> p?.let { repo.deleteParty(it.id); "تم حذف ${it.name}" }
            "delete_entry" -> { repo.deleteEntry(a.entryId); "تم حذف العملية" }
            else -> "ما فهمت العملية"
        } ?: "ما لقيت «${a.party}» في دفترك"
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
        busy.value = "نقرأ الملف…"
        try {
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            val local = SheetImporter.parse(name, bytes)
            val isImage = mime.startsWith("image/")
            val isPdf = mime.contains("pdf") || name.lowercase().endsWith(".pdf")
            if (!smart && local.rows.isNotEmpty()) { importResult.value = local; return@launch }
            busy.value = "مدين يرتّب البيانات…"
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
        } catch (e: Exception) { say("ما قدرت أقرأ الملف") } finally { busy.value = null }
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
        busy.value = "ننسخ احتياطياً…"
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
        busy.value = "نتحقق من الكود…"
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
        val json = com.daftari.app.data.Backup.toJson(repo.allParties(), repo.allEntries(), s().businessName, s().ownerName, s().role.name)
        val f = withContext(Dispatchers.IO) {
            val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            java.io.File(dir, "daftari_backup_${LocalDate.now()}.json").apply { writeText(json) }
        }
        withContext(Dispatchers.IO) { com.daftari.app.notify.DailyWorker.writeLocalBackup(getApplication(), json) }
        then(f)
    }

    fun restoreFrom(uri: Uri, onDone: () -> Unit) = viewModelScope.launch {
        try {
            val text = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
            val snap = com.daftari.app.data.Backup.parse(text)
            repo.wipe()
            val ids = HashMap<Long, Long>()
            snap.parties.forEach { p -> ids[p.id] = repo.saveParty(p.copy(id = 0)) }
            snap.entries.forEach { e -> ids[e.partyId]?.let { repo.addEntry(e.copy(id = 0, partyId = it)) } }
            say("تمت الاستعادة ✅ (${snap.parties.size} حساب)"); onDone()
        } catch (e: Exception) { say(e.message?.takeIf { it.any { ch -> ch in '\u0600'..'\u06FF' } } ?: "الملف غير صالح") }
    }
}
