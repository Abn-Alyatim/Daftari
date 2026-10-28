package com.daftari.app.data

import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Party
import com.daftari.app.domain.PartyKind
import com.daftari.app.domain.Period
import com.daftari.app.domain.Role
import java.time.LocalDate

/** «جرّب ببيانات تجريبية»: a realistic market (names from a real Sana'a collection sheet) or a grocery. */
object Demo {
    val kahlaniNames = listOf(
        "أيوب العبدلي", "بدر هادي الاشول", "جلال القاضي", "حمدي الاسدي", "حيدر محمود سيف", "سعد الخولاني", "سعيد المنصري",
        "سليم عمر الحرازي", "عادل الضبيبي", "عبدالرحمن الشعباني", "عبدالله الحامدي", "عبدالله المنصور", "علي الضبيبي", "فوزي سعيد",
        "فيصل داؤود", "قايد السامعي", "ماهر الاخرم", "محسن الروم", "محمد الاشول", "محمد البكالي", "محمد الحرازي", "محمد هبه",
        "محمد يوسف", "المديره", "مصطفى الوصابي", "هاني الحماطي", "ياسر شلاله",
    )
    private val activities = listOf("بهارات وعطارة", "أقمشة", "عسل وسمن بلدي", "فضة وعقيق", "تمور", "ملابس أطفال", "أحذية", "عطور", "أدوات منزلية", "جوالات", "حلويات", "خضار وفواكه")
    private val collectors = listOf("أحمد", "صالح", "أحمد")

    data class Seed(val parties: List<Party>, val entries: (List<Long>) -> List<Entry>)

    fun market(today: LocalDate): Seed {
        val start = today.withDayOfMonth(1).minusMonths(1)
        val parties = kahlaniNames.mapIndexed { i, n ->
            val daily = longArrayOf(1500, 2000, 2000, 2500, 3000)[i % 5] * 100
            Party(
                kind = PartyKind.SHOP, shopNumber = (i + 1).toString(), name = n,
                phone = "+96777" + (1000000 + i * 37171).toString().takeLast(7),
                activity = activities[i % activities.size], periodicAmount = daily, period = Period.DAILY, startDate = start,
                unitsCount = if (i % 9 == 4) 2 else 1, openingBalance = if (i % 7 == 3) 15_000_00 else 0,
                notes = if (i % 8 == 2) "عقد ينتهي آخر السنة" else "",
            )
        }
        return Seed(parties) { ids ->
            val out = ArrayList<Entry>()
            ids.forEachIndexed { i, id ->
                val p = parties[i]
                val days = (today.toEpochDay() - start.toEpochDay() + 1).toInt()
                // most pay weekly; a few lag behind
                val reliability = when (i % 6) { 0, 1, 2 -> 1.0; 3 -> 0.85; 4 -> 0.6; else -> 0.95 }
                var d = start.plusDays(6)
                var receipt = 1000 + i * 17
                while (!d.isAfter(today)) {
                    val amount = (p.periodicAmount * 7 * reliability).toLong() / 100 * 100
                    out += Entry(partyId = id, type = EntryType.PAYMENT, amount = amount, date = d, note = "دفعة أسبوعية",
                        collector = collectors[(i + d.dayOfMonth) % collectors.size], receiptNo = (receipt++).toString())
                    d = d.plusDays(7)
                }
                if (i % 3 == 0) out += Entry(partyId = id, type = EntryType.PAYMENT, amount = p.periodicAmount, date = today, note = "إيجار اليوم",
                    collector = "أحمد", receiptNo = (receipt).toString())
                if (days < 0) Unit
            }
            out
        }
    }

    fun grocery(today: LocalDate): Seed {
        val names = listOf(
            Triple("مختار عبده", "+967771234567", 0L), Triple("فهد الحميري", "+967733456789", 30_000_00L), Triple("نجيب الوصابي", "+967712233445", 0L),
            Triple("أم سلمان", "+967770011223", 20_000_00L), Triple("عمار الأكوع", "+967735566778", 0L), Triple("حسين المطري", "+967778899001", 25_000_00L),
            Triple("بشير القدسي", "+967771112223", 0L), Triple("سلوى الشرعبي", "+967739988776", 15_000_00L), Triple("عبدالكريم الريمي", "+967774433221", 0L),
        )
        val parties = names.map { (n, ph, lim) -> Party(kind = PartyKind.CUSTOMER, name = n, phone = ph, creditLimit = lim, startDate = today.minusDays(40)) }
        return Seed(parties) { ids ->
            val e = ArrayList<Entry>()
            fun debt(i: Int, amt: Long, ago: Long, items: String, due: Long? = null) = e.add(Entry(partyId = ids[i], type = EntryType.CHARGE, amount = amt * 100,
                date = today.minusDays(ago), note = "", items = items, dueDate = due?.let { today.plusDays(it) }))
            fun pay(i: Int, amt: Long, ago: Long) = e.add(Entry(partyId = ids[i], type = EntryType.PAYMENT, amount = amt * 100, date = today.minusDays(ago), note = "تسديد"))
            debt(0, 8400, 9, "كيس رز 10 كيلو\nزيت 1.5 لتر ×2\nسكر 2 كيلو", -2); debt(0, 4000, 1, "حليب نيدو\nشاي"); pay(0, 0, 0)
            debt(1, 12000, 20, "مواد شهرية"); pay(1, 8500, 6); debt(1, 3000, 2, "بيض كرتون")
            debt(2, 5600, 14, "دقيق 10 كيلو"); pay(2, 5600, 3)
            debt(3, 9800, 35, "مواد غذائية", -20); debt(3, 13000, 12, "أرز\nفاصوليا\nزيت")
            debt(4, 1200, 0, "مشروبات")
            debt(5, 21000, 48, "حساب شهر رمضان"); pay(5, 4000, 30); debt(5, 8000, 5, "غاز\nسكر", 0)
            debt(6, 3500, 3, "خبز وجبن"); pay(6, 3500, 0)
            debt(7, 16200, 18, "مواد الأسبوع", 1)
            debt(8, 2600, 4, "منظفات")
            e.filter { it.amount > 0 }
        }
    }

    fun forRole(role: Role, today: LocalDate) = if (role == Role.LANDLORD) market(today) else grocery(today)
    fun businessFor(role: Role) = if (role == Role.LANDLORD) "سوق الكحلاني" to "سامي الهلباء" else "بقالة الأمانة" to "عبدالله أحمد"
}
