package com.daftari.app.core

import com.daftari.app.domain.Entry

/** What the payment sheet hands over: method, transfer company / wallet, transfer / transaction number, sender phone. */
data class PayInfo(val method: String, val company: String = "", val ref: String = "", val phone: String = "")

/**
 * 1.1.1 — how a payment came in: نقداً / حوالة (الكريمي، النجم، الحزمي، أخرى) / محفظة (ون كاش، جيب، جوالي، فلوسك، حاسب، أخرى) / غيره.
 * The company or wallet name lives in [Entry.transferCo], the transfer / transaction number in [Entry.transferNo].
 */
object PayMethods {
    const val CASH = "cash"
    const val TRANSFER = "transfer"
    const val WALLET = "wallet"
    const val OTHER = "other"

    /** The only transfer companies offered as chips; anything else is typed under «أخرى». */
    val COMPANIES = listOf("الكريمي", "النجم", "الحزمي")

    data class Wallet(val key: String, val label: String, val latin: String, val aliases: List<String>)

    val WALLETS = listOf(
        Wallet("onecash", "ون كاش", "ONE Cash", listOf("ون كاش", "وان كاش", "ونكاش", "ون كش", "one cash", "onecash", "1cash")),
        Wallet("jaib", "جيب", "Jaib", listOf("جيب", "محفظة جيب", "jaib", "jeeb")),
        Wallet("jawali", "جوالي", "Jawali", listOf("جوالي", "محفظة جوالي", "jawali")),
        Wallet("floosak", "فلوسك", "Floosak", listOf("فلوسك", "فلوسكم", "floosak", "flousak")),
        Wallet("hasib", "حاسب", "Haseb", listOf("حاسب", "محفظة حاسب", "haseb", "hasib")),
    )

    fun methodLabel(m: String): String = when (m) {
        CASH -> "نقداً"; TRANSFER -> "حوالة"; WALLET -> "محفظة"; OTHER -> "غير ذلك"; else -> m
    }

    /** The wallet a free-typed name refers to (any spelling we know), or null. */
    fun wallet(name: String): Wallet? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return WALLETS.firstOrNull { w -> w.aliases.any { norm(it) == n } || norm(w.label) == n }
    }

    /** «الكريمي» for «كريمي» / «بالكريمي» …, the typed name otherwise. */
    fun company(name: String): String {
        val n = norm(name).removePrefix("ب").let { if (it.startsWith("ال")) it else "ال$it" }
        return COMPANIES.firstOrNull { norm(it) == n } ?: name.trim()
    }

    /** «حوالة الكريمي رقم 123456» · «محفظة جوالي رقم 778899 من 777123456». */
    fun describe(e: Entry): String = when (e.method) {
        TRANSFER -> listOf("حوالة", e.transferCo, if (e.transferNo.isNotBlank()) "رقم \u2066${e.transferNo}\u2069" else "").filter { it.isNotBlank() }.joinToString(" ")
        WALLET -> listOf("محفظة", e.transferCo, if (e.transferNo.isNotBlank()) "رقم \u2066${e.transferNo}\u2069" else "",
            if (e.senderPhone.isNotBlank()) "من \u2066${e.senderPhone}\u2069" else "").filter { it.isNotBlank() }.joinToString(" ")
        CASH -> "نقداً"; OTHER -> "غير ذلك"; else -> ""
    }

    /** A payment's statement text: its note (unless it only repeats the method) + «حوالة الكريمي رقم …» / «محفظة جوالي رقم …». */
    fun statementText(e: Entry): String {
        val how = if (e.method == TRANSFER || e.method == WALLET) describePlain(e) else ""
        val note = e.note.trim().takeUnless { how.isNotEmpty() && (it == "حوالة" || it.startsWith("محفظة") || it == "عن طريق مدين") }.orEmpty()
        return listOf(note, how).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Imported rows from another app: «سداد حوالة الكريمي رقم 4455» / «عبر جوالي 778899» in the note → method, company / wallet, number. */
    fun fromNote(e: Entry): Entry {
        if (e.type != com.daftari.app.domain.EntryType.PAYMENT || e.method.isNotBlank() || e.note.isBlank()) return e
        val hit = com.daftari.app.chat.Nlu.payMethod(com.daftari.app.chat.ArabicText.words(e.note))
        if (hit.method.isEmpty()) return e
        return e.copy(method = hit.method, transferCo = hit.company, transferNo = hit.ref, senderPhone = hit.phone)
    }

    /** The compact line under a payment row: «جوالي · رقم 778899» / «حوالة الحزمي · رقم 123456». */
    fun short(e: Entry): String = when (e.method) {
        WALLET -> listOf(e.transferCo.ifBlank { "محفظة" }, if (e.transferNo.isNotBlank()) "رقم \u2066${e.transferNo}\u2069" else "").filter { it.isNotBlank() }.joinToString(" · ")
        TRANSFER -> listOf(("حوالة " + e.transferCo).trim(), if (e.transferNo.isNotBlank()) "رقم \u2066${e.transferNo}\u2069" else "").filter { it.isNotBlank() }.joinToString(" · ")
        else -> ""
    }

    /** A payment's own note when it says more than the method does. */
    fun ownNote(e: Entry): String = e.note.trim().takeUnless { (e.method == TRANSFER || e.method == WALLET) && (it == "حوالة" || it.startsWith("محفظة") || it == "عن طريق مدين") }.orEmpty()

    /** Same text without the bidi isolates — for Excel cells and plain logs. */
    fun describePlain(e: Entry): String = describe(e).replace("\u2066", "").replace("\u2069", "")

    /**
     * Rows read from 1.1.0 files / logs: a number without a method was a transfer; a wallet name typed into the
     * company field of a «حوالة» becomes an «محفظة» payment; known spellings are made canonical.
     */
    fun migrate(e: Entry): Entry {
        var x = e
        if (x.method.isBlank() && x.transferNo.isNotBlank()) x = x.copy(method = TRANSFER)
        if (x.method == TRANSFER || x.method == WALLET) {
            val w = wallet(x.transferCo)
            x = when {
                w != null -> x.copy(method = WALLET, transferCo = w.label)
                x.method == TRANSFER && x.transferCo.isNotBlank() -> x.copy(transferCo = company(x.transferCo))
                else -> x
            }
        }
        return x
    }

    private fun norm(s: String) = s.trim().lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "").replace(Regex("\\s+"), " ")
}
