package com.daftari.app.data

import android.content.Context
import android.net.Uri
import com.daftari.app.audit.Action
import com.daftari.app.audit.AuditLog
import com.daftari.app.audit.AuditRecord
import com.daftari.app.audit.Via
import com.daftari.app.core.Money
import com.daftari.app.core.fxNote
import com.daftari.app.domain.Attachment
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class Dashboard(
    val summaries: List<PartySummary>,
    val totalOwed: Long,
    val lateCount: Int,
    val partialCount: Int,
    val paidCount: Int,
    val collectedToday: Long,
    val expectedToday: Long,
    val paidTodayCount: Int,
    val collectedMonth: Long,
    val newDebtToday: Long = 0,
    /** customers with a promised due date today or earlier and still owing */
    val dueNow: List<Pair<PartySummary, LocalDate>> = emptyList(),
    val overLimit: List<PartySummary> = emptyList(),
)

/** Who did it, for «السجل». */
data class Actor(val name: String, val device: String)

/**
 * The one place that writes the ledger. Every write also goes to «السجل» ([AuditLog]) with a full before/after snapshot.
 */
class LedgerRepository(
    private val db: DaftariDb,
    private val appContext: Context?,
    val audit: AuditLog? = null,
    private val actor: suspend () -> Actor = { Actor("المالك", "") },
) {
    private val dao = db.dao()

    val parties: Flow<List<Party>> = dao.observeParties().map { l -> l.map { it.toDomain() } }
    val entries: Flow<List<Entry>> = dao.observeEntries().map { l -> l.map { it.toDomain() } }

    fun summaries(today: () -> LocalDate, lateAfterDays: () -> Int): Flow<List<PartySummary>> =
        combine(parties, entries) { ps, es ->
            val byParty = es.groupBy { it.partyId }
            ps.map { Ledger.summarize(it, byParty[it.id].orEmpty(), today(), lateAfterDays()) }
        }

    fun dashboard(today: () -> LocalDate, lateAfterDays: () -> Int, conv: (Party, Long) -> Long = { _, v -> v }): Flow<Dashboard> =
        combine(summaries(today, lateAfterDays), entries) { s, es -> buildDashboard(s, es, today(), conv) }

    /** Accounts from before 1.1.0 get the currency they were shown in. */
    suspend fun fillLegacyCurrency(code: String) = dao.fillPartyCurrency(code)

    fun party(id: Long): Flow<Party?> = dao.observeParty(id).map { it?.toDomain() }
    fun entriesFor(id: Long): Flow<List<Entry>> = dao.observeEntriesFor(id).map { l -> l.map { it.toDomain() } }
    fun attachments(id: Long): Flow<List<Attachment>> = dao.observeAttachments(id).map { l -> l.map { it.toDomain() } }

    suspend fun allParties(): List<Party> = dao.parties().map { it.toDomain() }
    suspend fun allEntries(): List<Entry> = dao.entries().map { it.toDomain() }
    suspend fun findParty(id: Long): Party? = dao.party(id)?.toDomain()
    /** Duplicate guard for رقم الحوالة. */
    suspend fun entryByTransfer(no: String): Entry? = if (no.isBlank()) null else dao.byTransfer(no.trim())?.toDomain()
    suspend fun findEntry(id: Long): Entry? = dao.entry(id)?.takeIf { !it.deleted }?.toDomain()

    // ------------------------------------------------------------------ log helpers
    private suspend fun log(via: String, action: String, entity: String, id: Long, partyName: String, title: String, before: JSONObject?, after: JSONObject?, ref: Long = 0) {
        val a = audit ?: return
        val who = actor()
        a.append(who.name, who.device, via, action, entity, id, partyName, title, before?.toString() ?: "", after?.toString() ?: "", ref)
    }

    private fun entryWord(e: Entry) = if (e.type == EntryType.PAYMENT) "سداد" else "دين"
    private fun entryTitle(e: Entry, name: String): String {
        val fx = e.fxNote().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val what = listOf(e.items, e.note).firstOrNull { it.isNotBlank() && it != "حوالة" && !it.startsWith("محفظة") && it != "عن طريق مدين" }?.let { " — $it" }.orEmpty() + fx
        return if (e.type == EntryType.PAYMENT) "سداد ${Money.number(e.amount)} من «$name»$what" else "دين ${Money.number(e.amount)} على «$name»$what"
    }
    private suspend fun nameOf(partyId: Long) = dao.party(partyId)?.name ?: "?"

    private fun changed(a: Party, b: Party): String = buildList {
        if (a.name != b.name) add("الاسم"); if (a.phone != b.phone) add("الجوال"); if (a.activity != b.activity) add("النشاط")
        if (a.periodicAmount != b.periodicAmount) add("المبلغ الدوري"); if (a.creditLimit != b.creditLimit) add("سقف الدين")
        if (a.openingBalance != b.openingBalance) add("الرصيد السابق"); if (a.notes != b.notes) add("الملاحظات"); if (a.category != b.category) add("التصنيف")
        if (a.shopNumber != b.shopNumber) add("رقم المحل")
    }.joinToString("، ").ifBlank { "دون تغيير ظاهر" }

    // ------------------------------------------------------------------ writes (all logged)
    suspend fun saveParty(p: Party, via: String = Via.MANUAL): Long {
        val before = if (p.id != 0L) dao.party(p.id)?.takeIf { !it.deleted }?.toDomain() else null
        val id = dao.upsertParty(PartyEntity.from(p.copy(updatedAt = System.currentTimeMillis())))
        val realId = if (p.id == 0L) id else p.id
        val after = dao.party(realId)!!.toDomain()
        if (before == null) log(via, Action.CREATE, "PARTY", realId, after.name, "إضافة حساب «${after.name}»", null, Snap.party(after))
        else log(via, Action.UPDATE, "PARTY", realId, after.name, "تعديل حساب «${after.name}»: ${changed(before, after)}", Snap.party(before), Snap.party(after))
        return realId
    }

    suspend fun deleteParty(id: Long, via: String = Via.MANUAL) {
        val p = dao.party(id)?.takeIf { !it.deleted }?.toDomain() ?: return
        val es = dao.entriesOf(id).map { it.toDomain() }
        dao.deleteParty(id, System.currentTimeMillis()); dao.deleteEntriesFor(id)
        log(via, Action.DELETE, "PARTY", id, p.name, "حذف حساب «${p.name}»" + if (es.isNotEmpty()) " مع ${es.size} عملية" else "", Snap.partyWithEntries(p, es), null)
    }

    suspend fun addEntry(e: Entry, via: String = Via.MANUAL): Long {
        val id = dao.insertEntry(EntryEntity.from(e))
        val saved = e.copy(id = id)
        log(via, Action.CREATE, "ENTRY", id, nameOf(e.partyId), entryTitle(saved, nameOf(e.partyId)), null, Snap.entry(saved))
        return id
    }

    suspend fun updateEntry(e: Entry, via: String = Via.MANUAL) {
        val before = dao.entry(e.id)?.toDomain()
        dao.updateEntry(EntryEntity.from(e))
        val name = nameOf(e.partyId)
        log(via, Action.UPDATE, "ENTRY", e.id, name, "تعديل عملية: " + entryTitle(e, name), before?.let { Snap.entry(it) }, Snap.entry(e))
    }

    suspend fun deleteEntry(id: Long, via: String = Via.MANUAL) {
        val e = dao.entry(id)?.takeIf { !it.deleted }?.toDomain() ?: return
        dao.deleteEntry(id)
        val name = nameOf(e.partyId)
        log(via, Action.DELETE, "ENTRY", id, name, "حذف " + entryTitle(e, name), Snap.entry(e), null)
    }

    suspend fun addPayment(partyId: Long, amount: Long, date: LocalDate, note: String, collector: String = "", receiptNo: String = "", via: String = Via.MANUAL) =
        addEntry(Entry(partyId = partyId, type = EntryType.PAYMENT, amount = amount, date = date, note = note, collector = collector, receiptNo = receiptNo), via)
    suspend fun addCharge(partyId: Long, amount: Long, date: LocalDate, note: String, items: String = "", dueDate: LocalDate? = null, via: String = Via.MANUAL) =
        addEntry(Entry(partyId = partyId, type = EntryType.CHARGE, amount = amount, date = date, note = note, items = items, dueDate = dueDate), via)

    /** Copies a picked file (contract photo / PDF) into private storage. */
    suspend fun attach(partyId: Long, uri: Uri, displayName: String, mime: String, entryId: Long = 0): Long = withContext(Dispatchers.IO) {
        val ctx = requireNotNull(appContext)
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        val ext = when {
            mime.contains("pdf") -> "pdf"
            mime.contains("png") -> "png"
            else -> "jpg"
        }
        val name = "p${partyId}_${System.currentTimeMillis()}.$ext"
        ctx.contentResolver.openInputStream(uri)!!.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        dao.insertAttachment(AttachmentEntity(partyId = partyId, fileName = name, displayName = displayName, mime = mime, createdAt = System.currentTimeMillis(), entryId = entryId))
    }

    suspend fun deleteAttachment(a: Attachment) {
        appContext?.let { File(File(it.filesDir, "attachments"), a.fileName).delete() }
        dao.deleteAttachment(a.id)
    }

    /** «مسح كل البيانات»: the whole book goes into the log first, so even this can be restored. The log itself is never wiped. */
    suspend fun wipe(via: String = Via.MANUAL) {
        val ps = allParties(); val es = allEntries()
        if (ps.isNotEmpty() || es.isNotEmpty())
            log(via, Action.WIPE, "ALL", 0, "", "مسح كل البيانات (${ps.size} حساب، ${es.size} عملية)", Snap.all(ps, es), null)
        dao.wipeEntries(); dao.wipeAttachments(); dao.wipeParties()
    }

    // ------------------------------------------------------------------ restore from «السجل»
    sealed interface RestoreResult { data class Done(val message: String) : RestoreResult; data class Failed(val message: String) : RestoreResult }

    /** Puts back what a DELETE / UPDATE / WIPE / BACKUP_RESTORE record held, with the same ids. Logged as RESTORE (ref = that record). */
    suspend fun restoreFromLog(r: AuditRecord, via: String = Via.LOG): RestoreResult {
        if (!r.restorable) return RestoreResult.Failed("هذه العملية لا تحتوي على شيء للاسترجاع")
        val before = JSONObject(r.before)
        val title = "استرجاع: ${r.title}"
        return when {
            r.entity == "PARTY" && r.action == Action.DELETE -> {
                val p = Snap.partyFrom(before.getJSONObject("party"))
                val es = Snap.entries(before)
                dao.upsertParty(PartyEntity.from(p))
                es.forEach { dao.upsertEntry(EntryEntity.from(it)) }
                log(via, Action.RESTORE, "PARTY", p.id, p.name, title, null, before, r.seq)
                RestoreResult.Done("استُعيد حساب «${p.name}»" + if (es.isNotEmpty()) " مع ${es.size} عملية ✅" else " ✅")
            }
            r.entity == "PARTY" && r.action == Action.UPDATE -> {
                val p = Snap.partyFrom(before)
                val cur = dao.party(p.id)?.toDomain()
                dao.upsertParty(PartyEntity.from(p.copy(updatedAt = System.currentTimeMillis())))
                log(via, Action.RESTORE, "PARTY", p.id, p.name, title, cur?.let { Snap.party(it) }, before, r.seq)
                RestoreResult.Done("استُعيدت بيانات «${p.name}» كما كانت ✅")
            }
            r.entity == "ENTRY" -> {
                val e = Snap.entryFrom(before)
                val pr = dao.party(e.partyId) ?: return RestoreResult.Failed("حساب «${r.partyName}» نفسه حُذف نهائياً — استرجعه من السجل أولاً")
                val revived = pr.deleted
                if (revived) dao.undeleteParty(e.partyId)
                val cur = dao.entry(e.id)?.takeIf { !it.deleted }?.toDomain()
                dao.upsertEntry(EntryEntity.from(e))
                log(via, Action.RESTORE, "ENTRY", e.id, pr.name, title, cur?.let { Snap.entry(it) }, before, r.seq)
                RestoreResult.Done((if (r.action == Action.UPDATE) "استُعيدت العملية كما كانت" else "استُعيدت العملية إلى حساب «${pr.name}»") + (if (revived) " (واستُعيد الحساب نفسه)" else "") + " ✅")
            }
            r.action == Action.WIPE || r.action == Action.BACKUP_RESTORE -> {
                val ps = Snap.parties(before); val es = Snap.entries(before)
                ps.forEach { dao.upsertParty(PartyEntity.from(it)) }
                es.forEach { dao.upsertEntry(EntryEntity.from(it)) }
                log(via, Action.RESTORE, "ALL", 0, "", title, null, JSONObject().put("parties", ps.size).put("entries", es.size), r.seq)
                RestoreResult.Done("استُعيد ${ps.size} حساب و${es.size} عملية ✅")
            }
            else -> RestoreResult.Failed("لا يمكن استرجاع هذه العملية")
        }
    }

    /** «تراجع» from «مدين»: undoes the newest change (adds are removed, deletes / edits are put back). */
    suspend fun undoLast(): RestoreResult {
        val last = audit?.last() ?: return RestoreResult.Failed("لا توجد عمليات للتراجع عنها")
        return undo(last)
    }

    suspend fun undo(r: AuditRecord): RestoreResult = when {
        r.action == Action.CREATE && r.entity == "ENTRY" -> {
            if (dao.entry(r.entityId)?.deleted != false) RestoreResult.Failed("العملية محذوفة مسبقاً")
            else { deleteEntry(r.entityId, Via.UNDO); RestoreResult.Done("تراجعت: ${r.title} ✅") }
        }
        r.action == Action.CREATE && r.entity == "PARTY" -> { deleteParty(r.entityId, Via.UNDO); RestoreResult.Done("تراجعت: ${r.title} ✅") }
        r.restorable -> restoreFromLog(r, Via.UNDO)
        else -> RestoreResult.Failed("لا يمكن التراجع عن آخر عملية («${r.title}»)")
    }

    // ------------------------------------------------------------------ backup merge
    data class MergeStats(val partiesAdded: Int, val partiesUpdated: Int, val entriesAdded: Int, val entriesSkipped: Int, val logAdopted: Boolean)

    /**
     * Brings a backup in without ever duplicating: the same book (same bookId, or an empty phone) merges by ids; another book
     * matches customers by phone / name and skips operations already present. The current book goes into the log first.
     */
    suspend fun mergeBackup(d: com.daftari.app.backup.BackupData, sameBook: Boolean, title: String): MergeStats {
        val curP = allParties(); val curE = allEntries()
        val adopted = if (curP.isEmpty() && curE.isEmpty() && d.log.isNotEmpty()) audit?.adopt(d.log) == true else false
        log(Via.BACKUP, Action.BACKUP_RESTORE, "ALL", 0, "", title, Snap.all(curP, curE),
            JSONObject().put("customers", d.parties.size).put("entries", d.entries.size).put("logHead", d.log.lastOrNull()?.hash ?: ""))
        var pAdd = 0; var pUpd = 0; var eAdd = 0; var eSkip = 0
        val empty = curP.isEmpty() && curE.isEmpty()
        if (empty || sameBook) {
            val existingIds = curP.map { it.id }.toSet()
            d.parties.forEach { p -> if (p.id in existingIds) pUpd++ else pAdd++; dao.upsertParty(PartyEntity.from(p)) }
            val curById = curE.associateBy { it.id }
            d.entries.forEach { e ->
                val same = curById[e.id]
                if (same != null && same == e) eSkip++ else { dao.upsertEntry(EntryEntity.from(e)); eAdd++ }
            }
        } else {
            val byPhone = curP.filter { it.phone.isNotBlank() }.associateBy { it.phone.filter(Char::isDigit).takeLast(9) }
            val byName = curP.associateBy { com.daftari.app.importer.StatementImporter.key(it.name) }
            val idMap = HashMap<Long, Long>()
            d.parties.forEach { p ->
                val m = (if (p.phone.isNotBlank()) byPhone[p.phone.filter(Char::isDigit).takeLast(9)] else null) ?: byName[com.daftari.app.importer.StatementImporter.key(p.name)]
                idMap[p.id] = m?.id ?: dao.upsertParty(PartyEntity.from(p.copy(id = 0))).also { pAdd++ }
            }
            val have = curE.map { listOf(it.partyId, it.type, it.amount, it.date, it.items, it.note).joinToString("|") }.toHashSet()
            d.entries.forEach { e ->
                val pid = idMap[e.partyId] ?: return@forEach
                val k = listOf(pid, e.type, e.amount, e.date, e.items, e.note).joinToString("|")
                if (!have.add(k)) eSkip++ else { dao.insertEntry(EntryEntity.from(e.copy(id = 0, partyId = pid))); eAdd++ }
            }
        }
        return MergeStats(pAdd, pUpd, eAdd, eSkip, adopted)
    }

    companion object {
        /** [conv] turns an amount of an account (its own currency) into the app's default currency for the totals. */
        fun buildDashboard(s: List<PartySummary>, es: List<Entry>, today: LocalDate, conv: (Party, Long) -> Long = { _, v -> v }): Dashboard {
            val monthStart = today.withDayOfMonth(1)
            val byId = s.associate { it.party.id to it.party }
            fun cv(partyId: Long, v: Long) = byId[partyId]?.let { conv(it, v) } ?: v
            return Dashboard(
                summaries = s,
                totalOwed = s.sumOf { conv(it.party, it.balance.coerceAtLeast(0)) },
                lateCount = s.count { it.status == com.daftari.app.domain.Status.LATE },
                partialCount = s.count { it.status == com.daftari.app.domain.Status.PARTIAL || it.status == com.daftari.app.domain.Status.OWES },
                paidCount = s.count { it.status == com.daftari.app.domain.Status.PAID },
                collectedToday = s.sumOf { conv(it.party, it.paidToday) },
                expectedToday = s.sumOf { Ledger.accrued(it.party, today, today) },
                paidTodayCount = s.count { it.paidToday > 0 },
                collectedMonth = es.filter { it.type == EntryType.PAYMENT && !it.date.isBefore(monthStart) && !it.date.isAfter(today) }.sumOf { cv(it.partyId, it.amount) },
                newDebtToday = es.filter { it.type == EntryType.CHARGE && it.date == today }.sumOf { cv(it.partyId, it.amount) },
                dueNow = s.filter { it.balance > 0 }.mapNotNull { sm ->
                    es.filter { it.partyId == sm.party.id && it.type == EntryType.CHARGE && it.dueDate != null && !it.dueDate.isAfter(today) }
                        .minOfOrNull { it.dueDate!! }?.let { sm to it }
                }.sortedBy { it.second },
                overLimit = s.filter { it.party.creditLimit > 0 && it.balance > it.party.creditLimit },
            )
        }
    }
}
