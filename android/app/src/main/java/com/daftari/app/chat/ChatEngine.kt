package com.daftari.app.chat

import com.daftari.app.core.AppInfo
import com.daftari.app.core.Currency
import com.daftari.app.core.Dates
import com.daftari.app.core.Money
import com.daftari.app.data.ChatAction
import com.daftari.app.data.ChatTable
import com.daftari.app.data.Dashboard
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Role
import com.daftari.app.domain.Status
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val table: ChatTable? = null,
    val action: ChatAction? = null,
    /** null = not an action; true done; false cancelled */
    val actionDone: Boolean? = null,
    /** «أي محمد تقصد؟»: one tap picks a customer and turns into a confirmation card */
    val choices: List<ChatAction> = emptyList(),
    val id: Long = System.nanoTime(),
)

/**
 * The data «مدين» is allowed to see: only this user's own ledger, compact. Sent to the server with each question;
 * the server's prompt forbids anything outside it.
 */
object ChatContext {
    fun build(d: Dashboard, role: Role, business: String, owner: String, currency: Currency, today: LocalDate): JsonObject = buildJsonObject {
        put("app", AppInfo.NAME)
        put("assistant", AppInfo.ASSISTANT)
        put("role", role.name)
        put("business", business)
        put("owner", owner)
        put("currency", currency.code)
        put("today", today.toString())
        put("totalOwed", d.totalOwed / 100.0)
        put("collectedToday", d.collectedToday / 100.0)
        put("collectedThisMonth", d.collectedMonth / 100.0)
        put("lateCount", d.lateCount)
        put("count", d.summaries.size)
        put("parties", buildJsonArray {
            d.summaries.sortedByDescending { it.balance }.take(400).forEach { s ->
                add(buildJsonObject {
                    put("ref", s.party.shopNumber.ifBlank { s.party.name })
                    put("shop", s.party.shopNumber)
                    put("name", s.party.name)
                    put("phone", s.party.phone)
                    put("sells", s.party.activity)
                    put("periodic", s.party.periodicAmount / 100.0)
                    put("period", s.party.period.name)
                    put("balance", s.balance / 100.0)
                    put("paidTotal", s.totalPaid / 100.0)
                    put("status", s.status.labelAr)
                    put("daysLate", s.daysLate)
                    put("lastPayment", s.lastPayment?.toString() ?: "")
                    put("paidToday", s.paidToday / 100.0)
                    if (s.party.creditLimit > 0) put("creditLimit", s.party.creditLimit / 100.0)
                })
            }
        })
    }
}

/** Offline answers for the common questions, so «مدين» still helps with bad internet. Null = needs the AI. */
object LocalAnswers {
    fun answer(q: String, d: Dashboard, role: Role, currency: Currency, today: LocalDate): ChatMessage? {
        val t = q.replace("أ", "ا").replace("إ", "ا").replace("ة", "ه")
        val noun = if (role == Role.LANDLORD) "مستأجر" else "زبون"
        fun m(v: Long) = Money.format(v, currency)
        val owing = d.summaries.filter { it.balance > 0 }
        return when {
            listOf("اجمالي", "كم المتبقي", "كم لي", "مجموع", "كم عليهم", "كم الديون").any { t.contains(it) } ->
                ChatMessage(false, "إجمالي المتبقي على الكل: ${m(d.totalOwed)}\nعدد من عليهم ديون: ${owing.size} $noun، منهم ${d.lateCount} متأخر.")
            (t.contains("ما دفع") || t.contains("مادفع") || t.contains("لم يدفع")) && role == Role.LANDLORD -> {
                val list = d.summaries.filter { it.paidToday == 0L && Ledger.accrued(it.party, today, today) > 0 }
                ChatMessage(false, if (list.isEmpty()) "الجميع دفعوا اليوم 👏" else "${list.size} لم يدفعوا اليوم:",
                    if (list.isEmpty()) null else ChatTable("لم يدفعوا اليوم", listOf("المحل", "المستأجر", "المتبقي"),
                        list.take(30).map { listOf(it.party.shopNumber, it.party.name, Money.plain(it.balance)) }))
            }
            t.contains("متاخر") || t.contains("اكثر") || t.contains("اعلى") -> {
                val top = owing.sortedByDescending { it.balance }.take(10)
                ChatMessage(false, if (top.isEmpty()) "لا يوجد أحد عليه دين 👏" else "الأكثر عليهم:",
                    if (top.isEmpty()) null else ChatTable("الأكثر عليهم", listOf(if (role == Role.LANDLORD) "المحل" else "م", "الاسم", "المتبقي", "الحالة"),
                        top.mapIndexed { i, s -> listOf(s.party.shopNumber.ifBlank { (i + 1).toString() }, s.party.name, Money.plain(s.balance), s.status.labelAr) }))
            }
            (t.contains("اليوم") && (t.contains("تحصيل") || t.contains("حصل") || t.contains("دفع") || t.contains("جمع"))) ->
                ChatMessage(false, "تحصيل اليوم ${Dates.short(today)}: ${m(d.collectedToday)} من ${d.paidTodayCount} $noun." +
                    (if (d.newDebtToday > 0) "\nديون جديدة اليوم: ${m(d.newDebtToday)}" else ""))
            t.contains("الشهر") -> ChatMessage(false, "المحصّل هذا الشهر: ${m(d.collectedMonth)}")
            t.contains("جدول") || t.contains("كشف") -> ChatMessage(false, "هذا جدول بجميع الأرصدة:",
                ChatTable("الأرصدة", listOf(if (role == Role.LANDLORD) "المحل" else "م", "الاسم", "المتبقي", "الحالة"),
                    d.summaries.sortedByDescending { it.balance }.mapIndexed { i, s -> listOf(s.party.shopNumber.ifBlank { (i + 1).toString() }, s.party.name, Money.plain(s.balance), s.status.labelAr) }))
            t.contains("مسدد") || t.contains("سدد") && t.contains("مين") -> {
                val paid = d.summaries.filter { it.status == Status.PAID }
                ChatMessage(false, "${paid.size} مسددين بالكامل.")
            }
            else -> null
        }
    }

    const val NOT_READY = "الخدمة قيد التجهيز ⏳\nيمكنني الآن أن أجيب دون إنترنت عن: الإجمالي، والمتأخرين، ومن لم يدفع اليوم، وتحصيل اليوم، وجدول الأرصدة."
}
