package com.daftari.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Money given / paid in another currency than the account's: the original amount and the rate used at that moment. */
data class Fx(val currency: String, val origAmount: Long, val rate: String)

/** One city's market: ريال يمني per 1 SAR / 1 USD, buy and sell, and the day they were set. */
data class CityRates(val city: String, val sarBuy: String, val sarSell: String, val usdBuy: String, val usdSell: String, val date: String) {
    fun per(code: String, buy: Boolean): BigDecimal? = when (code) {
        Currency.YER.code -> BigDecimal.ONE
        Currency.SAR.code -> (if (buy) sarBuy else sarSell).toBigDecimalOrNull()
        Currency.USD.code -> (if (buy) usdBuy else usdSell).toBigDecimalOrNull()
        else -> null
    }?.takeIf { it.signum() > 0 }

    fun toJson(): JSONObject = JSONObject().put("city", city).put("sarBuy", sarBuy).put("sarSell", sarSell).put("usdBuy", usdBuy).put("usdSell", usdSell).put("date", date)

    companion object {
        fun from(o: JSONObject) = CityRates(o.getString("city"), o.optString("sarBuy"), o.optString("sarSell"), o.optString("usdBuy"), o.optString("usdSell"), o.optString("date"))
    }
}

/**
 * The exchange-rate table from الإعدادات: صنعاء and عدن (their markets differ a lot), manual, each with its date; optionally
 * refreshed from the internet. The rate actually used is stored on each operation, so changing the table never rewrites history.
 * 1.1.2: every conversion (payment or debt) uses the «شراء» (buy) rate only; «بيع» (sell) is kept and shown for reference.
 */
data class Rates(val city: String = SANAA, val cities: List<CityRates> = DEFAULTS) {
    val current: CityRates get() = cities.firstOrNull { it.city == city } ?: cities.first()

    /** 1 [from] = ? [to], always at the «شراء» rate (the «بيع» rate is for reference only). */
    fun rate(from: String, to: String): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        val a = current.per(from, buy = true) ?: return null; val b = current.per(to, buy = true) ?: return null
        return a.divide(b, 6, RoundingMode.HALF_UP).stripTrailingZeros()
    }

    fun toJson(): String = JSONObject().put("city", city).put("cities", JSONArray().apply { cities.forEach { put(it.toJson()) } }).toString()

    fun with(c: CityRates) = copy(cities = cities.map { if (it.city == c.city) c else it }.let { l -> if (l.any { it.city == c.city }) l else l + c })

    companion object {
        const val SANAA = "صنعاء"; const val ADEN = "عدن"
        /** Market rates as of 30 Oct 2026 (ye-rial.com). */
        val DEFAULTS = listOf(
            CityRates(SANAA, "139.5", "140.2", "533", "535", "2026-10-30"),
            CityRates(ADEN, "411", "413", "1568", "1579", "2026-10-30"),
        )

        fun parse(s: String): Rates = if (s.isBlank()) Rates() else runCatching {
            val o = JSONObject(s); val a = o.getJSONArray("cities")
            Rates(o.optString("city", SANAA), (0 until a.length()).map { CityRates.from(a.getJSONObject(it)) })
        }.getOrDefault(Rates())

        /** origMinor × rate → account minor units. */
        fun convert(origMinor: Long, rate: String): Long =
            (rate.toBigDecimalOrNull() ?: BigDecimal.ZERO).multiply(BigDecimal.valueOf(origMinor)).setScale(0, RoundingMode.HALF_UP).toLong()

        fun plain(rate: BigDecimal): String = rate.stripTrailingZeros().toPlainString()

        /** «تحديث من النت»: reads the صنعاء / عدن tables of ye-rial.com. Null when the page changed or is not there. */
        fun parseYeRial(html: String, date: String): List<CityRates>? {
            val text = html.replace(Regex("(?s)<script.*?</script>|<style.*?</style>"), " ").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
            fun block(city: String): CityRates? {
                val i = text.indexOf("في $city"); if (i < 0) return null
                val seg = text.substring(i, minOf(text.length, i + 600))
                fun pair(code: String) = Regex("\\($code\\)\\s*([0-9.,]+)\\s+([0-9.,]+)").find(seg)?.let { it.groupValues[1].replace(",", "") to it.groupValues[2].replace(",", "") }
                val sar = pair("SAR") ?: return null; val usd = pair("USD") ?: return null
                return CityRates(city, sar.first, sar.second, usd.first, usd.second, date)
            }
            val l = listOfNotNull(block(SANAA), block(ADEN))
            return l.takeIf { it.isNotEmpty() }
        }
    }
}

/** The account's currency, falling back to the app default for rows from before 1.1.0. */
fun com.daftari.app.domain.Party.cur(default: Currency): Currency = if (currency.isBlank()) default else Currency.of(currency)

/** «100 SAR × 139.5 · حوالة 123456 (الكريمي)» / «محفظة جوالي رقم 778899» for statements. */
fun com.daftari.app.domain.Entry.fxNote(): String = listOfNotNull(
    if (foreign) "${Money.number(origAmount)} $currency × $rate" else null,
    PayMethods.describePlain(this).takeIf { method == "transfer" || method == "wallet" },
).joinToString(" · ")
