package com.daftari.app.core

import com.daftari.app.data.AppSettings

/**
 * Free vs Pro («دفتري برو»). Free forever: up to [FREE_PARTIES] accounts, PDF export, call/WhatsApp/SMS, app lock.
 * Every new account gets [TRIAL_DAYS] days of full Pro to try everything; after that Pro-only features ask for a subscription.
 * Payment is pending config: an activation code from the server (manual transfer like ALYATIM) now, Google Play Billing later.
 */
object Plan {
    const val TRIAL_DAYS = 7
    const val FREE_PARTIES = 15
    const val FREE_CHAT_PER_DAY = 3

    enum class Feature(val labelAr: String) {
        UNLIMITED("عدد غير محدود من المحلات والزبائن"),
        EXPORT_ALL("Word و Excel والصورة + كشف الكل"),
        AI_CHAT("«مدين» المساعد الذكي بلا حدود"),
        SMART_IMPORT("رفع ملف ذكي (Word / Excel / صورة دفتر)"),
        BACKUP("نسخ احتياطي يومي ومزامنة"),
        REMINDERS("تنبيهات السقف والمواعيد التلقائية"),
        REPORTS("التقارير المتقدمة (شهري، تصنيفات، تفصيلي)"),
    }

    data class Price(val months: Int, val yer: Long, val sar: Long, val usd: Long, val labelAr: String)

    /** Proposed prices (whole units). Madyan can change them here or later from the server. */
    val prices = listOf(
        Price(1, 2_000, 15, 4, "شهر"),
        Price(6, 10_000, 75, 20, "6 أشهر"),
        Price(12, 18_000, 130, 35, "سنة"),
    )

    fun priceText(p: Price, c: Currency): String = when (c) {
        Currency.YER -> Money.format(Money.ofWhole(p.yer), c, false)
        Currency.SAR -> Money.format(Money.ofWhole(p.sar), c, false)
        Currency.USD -> Money.format(Money.ofWhole(p.usd), c, false)
    }

    private const val DAY = 24 * 3600_000L

    fun isPro(s: AppSettings, now: Long = System.currentTimeMillis()): Boolean = s.proUntil > now || inTrial(s, now)
    fun inTrial(s: AppSettings, now: Long = System.currentTimeMillis()): Boolean = s.trialStartedAt > 0 && now < s.trialStartedAt + TRIAL_DAYS * DAY
    fun trialDaysLeft(s: AppSettings, now: Long = System.currentTimeMillis()): Int =
        if (!inTrial(s, now)) 0 else (((s.trialStartedAt + TRIAL_DAYS * DAY) - now + DAY - 1) / DAY).toInt()

    fun allows(s: AppSettings, f: Feature, partyCount: Int = 0, now: Long = System.currentTimeMillis()): Boolean =
        isPro(s, now) || (f == Feature.UNLIMITED && partyCount < FREE_PARTIES)

    fun statusText(s: AppSettings, now: Long = System.currentTimeMillis()): String = when {
        s.proUntil > now -> "برو مفعّل حتى ${java.time.Instant.ofEpochMilli(s.proUntil).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { Dates.full(it) }}"
        inTrial(s, now) -> "تجربة برو مجانية · باقي ${trialDaysLeft(s, now)} يوم"
        else -> "النسخة المجانية · حتى $FREE_PARTIES حساب"
    }
}
