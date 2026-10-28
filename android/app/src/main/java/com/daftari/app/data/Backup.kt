package com.daftari.app.data

import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Period
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** The whole ledger as one JSON file: daily local backup, «تصدير قاعدة البيانات», Google Drive / WhatsApp copy, restore. */
object Backup {
    const val VERSION = 1

    fun toJson(parties: List<Party>, entries: List<Entry>, business: String, owner: String, role: String): String {
        val ps = JSONArray()
        parties.forEach { p ->
            ps.put(JSONObject().apply {
                put("id", p.id); put("kind", p.kind.name); put("shopNumber", p.shopNumber); put("name", p.name); put("phone", p.phone)
                put("activity", p.activity); put("periodicAmount", p.periodicAmount); put("period", p.period.name); put("startDay", p.startDate.toEpochDay())
                put("notes", p.notes); put("location", p.location); put("openingBalance", p.openingBalance); put("unitsCount", p.unitsCount)
                put("creditLimit", p.creditLimit); put("category", p.category); put("createdAt", p.createdAt)
            })
        }
        val es = JSONArray()
        entries.forEach { e ->
            es.put(JSONObject().apply {
                put("partyId", e.partyId); put("type", e.type.name); put("amount", e.amount); put("day", e.date.toEpochDay()); put("note", e.note)
                put("collector", e.collector); put("receiptNo", e.receiptNo); put("items", e.items); e.dueDate?.let { put("dueDay", it.toEpochDay()) }; put("createdAt", e.createdAt)
            })
        }
        return JSONObject().apply {
            put("app", "daftari"); put("version", VERSION); put("exportedAt", System.currentTimeMillis())
            put("business", business); put("owner", owner); put("role", role); put("parties", ps); put("entries", es)
        }.toString()
    }

    data class Snapshot(val parties: List<Party>, val entries: List<Entry>, val business: String, val owner: String, val role: String)

    /** Parses a backup. Party ids are kept so entries can be re-linked (the caller remaps them on insert). */
    fun parse(json: String): Snapshot {
        val o = JSONObject(json)
        require(o.optString("app") == "daftari") { "هذا الملف ليس نسخة احتياطية من دفتري" }
        val ps = o.getJSONArray("parties")
        val parties = (0 until ps.length()).map { i ->
            val p = ps.getJSONObject(i)
            Party(
                id = p.getLong("id"), kind = PartyKind.valueOf(p.getString("kind")), shopNumber = p.optString("shopNumber"), name = p.getString("name"),
                phone = p.optString("phone"), activity = p.optString("activity"), periodicAmount = p.optLong("periodicAmount"),
                period = runCatching { Period.valueOf(p.optString("period")) }.getOrDefault(Period.NONE),
                startDate = LocalDate.ofEpochDay(p.optLong("startDay", LocalDate.now().toEpochDay())), notes = p.optString("notes"), location = p.optString("location"),
                openingBalance = p.optLong("openingBalance"), unitsCount = p.optInt("unitsCount", 1), creditLimit = p.optLong("creditLimit"),
                category = p.optString("category"), createdAt = p.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        val es = o.getJSONArray("entries")
        val entries = (0 until es.length()).map { i ->
            val e = es.getJSONObject(i)
            Entry(
                partyId = e.getLong("partyId"), type = EntryType.valueOf(e.getString("type")), amount = e.getLong("amount"), date = LocalDate.ofEpochDay(e.getLong("day")),
                note = e.optString("note"), collector = e.optString("collector"), receiptNo = e.optString("receiptNo"), items = e.optString("items"),
                dueDate = if (e.has("dueDay")) LocalDate.ofEpochDay(e.getLong("dueDay")) else null, createdAt = e.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        return Snapshot(parties, entries, o.optString("business"), o.optString("owner"), o.optString("role"))
    }
}
