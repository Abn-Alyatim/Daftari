package com.daftari.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.daftari.app.domain.Attachment
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Period
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(tableName = "parties", indices = [Index("kind"), Index("deleted")])
data class PartyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "shop_number") val shopNumber: String,
    val name: String,
    val phone: String,
    val activity: String,
    @ColumnInfo(name = "periodic_amount") val periodicAmount: Long,
    val period: String,
    @ColumnInfo(name = "start_day") val startDay: Long,
    val notes: String,
    val location: String,
    @ColumnInfo(name = "opening_balance") val openingBalance: Long = 0,
    @ColumnInfo(name = "units_count") val unitsCount: Int = 1,
    @ColumnInfo(name = "credit_limit") val creditLimit: Long = 0,
    @ColumnInfo(name = "category", defaultValue = "") val category: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    @ColumnInfo(name = "currency", defaultValue = "") val currency: String = "",
) {
    fun toDomain() = Party(
        id = id, kind = PartyKind.valueOf(kind), shopNumber = shopNumber, name = name, phone = phone, activity = activity,
        periodicAmount = periodicAmount, period = runCatching { Period.valueOf(period) }.getOrDefault(Period.NONE),
        startDate = LocalDate.ofEpochDay(startDay), notes = notes, location = location, openingBalance = openingBalance, unitsCount = unitsCount, creditLimit = creditLimit, category = category, createdAt = createdAt, updatedAt = updatedAt, currency = currency,
    )

    companion object {
        fun from(p: Party) = PartyEntity(
            id = p.id, kind = p.kind.name, shopNumber = p.shopNumber, name = p.name, phone = p.phone, activity = p.activity,
            periodicAmount = p.periodicAmount, period = p.period.name, startDay = p.startDate.toEpochDay(), notes = p.notes,
            location = p.location, openingBalance = p.openingBalance, unitsCount = p.unitsCount, creditLimit = p.creditLimit, category = p.category, createdAt = p.createdAt, updatedAt = p.updatedAt, currency = p.currency,
        )
    }
}

@Entity(tableName = "entries", indices = [Index("party_id"), Index("day")])
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "party_id") val partyId: Long,
    val type: String,
    val amount: Long,
    val day: Long,
    val note: String,
    val collector: String = "",
    @ColumnInfo(name = "receipt_no") val receiptNo: String = "",
    val items: String = "",
    @ColumnInfo(name = "due_day") val dueDay: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val deleted: Boolean = false,
    @ColumnInfo(name = "currency", defaultValue = "") val currency: String = "",
    @ColumnInfo(name = "orig_amount", defaultValue = "0") val origAmount: Long = 0,
    @ColumnInfo(name = "rate", defaultValue = "") val rate: String = "",
    @ColumnInfo(name = "method", defaultValue = "") val method: String = "",
    @ColumnInfo(name = "transfer_no", defaultValue = "") val transferNo: String = "",
    @ColumnInfo(name = "transfer_co", defaultValue = "") val transferCo: String = "",
    @ColumnInfo(name = "sender_phone", defaultValue = "") val senderPhone: String = "",
) {
    fun toDomain() = Entry(id, partyId, EntryType.valueOf(type), amount, LocalDate.ofEpochDay(day), note, collector, receiptNo, items, dueDay?.let { LocalDate.ofEpochDay(it) }, createdAt, currency, origAmount, rate, method, transferNo, transferCo, senderPhone)

    companion object {
        fun from(e: Entry) = EntryEntity(e.id, e.partyId, e.type.name, e.amount, e.date.toEpochDay(), e.note, e.collector, e.receiptNo, e.items, e.dueDate?.toEpochDay(), e.createdAt, false, e.currency, e.origAmount, e.rate, e.method, e.transferNo, e.transferCo, e.senderPhone)
    }
}

@Entity(tableName = "attachments", indices = [Index("party_id")])
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "party_id") val partyId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val mime: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "entry_id", defaultValue = "0") val entryId: Long = 0,
) {
    fun toDomain() = Attachment(id, partyId, fileName, displayName, mime, createdAt, entryId)
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM parties WHERE deleted = 0 ORDER BY CASE WHEN shop_number GLOB '[0-9]*' THEN CAST(shop_number AS INTEGER) ELSE 999999 END, shop_number, name")
    fun observeParties(): Flow<List<PartyEntity>>

    @Query("SELECT * FROM parties WHERE deleted = 0")
    suspend fun parties(): List<PartyEntity>

    @Query("SELECT * FROM parties WHERE id = :id")
    suspend fun party(id: Long): PartyEntity?

    @Query("SELECT * FROM parties WHERE id = :id")
    fun observeParty(id: Long): Flow<PartyEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParty(p: PartyEntity): Long

    @Query("UPDATE parties SET deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun deleteParty(id: Long, now: Long)

    @Query("SELECT * FROM entries WHERE deleted = 0 ORDER BY day DESC, created_at DESC")
    fun observeEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deleted = 0")
    suspend fun entries(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE party_id = :partyId AND deleted = 0 ORDER BY day DESC, created_at DESC")
    fun observeEntriesFor(partyId: Long): Flow<List<EntryEntity>>

    @Insert
    suspend fun insertEntry(e: EntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(e: EntryEntity): Long

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: Long): EntryEntity?

    @Query("SELECT * FROM entries WHERE party_id = :partyId AND deleted = 0")
    suspend fun entriesOf(partyId: Long): List<EntryEntity>

    @Query("UPDATE parties SET deleted = 0 WHERE id = :id")
    suspend fun undeleteParty(id: Long)

    @Update
    suspend fun updateEntry(e: EntryEntity)

    @Query("UPDATE entries SET deleted = 1 WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("UPDATE entries SET deleted = 1 WHERE party_id = :partyId")
    suspend fun deleteEntriesFor(partyId: Long)

    @Query("SELECT * FROM attachments WHERE entry_id = :entryId ORDER BY created_at DESC")
    suspend fun attachmentsOfEntry(entryId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE party_id = :partyId ORDER BY created_at DESC")
    fun observeAttachments(partyId: Long): Flow<List<AttachmentEntity>>

    @Insert
    suspend fun insertAttachment(a: AttachmentEntity): Long

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    @Query("SELECT * FROM entries WHERE deleted = 0 AND transfer_no = :no LIMIT 1")
    suspend fun byTransfer(no: String): EntryEntity?

    @Query("UPDATE parties SET currency = :code WHERE currency = ''")
    suspend fun fillPartyCurrency(code: String): Int

    @Query("DELETE FROM entries")
    suspend fun wipeEntries()

    @Query("DELETE FROM parties")
    suspend fun wipeParties()

    @Query("DELETE FROM attachments")
    suspend fun wipeAttachments()
}

@Database(entities = [PartyEntity::class, EntryEntity::class, AttachmentEntity::class], version = 3, exportSchema = false)
abstract class DaftariDb : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        /** 1.1.0: per-account currency + per-operation original currency and rate. Existing rows keep their amounts. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parties ADD COLUMN currency TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN currency TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN orig_amount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN rate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN method TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN transfer_no TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN transfer_co TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 1.1.1: e-wallet payments (sender phone) + receipt photos tied to the payment itself. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN sender_phone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE attachments ADD COLUMN entry_id INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context, inMemory: Boolean = false): DaftariDb =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, DaftariDb::class.java).allowMainThreadQueries()
            else Room.databaseBuilder(context, DaftariDb::class.java, "daftari.db")).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
