package com.daftari.app.core

import com.daftari.app.data.ApiException
import com.daftari.app.data.AppSettings
import com.daftari.app.data.SubPlan
import com.daftari.app.data.SubRequestDto
import java.time.Instant
import java.time.ZoneId

/**
 * 1.1.5 — subscriptions paid by transfer / wallet and approved by the developer in Madyan Panel.
 * Plans, payment methods, the number and the account name come from the server (editable in the panel, no app update).
 */
object Subs {
    const val TRANSFER = "transfer"
    const val WALLET = "wallet"
    private const val DAY = 24 * 3600_000L

    /** «الاسم الرباعي»: four names or more, each at least two letters (the server checks the same). */
    fun isFourPartName(s: String): Boolean {
        val ws = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return ws.size >= 4 && ws.all { w -> w.count { it.isLetter() } >= 2 }
    }

    /** Arabic-Indic digits → Latin, spaces removed. */
    fun cleanTxn(s: String): String = s.map { c -> if (c in '\u0660'..'\u0669') ('0' + (c - '\u0660')) else c }.joinToString("").filter { !it.isWhitespace() }.take(40)

    fun isTxnValid(s: String): Boolean = Regex("^[0-9A-Za-z\\-/]{3,40}$").matches(cleanTxn(s))

    fun currencyOf(code: String): Currency = Currency.of(code)

    fun priceText(price: Double, currency: String): String = Money.format(Money.ofWhole(Math.round(price)), currencyOf(currency))
    fun priceText(p: SubPlan): String = priceText(p.price, p.currency)

    /** «شهر واحد» / «سنة كاملة» / «90 يوماً». */
    fun durationText(days: Int): String = when (days) {
        30, 31 -> "شهر واحد"
        60 -> "شهران"
        90 -> "ثلاثة أشهر"
        180 -> "ستة أشهر"
        365, 366 -> "سنة كاملة"
        1 -> "يوم واحد"
        2 -> "يومان"
        in 3..10 -> "$days أيام"
        else -> "$days يوماً"
    }

    fun dateText(ms: Long): String = if (ms <= 0) "—" else Dates.full(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate())

    /** The badge next to the account name: the approved plan's badge while it runs, else null. */
    fun badge(s: AppSettings, now: Long = System.currentTimeMillis()): String? =
        if (s.proUntil > now && s.planName.isNotBlank()) s.planBadge.ifBlank { "برو" } else null

    /** The plan ran out since we last looked: its name (for «انتهى اشتراكك»), else null. */
    fun expired(s: AppSettings, now: Long = System.currentTimeMillis()): String? =
        if (s.planName.isNotBlank() && s.proUntil in 1..now) s.planName else null

    fun daysLeft(endsAt: Long, now: Long = System.currentTimeMillis()): Int = if (endsAt <= now) 0 else ((endsAt - now + DAY - 1) / DAY).toInt()

    /** The lines of «تم الاشتراك». */
    fun approvedLines(r: SubRequestDto): List<Pair<String, String>> = listOf(
        "الخطة" to r.planName,
        "المدة" to durationText(r.days),
        "باسم" to r.payerName,
        "المبلغ" to priceText(r.price, r.currency),
        "طريقة الدفع" to listOf(if (r.methodKind == WALLET) "محفظة" else "حوالة", r.methodLabel).filter { it.isNotBlank() }.joinToString(" "),
        "رقم العملية" to r.txnNo,
        "يبدأ" to dateText(r.startsAt),
        "ينتهي" to dateText(r.endsAt),
    ).filter { it.second.isNotBlank() }

    /** What the subscription screens say when the server cannot be used (not deployed yet, offline, maintenance). */
    fun errorText(e: Throwable): String = when {
        e !is ApiException -> "تعذّر الاتصال بخدمة الاشتراكات. حاول مرة أخرى."
        e.code == "no_server" || e.status == 404 || e.code == "http_404" -> "خدمة الاشتراكات قيد التجهيز، وستعمل قريباً دون الحاجة إلى تحديث التطبيق. بياناتك محفوظة على جوالك."
        e.code == "offline" -> "لا يوجد اتصال بالإنترنت أو أن خادم الاشتراكات لا يستجيب. تحقق من الاتصال ثم أعد المحاولة."
        e.code == "maintenance" -> e.message.ifBlank { "خدمة الاشتراكات متوقفة مؤقتاً للصيانة." }
        e.status in 500..599 -> "خادم الاشتراكات يواجه مشكلة مؤقتة. حاول بعد قليل."
        else -> e.message
    }
}
