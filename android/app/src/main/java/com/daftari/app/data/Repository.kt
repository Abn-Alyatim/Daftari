package com.daftari.app.data

import android.content.Context
import android.net.Uri
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

class LedgerRepository(private val db: DaftariDb, private val appContext: Context?) {
    private val dao = db.dao()

    val parties: Flow<List<Party>> = dao.observeParties().map { l -> l.map { it.toDomain() } }
    val entries: Flow<List<Entry>> = dao.observeEntries().map { l -> l.map { it.toDomain() } }

    fun summaries(today: () -> LocalDate, lateAfterDays: () -> Int): Flow<List<PartySummary>> =
        combine(parties, entries) { ps, es ->
            val byParty = es.groupBy { it.partyId }
            ps.map { Ledger.summarize(it, byParty[it.id].orEmpty(), today(), lateAfterDays()) }
        }

    fun dashboard(today: () -> LocalDate, lateAfterDays: () -> Int): Flow<Dashboard> =
        combine(summaries(today, lateAfterDays), entries) { s, es -> buildDashboard(s, es, today()) }

    fun party(id: Long): Flow<Party?> = dao.observeParty(id).map { it?.toDomain() }
    fun entriesFor(id: Long): Flow<List<Entry>> = dao.observeEntriesFor(id).map { l -> l.map { it.toDomain() } }
    fun attachments(id: Long): Flow<List<Attachment>> = dao.observeAttachments(id).map { l -> l.map { it.toDomain() } }

    suspend fun allParties(): List<Party> = dao.parties().map { it.toDomain() }
    suspend fun allEntries(): List<Entry> = dao.entries().map { it.toDomain() }
    suspend fun findParty(id: Long): Party? = dao.party(id)?.toDomain()

    suspend fun saveParty(p: Party): Long = dao.upsertParty(PartyEntity.from(p.copy(updatedAt = System.currentTimeMillis())))
    suspend fun deleteParty(id: Long) {
        dao.deleteParty(id, System.currentTimeMillis()); dao.deleteEntriesFor(id)
    }
    suspend fun addEntry(e: Entry): Long = dao.insertEntry(EntryEntity.from(e))
    suspend fun updateEntry(e: Entry) = dao.updateEntry(EntryEntity.from(e))
    suspend fun deleteEntry(id: Long) = dao.deleteEntry(id)

    suspend fun addPayment(partyId: Long, amount: Long, date: LocalDate, note: String, collector: String = "", receiptNo: String = "") =
        addEntry(Entry(partyId = partyId, type = EntryType.PAYMENT, amount = amount, date = date, note = note, collector = collector, receiptNo = receiptNo))
    suspend fun addCharge(partyId: Long, amount: Long, date: LocalDate, note: String, items: String = "", dueDate: LocalDate? = null) =
        addEntry(Entry(partyId = partyId, type = EntryType.CHARGE, amount = amount, date = date, note = note, items = items, dueDate = dueDate))

    /** Copies a picked file (contract photo / PDF) into private storage. */
    suspend fun attach(partyId: Long, uri: Uri, displayName: String, mime: String): Long = withContext(Dispatchers.IO) {
        val ctx = requireNotNull(appContext)
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        val ext = when {
            mime.contains("pdf") -> "pdf"
            mime.contains("png") -> "png"
            else -> "jpg"
        }
        val name = "p${partyId}_${System.currentTimeMillis()}.$ext"
        ctx.contentResolver.openInputStream(uri)!!.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        dao.insertAttachment(AttachmentEntity(partyId = partyId, fileName = name, displayName = displayName, mime = mime, createdAt = System.currentTimeMillis()))
    }

    suspend fun deleteAttachment(a: Attachment) {
        appContext?.let { File(File(it.filesDir, "attachments"), a.fileName).delete() }
        dao.deleteAttachment(a.id)
    }

    suspend fun wipe() { dao.wipeEntries(); dao.wipeAttachments(); dao.wipeParties() }

    companion object {
        fun buildDashboard(s: List<PartySummary>, es: List<Entry>, today: LocalDate): Dashboard {
            val monthStart = today.withDayOfMonth(1)
            return Dashboard(
                summaries = s,
                totalOwed = s.sumOf { it.balance.coerceAtLeast(0) },
                lateCount = s.count { it.status == com.daftari.app.domain.Status.LATE },
                partialCount = s.count { it.status == com.daftari.app.domain.Status.PARTIAL || it.status == com.daftari.app.domain.Status.OWES },
                paidCount = s.count { it.status == com.daftari.app.domain.Status.PAID },
                collectedToday = s.sumOf { it.paidToday },
                expectedToday = s.sumOf { Ledger.accrued(it.party, today, today) },
                paidTodayCount = s.count { it.paidToday > 0 },
                collectedMonth = es.filter { it.type == EntryType.PAYMENT && !it.date.isBefore(monthStart) && !it.date.isAfter(today) }.sumOf { it.amount },
                newDebtToday = es.filter { it.type == EntryType.CHARGE && it.date == today }.sumOf { it.amount },
                dueNow = s.filter { it.balance > 0 }.mapNotNull { sm ->
                    es.filter { it.partyId == sm.party.id && it.type == EntryType.CHARGE && it.dueDate != null && !it.dueDate.isAfter(today) }
                        .minOfOrNull { it.dueDate!! }?.let { sm to it }
                }.sortedBy { it.second },
                overLimit = s.filter { it.party.creditLimit > 0 && it.balance > it.party.creditLimit },
            )
        }
    }
}
