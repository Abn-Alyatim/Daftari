package com.daftari.app.backup

import com.daftari.app.audit.AuditLog
import com.daftari.app.audit.AuditRecord
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Period
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A reminder set from «مدين» (kept in backups so a new phone gets them back). */
data class Reminder(val id: Long, val partyId: Long, val at: Long, val text: String, val done: Boolean = false)

/** Everything one Daftari book holds. Amounts are minor units (×100) in memory, decimal numbers in the file. */
data class BackupData(
    val bookId: String,
    val appVersion: String,
    val createdAt: Long,
    val currency: String,
    val business: String,
    val owner: String,
    val role: String,
    val parties: List<Party>,
    val entries: List<Entry>,
    val reminders: List<Reminder> = emptyList(),
    val log: List<AuditRecord> = emptyList(),
    /** 1 when it came from a 1.0.x JSON backup (migrated). */
    val migratedFrom: Int = 0,
    /** 2.2 (1.1.2): the exchange-rate table (each city's شراء and بيع); blank in older files. */
    val ratesJson: String = "",
)

data class Manifest(
    val schemaVersion: Int, val appVersion: String, val createdAt: Long, val encrypted: Boolean,
    val customers: Int, val entries: Int, val debts: Int, val payments: Int, val reminders: Int, val log: Int,
    val json: JSONObject,
)

sealed interface ReadResult {
    data class Ok(val data: BackupData, val manifest: Manifest?, val warnings: List<String>) : ReadResult
    data class NeedsPassword(val manifest: Manifest) : ReadResult
    data class Bad(val messageAr: String) : ReadResult
}

/** The derived key for a «كلمة سر النسخ» (kept on the phone for the daily automatic backup; the password itself is never stored). */
class BackupKey(val key: ByteArray, val salt: ByteArray, val iterations: Int = DaftariFile.ITERATIONS)

/**
 * The one canonical backup format: `Daftari-YYYY-MM-DD-HHmm.daftari` = a ZIP holding `manifest.json` and `data.json`
 * (or `data.json.enc` when protected by a «كلمة سر النسخ»: PBKDF2-SHA256 → AES-256-GCM). The manifest carries the SHA-256
 * of the plain data.json and the record counts, so a partial / corrupted / edited file is refused. Used for Google Drive
 * (both ways), «حفظ نسخة», «استيراد نسخة», the daily automatic copy, and the files «مدين» opens. See docs/backup-format.md.
 */
object DaftariFile {
    const val FORMAT = "daftari-backup"
    const val SCHEMA = 2
    /** 2.1 (1.1.1): «محفظة» payments (method "wallet", transferCompany = wallet name, senderPhone), transfer number optional.
     *  Additive only, so 1.1.0 still opens these files; 2.0 files are migrated on read ([com.daftari.app.core.PayMethods.migrate]).
     *  2.2 (1.1.2): optional "rates" object = the exchange-rate table, each city with شراء (sarBuy / usdBuy, the only rate used in
     *  calculations) and بيع (sarSell / usdSell, for reference). Additive; older files simply have none. */
    const val SCHEMA_MINOR = 2
    const val EXT = "daftari"
    const val MIME = "application/octet-stream"
    const val ITERATIONS = 120_000

    fun fileName(at: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return "Daftari-%04d-%02d-%02d-%02d%02d.daftari".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    // ------------------------------------------------------------------ crypto

    fun deriveKey(password: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, iterations: Int = ITERATIONS): BackupKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return BackupKey(key, salt, iterations)
    }

    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }.doFinal(data)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    // ------------------------------------------------------------------ write

    private fun money(minor: Long): BigDecimal = BigDecimal.valueOf(minor, 2).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    private fun minor(v: Any?): Long = BigDecimal(v.toString()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
    private fun day(d: LocalDate) = d.toString()

    fun dataJson(d: BackupData): JSONObject = JSONObject().apply {
        val accountCur = d.parties.associate { it.id to it.currency.ifBlank { d.currency } }
        put("format", FORMAT); put("schemaVersion", SCHEMA); put("schemaMinor", SCHEMA_MINOR); put("appVersion", d.appVersion)
        put("createdAt", Instant.ofEpochMilli(d.createdAt).toString()); put("createdAtMillis", d.createdAt)
        put("bookId", d.bookId); put("currency", d.currency); put("business", d.business); put("owner", d.owner); put("role", d.role)
        put("customers", JSONArray().apply {
            d.parties.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("phone", p.phone); put("kind", p.kind.name); put("shopNumber", p.shopNumber)
                    put("activity", p.activity); put("category", p.category); put("notes", p.notes); put("location", p.location)
                    put("openingBalance", money(p.openingBalance)); put("creditLimit", money(p.creditLimit)); put("periodicAmount", money(p.periodicAmount))
                    put("period", p.period.name); put("startDate", day(p.startDate)); put("unitsCount", p.unitsCount)
                    put("createdAt", p.createdAt); put("updatedAt", p.updatedAt); put("currency", p.currency.ifBlank { d.currency })
                })
            }
        })
        put("entries", JSONArray().apply {
            d.entries.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("customerId", e.partyId); put("type", if (e.type == EntryType.PAYMENT) "payment" else "debt")
                    put("amount", money(e.amount)); put("currency", accountCur[e.partyId] ?: d.currency); put("items", e.items)
                    if (e.foreign) { put("originalCurrency", e.currency); put("originalAmount", money(e.origAmount)); put("rate", e.rate) }
                    if (e.method.isNotBlank()) put("method", e.method)
                    if (e.transferNo.isNotBlank()) put("transferNo", e.transferNo)
                    if (e.transferCo.isNotBlank()) put("transferCompany", e.transferCo)
                    if (e.senderPhone.isNotBlank()) put("senderPhone", e.senderPhone)
                    put("date", day(e.date))
                    e.dueDate?.let { put("dueDate", day(it)) }; put("note", e.note); put("collector", e.collector); put("receiptNo", e.receiptNo)
                    put("createdAt", e.createdAt)
                })
            }
        })
        put("reminders", JSONArray().apply {
            d.reminders.forEach { r -> put(JSONObject().apply { put("id", r.id); put("customerId", r.partyId); put("at", r.at); put("text", r.text); put("done", r.done) }) }
        })
        put("log", AuditLog.toJsonArray(d.log))
        if (d.ratesJson.isNotBlank()) runCatching { JSONObject(d.ratesJson) }.getOrNull()?.let { put("rates", it) }
    }

    fun write(d: BackupData, key: BackupKey? = null): ByteArray {
        val plain = dataJson(d).toString(1).toByteArray(Charsets.UTF_8)
        val debts = d.entries.count { it.type != EntryType.PAYMENT }
        val manifest = JSONObject().apply {
            put("format", FORMAT); put("schemaVersion", SCHEMA); put("schemaMinor", SCHEMA_MINOR); put("appVersion", d.appVersion)
            put("createdAt", Instant.ofEpochMilli(d.createdAt).toString()); put("createdAtMillis", d.createdAt)
            put("file", if (key != null) "data.json.enc" else "data.json"); put("sha256", sha256(plain)); put("size", plain.size)
            put("counts", JSONObject().apply {
                put("customers", d.parties.size); put("entries", d.entries.size); put("debts", debts); put("payments", d.entries.size - debts)
                put("reminders", d.reminders.size); put("log", d.log.size)
            })
            d.log.lastOrNull()?.let { put("logHead", JSONObject().apply { put("seq", it.seq); put("hash", it.hash) }) }
            put("encrypted", key != null)
        }
        val payload: ByteArray = if (key == null) plain else {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            manifest.put("encryption", JSONObject().apply {
                put("cipher", "AES-256-GCM"); put("kdf", "PBKDF2WithHmacSHA256"); put("iterations", key.iterations); put("salt", b64(key.salt)); put("iv", b64(iv))
            })
            aes(Cipher.ENCRYPT_MODE, key.key, iv, plain)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toString(1).toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry(manifest.getString("file"))); z.write(payload); z.closeEntry()
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ read

    fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()

    private fun manifestOf(m: JSONObject): Manifest {
        val c = m.optJSONObject("counts") ?: JSONObject()
        return Manifest(m.optInt("schemaVersion"), m.optString("appVersion"), m.optLong("createdAtMillis"), m.optBoolean("encrypted"),
            c.optInt("customers"), c.optInt("entries"), c.optInt("debts"), c.optInt("payments"), c.optInt("reminders"), c.optInt("log"), m)
    }

    /** Reads, verifies and (for old versions) migrates a backup. [password] or [knownKey] open an encrypted one. */
    fun read(bytes: ByteArray, password: String? = null, knownKey: BackupKey? = null): ReadResult {
        if (bytes.isEmpty()) return ReadResult.Bad("الملف فارغ")
        if (!isZip(bytes)) return readLegacy(bytes)
        val files = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry
                while (e != null) { if (!e.isDirectory) files[e.name] = z.readBytes(); e = z.nextEntry }
            }
        } catch (e: Exception) { return ReadResult.Bad("الملف ناقص أو تالف (لم يُفتح). نزّله مرة أخرى كاملاً.") }
        val mBytes = files["manifest.json"] ?: return ReadResult.Bad("هذا ليس ملف نسخة دفتري (لا يحتوي على manifest)")
        val m = try { JSONObject(String(mBytes, Charsets.UTF_8)) } catch (e: Exception) { return ReadResult.Bad("بيانات النسخة تالفة") }
        if (m.optString("format") != FORMAT) return ReadResult.Bad("هذا ليس ملف نسخة دفتري")
        val manifest = manifestOf(m)
        if (manifest.schemaVersion > SCHEMA) return ReadResult.Bad("النسخة من إصدار أحدث من دفتري (${manifest.appVersion}). حدّث التطبيق ثم استرجعها.")
        val payload = files[m.optString("file", "data.json")] ?: return ReadResult.Bad("الملف ناقص: البيانات مفقودة")
        val plain: ByteArray = if (manifest.encrypted) {
            val enc = m.optJSONObject("encryption") ?: return ReadResult.Bad("بيانات التشفير ناقصة")
            val salt = unb64(enc.getString("salt")); val iv = unb64(enc.getString("iv")); val iter = enc.optInt("iterations", ITERATIONS)
            val key = when {
                knownKey != null && knownKey.salt.contentEquals(salt) && knownKey.iterations == iter -> knownKey.key
                password != null -> deriveKey(password, salt, iter).key
                else -> return ReadResult.NeedsPassword(manifest)
            }
            try { aes(Cipher.DECRYPT_MODE, key, iv, payload) } catch (e: Exception) {
                return if (password == null) ReadResult.NeedsPassword(manifest) else ReadResult.Bad("كلمة سر النسخ غير صحيحة، أو الملف تالف")
            }
        } else payload
        if (sha256(plain) != m.optString("sha256")) return ReadResult.Bad("الملف ناقص أو تالف: البصمة غير مطابقة. لم يُسترجع شيء.")
        val o = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (e: Exception) { return ReadResult.Bad("بيانات النسخة تالفة") }
        return parseData(o, manifest)
    }

    fun parseData(o: JSONObject, manifest: Manifest?): ReadResult {
        if (o.optString("format") != FORMAT) return ReadResult.Bad("هذا ليس ملف نسخة دفتري")
        val v = o.optInt("schemaVersion", 0)
        if (v > SCHEMA) return ReadResult.Bad("النسخة من إصدار أحدث من دفتري. حدّث التطبيق ثم استرجعها.")
        val warnings = ArrayList<String>()
        val data = try {
            val cs = o.getJSONArray("customers")
            val parties = (0 until cs.length()).map { i ->
                val p = cs.getJSONObject(i)
                Party(
                    id = p.getLong("id"), kind = runCatching { PartyKind.valueOf(p.optString("kind")) }.getOrDefault(PartyKind.CUSTOMER),
                    shopNumber = p.optString("shopNumber"), name = p.getString("name"), phone = p.optString("phone"), activity = p.optString("activity"),
                    periodicAmount = minor(p.opt("periodicAmount") ?: 0), period = runCatching { Period.valueOf(p.optString("period")) }.getOrDefault(Period.NONE),
                    startDate = runCatching { LocalDate.parse(p.optString("startDate")) }.getOrDefault(LocalDate.now()), notes = p.optString("notes"),
                    location = p.optString("location"), openingBalance = minor(p.opt("openingBalance") ?: 0), unitsCount = p.optInt("unitsCount", 1),
                    creditLimit = minor(p.opt("creditLimit") ?: 0), category = p.optString("category"),
                    createdAt = p.optLong("createdAt", 0), updatedAt = p.optLong("updatedAt", p.optLong("createdAt", 0)), currency = p.optString("currency"),
                )
            }
            val es = o.getJSONArray("entries")
            val entries = (0 until es.length()).map { i ->
                val e = es.getJSONObject(i)
                val type = when (e.getString("type").lowercase()) { "payment", "سداد", "PAYMENT".lowercase() -> EntryType.PAYMENT; else -> EntryType.CHARGE }
                Entry(
                    id = e.optLong("id"), partyId = e.getLong("customerId"), type = type, amount = minor(e.get("amount")), date = LocalDate.parse(e.getString("date")),
                    note = e.optString("note"), collector = e.optString("collector"), receiptNo = e.optString("receiptNo"), items = e.optString("items"),
                    dueDate = e.optString("dueDate").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }, createdAt = e.optLong("createdAt", 0),
                    currency = e.optString("originalCurrency"), origAmount = e.opt("originalAmount")?.let { minor(it) } ?: 0, rate = e.optString("rate"),
                    method = e.optString("method"), transferNo = e.optString("transferNo"), transferCo = e.optString("transferCompany"),
                    senderPhone = e.optString("senderPhone"),
                ).let(com.daftari.app.core.PayMethods::migrate)
            }
            val rs = o.optJSONArray("reminders") ?: JSONArray()
            val reminders = (0 until rs.length()).map { i -> rs.getJSONObject(i).let { Reminder(it.getLong("id"), it.optLong("customerId"), it.getLong("at"), it.optString("text"), it.optBoolean("done")) } }
            val log = AuditLog.fromJsonArray(o.optJSONArray("log"))
            BackupData(o.optString("bookId"), o.optString("appVersion"), o.optLong("createdAtMillis"), o.optString("currency", "YER"), o.optString("business"),
                o.optString("owner"), o.optString("role"), parties, entries, reminders, log, ratesJson = o.optJSONObject("rates")?.toString().orEmpty())
        } catch (e: Exception) { return ReadResult.Bad("بيانات النسخة ناقصة أو تالفة (${e.javaClass.simpleName})") }
        // integrity: unique ids, every operation belongs to a customer, no negative amounts, counts match the manifest
        val ids = data.parties.map { it.id }.toSet()
        if (ids.size != data.parties.size) return ReadResult.Bad("النسخة تحتوي على زبائن مكررين بالرقم نفسه — الملف تالف")
        val eIds = data.entries.map { it.id }.filter { it != 0L }
        if (eIds.toSet().size != eIds.size) return ReadResult.Bad("النسخة تحتوي على عمليات مكررة — الملف تالف")
        data.entries.firstOrNull { it.partyId !in ids }?.let { return ReadResult.Bad("عملية في النسخة لزبون غير موجود — الملف ناقص") }
        if (data.entries.any { it.amount < 0 }) return ReadResult.Bad("مبالغ سالبة في النسخة — الملف تالف")
        if (manifest != null && (manifest.customers != data.parties.size || manifest.entries != data.entries.size || manifest.log != data.log.size))
            return ReadResult.Bad("عدد السجلات لا يطابق بيانات النسخة — الملف ناقص")
        if (data.log.isNotEmpty() && !AuditLog.verifyChain(data.log.sortedBy { it.seq }, null).ok)
            warnings += "في سجل النسخة تلاعب، لذا ستُسترجع البيانات دون سجلها"
        return ReadResult.Ok(data, manifest, warnings)
    }

    /** 1.0.x: a plain JSON file ({app: "daftari", version: 1, parties, entries}) → schema 2. */
    private fun readLegacy(bytes: ByteArray): ReadResult {
        val o = try { JSONObject(String(bytes, Charsets.UTF_8).trim().removePrefix("\uFEFF")) } catch (e: Exception) {
            return ReadResult.Bad("هذا ليس ملف نسخة دفتري (\u2066.daftari\u2069)")
        }
        if (o.optString("format") == FORMAT) return parseData(o, null)
        if (o.optString("app") != "daftari") return ReadResult.Bad("هذا ليس ملف نسخة دفتري")
        return try {
            val ps = o.getJSONArray("parties")
            val parties = (0 until ps.length()).map { i -> com.daftari.app.data.Snap.partyFrom(ps.getJSONObject(i)) }
            val es = o.getJSONArray("entries")
            var nextId = 1L
            val entries = (0 until es.length()).map { i -> com.daftari.app.data.Snap.entryFrom(es.getJSONObject(i)).let { if (it.id == 0L) it.copy(id = nextId++) else it.also { nextId = maxOf(nextId, it.id + 1) } } }
            if (parties.map { it.id }.toSet().size != parties.size) return ReadResult.Bad("النسخة القديمة تحتوي على زبائن مكررين — الملف تالف")
            val ids = parties.map { it.id }.toSet()
            if (entries.any { it.partyId !in ids }) return ReadResult.Bad("النسخة القديمة ناقصة: عمليات لزبائن غير موجودين")
            ReadResult.Ok(BackupData(o.optString("bookId"), "1.0.x", o.optLong("exportedAt"), o.optString("currency", "YER"), o.optString("business"), o.optString("owner"),
                o.optString("role"), parties, entries, migratedFrom = 1), null, listOf("نسخة من إصدار قديم (1.0) — تم تحويلها"))
        } catch (e: Exception) { ReadResult.Bad("النسخة القديمة تالفة") }
    }
}
