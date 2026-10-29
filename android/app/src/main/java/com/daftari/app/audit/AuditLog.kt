package com.daftari.app.audit

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * «السجل»: the permanent, append-only log. Every create / edit / delete / restore of a customer or an operation is one row
 * with who, when, through what, and the full before / after snapshot. Rows are hash-chained (SHA-256 of the row + the previous
 * row's hash), SQLite triggers refuse any UPDATE or DELETE on the table, and the head hash is anchored outside the database,
 * so a removed, edited or truncated row is detected by [AuditLog.verify]. It lives in its own database file: «مسح كل البيانات»
 * and restoring a backup never touch it. There is no delete anywhere in this file.
 */
@Entity(tableName = "audit_log")
data class AuditRecord(
    @PrimaryKey val seq: Long,
    val at: Long,
    val actor: String,
    val device: String,
    val via: String,
    /** CREATE · UPDATE · DELETE · RESTORE · WIPE · BACKUP_RESTORE */
    val action: String,
    /** PARTY · ENTRY · ALL */
    val entity: String,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "party_name") val partyName: String,
    val title: String,
    val before: String,
    val after: String,
    @ColumnInfo(name = "ref_seq") val refSeq: Long = 0,
    @ColumnInfo(name = "prev_hash") val prevHash: String,
    val hash: String,
) {
    val restorable: Boolean get() = action in setOf(Action.DELETE, Action.UPDATE, Action.WIPE, Action.BACKUP_RESTORE) && before.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq); put("at", at); put("actor", actor); put("device", device); put("via", via); put("action", action); put("entity", entity)
        put("entityId", entityId); put("partyName", partyName); put("title", title); put("before", before); put("after", after); put("refSeq", refSeq)
        put("prevHash", prevHash); put("hash", hash)
    }

    companion object {
        fun fromJson(o: JSONObject) = AuditRecord(
            o.getLong("seq"), o.getLong("at"), o.optString("actor"), o.optString("device"), o.optString("via"), o.getString("action"), o.getString("entity"),
            o.optLong("entityId"), o.optString("partyName"), o.optString("title"), o.optString("before"), o.optString("after"), o.optLong("refSeq"),
            o.getString("prevHash"), o.getString("hash"),
        )
    }
}

object Action {
    const val CREATE = "CREATE"; const val UPDATE = "UPDATE"; const val DELETE = "DELETE"; const val RESTORE = "RESTORE"
    const val WIPE = "WIPE"; const val BACKUP_RESTORE = "BACKUP_RESTORE"

    fun labelAr(a: String) = when (a) {
        CREATE -> "إضافة"; UPDATE -> "تعديل"; DELETE -> "حذف"; RESTORE -> "استرجاع"; WIPE -> "مسح الكل"; BACKUP_RESTORE -> "استعادة نسخة"; else -> a
    }
}

object Via {
    const val MANUAL = "يدوي"; const val ASSISTANT = "مدين"; const val IMPORT = "استيراد"; const val LOG = "السجل"; const val UNDO = "تراجع"; const val BACKUP = "نسخة احتياطية"
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(r: AuditRecord)

    @Query("SELECT * FROM audit_log ORDER BY seq DESC")
    fun observeAll(): Flow<List<AuditRecord>>

    @Query("SELECT * FROM audit_log ORDER BY seq ASC")
    suspend fun allAsc(): List<AuditRecord>

    @Query("SELECT * FROM audit_log ORDER BY seq DESC LIMIT 1")
    suspend fun last(): AuditRecord?

    @Query("SELECT * FROM audit_log WHERE seq = :seq")
    suspend fun bySeq(seq: Long): AuditRecord?

    @Query("SELECT * FROM audit_log WHERE ref_seq = :seq AND action = 'RESTORE' ORDER BY seq DESC LIMIT 1")
    suspend fun restoreOf(seq: Long): AuditRecord?

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Long
}

@Database(entities = [AuditRecord::class], version = 1, exportSchema = false)
abstract class AuditDb : RoomDatabase() {
    abstract fun dao(): AuditDao

    companion object {
        private val GUARDS = listOf(
            "CREATE TRIGGER IF NOT EXISTS audit_no_update BEFORE UPDATE ON audit_log BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END",
            "CREATE TRIGGER IF NOT EXISTS audit_no_delete BEFORE DELETE ON audit_log BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END",
        )
        private val guard = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = GUARDS.forEach { db.execSQL(it) }
            override fun onOpen(db: SupportSQLiteDatabase) = GUARDS.forEach { db.execSQL(it) }
        }

        fun create(context: Context, inMemory: Boolean = false): AuditDb =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, AuditDb::class.java).allowMainThreadQueries()
            else Room.databaseBuilder(context, AuditDb::class.java, "daftari_log.db")).addCallback(guard).build()
    }
}

/** Where the head of the chain is anchored outside the log database (SharedPreferences on the phone). */
interface HeadAnchor {
    fun read(): Pair<Long, String>?
    fun write(seq: Long, hash: String)
}

class PrefsAnchor(context: Context) : HeadAnchor {
    private val p = context.getSharedPreferences("daftari_log_anchor", Context.MODE_PRIVATE)
    override fun read(): Pair<Long, String>? = p.getString("head", null)?.split("|")?.takeIf { it.size == 2 }?.let { it[0].toLong() to it[1] }
    override fun write(seq: Long, hash: String) { p.edit().putString("head", "$seq|$hash").apply() }
}

data class Verify(val ok: Boolean, val count: Long, val brokenAt: Long? = null, val reason: String = "") {
    val messageAr: String get() = if (ok) "السجل سليم ✅ · $count عملية · مسلسل ومختوم" else "⚠️ تلاعب في السجل: $reason"
}

class AuditLog(private val dao: AuditDao, private val anchor: HeadAnchor?) {
    private val lock = Mutex()
    val records: Flow<List<AuditRecord>> = dao.observeAll()

    suspend fun append(
        actor: String, device: String, via: String, action: String, entity: String, entityId: Long, partyName: String, title: String,
        before: String, after: String, refSeq: Long = 0, at: Long = System.currentTimeMillis(),
    ): AuditRecord = lock.withLock {
        val last = dao.last()
        val seq = (last?.seq ?: 0) + 1
        val prev = last?.hash ?: GENESIS
        val r = AuditRecord(seq, at, actor, device, via, action, entity, entityId, partyName, title, before, after, refSeq, prev, "")
        val sealed = r.copy(hash = hashOf(r))
        dao.insert(sealed)
        anchor?.write(seq, sealed.hash)
        sealed
    }

    suspend fun all(): List<AuditRecord> = dao.allAsc()
    suspend fun bySeq(seq: Long) = dao.bySeq(seq)
    suspend fun restoreOf(seq: Long) = dao.restoreOf(seq)
    suspend fun last() = dao.last()
    suspend fun count() = dao.count()

    suspend fun verify(): Verify = verifyChain(dao.allAsc(), anchor?.read())

    /** On a fresh phone, adopt the log carried in a backup (only when this phone's log is empty and the chain checks out). */
    suspend fun adopt(records: List<AuditRecord>): Boolean = lock.withLock {
        if (dao.count() > 0 || records.isEmpty()) return@withLock false
        val sorted = records.sortedBy { it.seq }
        if (!verifyChain(sorted, null).ok) return@withLock false
        sorted.forEach { dao.insert(it) }
        anchor?.write(sorted.last().seq, sorted.last().hash)
        true
    }

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

        fun hashOf(r: AuditRecord): String {
            val canonical = listOf(r.seq, r.at, r.actor, r.device, r.via, r.action, r.entity, r.entityId, r.partyName, r.title, r.before, r.after, r.refSeq, r.prevHash)
                .joinToString("\u001F")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        fun verifyChain(rows: List<AuditRecord>, anchor: Pair<Long, String>?): Verify {
            var prev = GENESIS
            rows.forEachIndexed { i, r ->
                if (r.seq != i + 1L) return Verify(false, rows.size.toLong(), r.seq, "عملية مفقودة قبل رقم ${r.seq}")
                if (r.prevHash != prev) return Verify(false, rows.size.toLong(), r.seq, "الربط مكسور عند العملية ${r.seq}")
                if (hashOf(r) != r.hash) return Verify(false, rows.size.toLong(), r.seq, "العملية ${r.seq} عُدّلت بعد تسجيلها")
                prev = r.hash
            }
            if (anchor != null) {
                val (aSeq, aHash) = anchor
                val last = rows.lastOrNull()
                if (last == null || last.seq < aSeq) return Verify(false, rows.size.toLong(), aSeq, "حُذفت عمليات من آخر السجل (كان يحتوي على $aSeq)")
                val atAnchor = rows.getOrNull((aSeq - 1).toInt())
                if (atAnchor?.hash != aHash) return Verify(false, rows.size.toLong(), aSeq, "ختم السجل لا يطابق")
            }
            return Verify(true, rows.size.toLong())
        }

        fun toJsonArray(rows: List<AuditRecord>): JSONArray = JSONArray().apply { rows.forEach { put(it.toJson()) } }
        fun fromJsonArray(a: JSONArray?): List<AuditRecord> = a?.let { (0 until it.length()).map { i -> AuditRecord.fromJson(it.getJSONObject(i)) } }.orEmpty()
    }
}
