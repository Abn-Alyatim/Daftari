/*
 * دفتري · chat/ChatMemory.kt
 * ذاكرة المحادثة القصيرة لـ«مدين»: آخر زبون ذُكر (لـ«سجل عليه» و«طلع لي كشف» دون اسم)، وهل ينتظر المساعد مبلغاً لزبون معيّن.
 * 1.1.8. Pure state (no Android): the view model keeps one per chat, notes every customer a message was about, and hands
 * [last] / [pending] to the offline parser. Cleared with the chat. Unit-tested (Features118Test).
 */
package com.daftari.app.chat

class ChatMemory {
    /** «كم المبلغ؟» was asked for [customerId]; a bare amount next is that debt ([payment] false) or payment. */
    data class Pending(val customerId: Long, val payment: Boolean)

    var lastId: Long = 0L
        private set
    var pending: Pending? = null
        private set

    /** The chat is now about this customer (0 = nobody: kept as it was). */
    fun note(id: Long) { if (id > 0) lastId = id }

    /** Notes the customer a parsed command names. */
    fun note(c: Command) { Nlu.mentioned(c)?.let { note(it.id) } }

    fun ask(id: Long, payment: Boolean) { if (id > 0) { pending = Pending(id, payment); lastId = id } }
    fun answered() { pending = null }

    /** The last customer, if still in the book. */
    fun last(customers: List<Customer>): Customer? = customers.firstOrNull { it.id == lastId }

    fun clear() { lastId = 0L; pending = null }
}
