package com.daftari.app.data

import com.daftari.app.core.PayMethods
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Period
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One JSON shape for a customer / an operation: backups, the permanent log's before/after snapshots, restore. */
object Snap {
    fun party(p: Party): JSONObject = JSONObject().apply {
        put("id", p.id); put("kind", p.kind.name); put("shopNumber", p.shopNumber); put("name", p.name); put("phone", p.phone)
        put("activity", p.activity); put("periodicAmount", p.periodicAmount); put("period", p.period.name); put("startDay", p.startDate.toEpochDay())
        put("notes", p.notes); put("location", p.location); put("openingBalance", p.openingBalance); put("unitsCount", p.unitsCount)
        put("creditLimit", p.creditLimit); put("category", p.category); put("createdAt", p.createdAt); put("updatedAt", p.updatedAt); put("currency", p.currency)
    }

    fun partyFrom(p: JSONObject): Party = Party(
        id = p.optLong("id"), kind = runCatching { PartyKind.valueOf(p.getString("kind")) }.getOrDefault(PartyKind.CUSTOMER), shopNumber = p.optString("shopNumber"),
        name = p.getString("name"), phone = p.optString("phone"), activity = p.optString("activity"), periodicAmount = p.optLong("periodicAmount"),
        period = runCatching { Period.valueOf(p.optString("period")) }.getOrDefault(Period.NONE),
        startDate = LocalDate.ofEpochDay(p.optLong("startDay", LocalDate.now().toEpochDay())), notes = p.optString("notes"), location = p.optString("location"),
        openingBalance = p.optLong("openingBalance"), unitsCount = p.optInt("unitsCount", 1), creditLimit = p.optLong("creditLimit"),
        category = p.optString("category"), createdAt = p.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = p.optLong("updatedAt", p.optLong("createdAt", System.currentTimeMillis())), currency = p.optString("currency"),
    )

    fun entry(e: Entry): JSONObject = JSONObject().apply {
        put("id", e.id); put("partyId", e.partyId); put("type", e.type.name); put("amount", e.amount); put("day", e.date.toEpochDay()); put("note", e.note)
        put("collector", e.collector); put("receiptNo", e.receiptNo); put("items", e.items); e.dueDate?.let { put("dueDay", it.toEpochDay()) }; put("createdAt", e.createdAt)
        if (e.foreign) { put("currency", e.currency); put("origAmount", e.origAmount); put("rate", e.rate) }
        if (e.method.isNotBlank()) { put("method", e.method); put("transferNo", e.transferNo); put("transferCo", e.transferCo); if (e.senderPhone.isNotBlank()) put("senderPhone", e.senderPhone) }
    }

    fun entryFrom(e: JSONObject): Entry = Entry(
        id = e.optLong("id"), partyId = e.getLong("partyId"), type = EntryType.valueOf(e.getString("type")), amount = e.getLong("amount"),
        date = LocalDate.ofEpochDay(e.getLong("day")), note = e.optString("note"), collector = e.optString("collector"), receiptNo = e.optString("receiptNo"),
        items = e.optString("items"), dueDate = if (e.has("dueDay")) LocalDate.ofEpochDay(e.getLong("dueDay")) else null,
        createdAt = e.optLong("createdAt", System.currentTimeMillis()),
        currency = e.optString("currency"), origAmount = e.optLong("origAmount"), rate = e.optString("rate"),
        method = e.optString("method"), transferNo = e.optString("transferNo"), transferCo = e.optString("transferCo"), senderPhone = e.optString("senderPhone"),
    ).let(PayMethods::migrate)

    fun all(parties: List<Party>, entries: List<Entry>): JSONObject = JSONObject().apply {
        put("parties", JSONArray().apply { parties.forEach { put(party(it)) } })
        put("entries", JSONArray().apply { entries.forEach { put(entry(it)) } })
    }

    fun partyWithEntries(p: Party, entries: List<Entry>): JSONObject = JSONObject().apply {
        put("party", party(p)); put("entries", JSONArray().apply { entries.forEach { put(entry(it)) } })
    }

    fun parties(o: JSONObject): List<Party> = o.optJSONArray("parties")?.let { a -> (0 until a.length()).map { partyFrom(a.getJSONObject(it)) } }.orEmpty()
    fun entries(o: JSONObject): List<Entry> = o.optJSONArray("entries")?.let { a -> (0 until a.length()).map { entryFrom(a.getJSONObject(it)) } }.orEmpty()
}
