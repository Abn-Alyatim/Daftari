package com.daftari.app.importer

import com.daftari.app.chat.ArabicText
import com.daftari.app.domain.Entry
import com.daftari.app.domain.EntryType
import com.daftari.app.domain.Party
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What a column means. */
enum class Field(val ar: String) {
    NAME("الاسم"), AMOUNT("المبلغ"), DEBIT("عليه / دين"), CREDIT("له / سداد"), BALANCE("الرصيد"), TYPE("النوع"), DATE("التاريخ"),
    PHONE("الجوال"), NOTE("ملاحظات"), ITEMS("الأصناف"), MONTH("شهر"), IGNORE("تجاهل"),
}

enum class Layout(val ar: String) { TRANSACTIONS("عملية في كل سطر"), BALANCES("رصيد لكل زبون"), MONTHLY("تفصيل شهري") }

/** One sheet / table read from the file, header row found. */
data class SourceTable(
    val fileName: String, val sheet: String, val title: String, val header: List<String>, val rows: List<List<String>>,
) {
    /** Same layout from the same app → same signature (used to remember his column mapping). */
    val signature: String get() = header.joinToString("|") { ArabicText.normalize(it) }
}

data class Mapping(val fields: List<Field>, val layout: Layout, val year: Int, val months: Map<Int, Int> = emptyMap())

enum class Dup(val ar: String) { IN_FILE("مكرر في الملف"), EXISTING("موجود مسبقاً") }

data class ImportTxn(
    val name: String, val phone: String, val type: EntryType, val amount: Long, val date: LocalDate, val note: String, val items: String,
    val row: Int, val dup: Dup? = null,
)

data class CustomerTotal(val name: String, val phone: String, val existingId: Long?, val debt: Long, val paid: Long, val count: Int) {
    val net: Long get() = debt - paid
}

data class ImportPlan(
    val table: SourceTable, val mapping: Mapping, val txns: List<ImportTxn>, val customers: List<CustomerTotal>,
    /** the file's own «الإجمالي» row, when it has one */
    val fileTotal: Long?, val skippedRows: Int, val warnings: List<String>,
) {
    val fileNet: Long get() = txns.sumOf { if (it.type == EntryType.PAYMENT) -it.amount else it.amount }
    val toImport: List<ImportTxn> get() = txns.filter { it.dup == null }
    val importNet: Long get() = toImport.sumOf { if (it.type == EntryType.PAYMENT) -it.amount else it.amount }
    val newCustomers: Int get() = customers.count { it.existingId == null }
    val totalMatches: Boolean? get() = fileTotal?.let { it == fileNet }
}

object StatementImporter {
    private val months = listOf(
        listOf("يناير", "جانفي", "كانون الثاني", "january", "jan"), listOf("فبراير", "فيفري", "شباط", "february", "feb"),
        listOf("مارس", "اذار", "march", "mar"), listOf("ابريل", "افريل", "نيسان", "april", "apr"), listOf("مايو", "ماي", "ايار", "may"),
        listOf("يونيو", "جوان", "حزيران", "june", "jun"), listOf("يوليو", "جويليه", "تموز", "july", "jul"), listOf("اغسطس", "اوت", "اب", "august", "aug"),
        listOf("سبتمبر", "ايلول", "september", "sep"), listOf("اكتوبر", "تشرين الاول", "october", "oct"), listOf("نوفمبر", "تشرين الثاني", "november", "nov"),
        listOf("ديسمبر", "كانون الاول", "december", "dec"),
    ).map { l -> l.map { ArabicText.normalize(it) } }

    val monthNamesAr = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")

    private fun n(s: String) = ArabicText.normalize(s)
    private fun syn(vararg w: String) = w.map { n(it) }
    private val synonyms: List<Pair<Field, List<String>>> = listOf(
        Field.PHONE to syn("الجوال", "جوال", "الهاتف", "هاتف", "موبايل", "الموبايل", "رقم الجوال", "رقم الهاتف", "التلفون", "تلفون", "phone", "mobile", "tel", "telephone", "whatsapp", "واتساب"),
        Field.NAME to syn("اسم الحساب", "إسم الحساب", "الحساب", "اسم العميل", "اسم الزبون", "العميل", "عميل", "الزبون", "زبون", "الاسم", "اسم", "المستاجر", "اسم المستاجر", "صاحب المحل", "name", "customer", "client", "account", "party", "customer name", "full name", "contact"),
        Field.TYPE to syn("نوع العمليه", "نوع الحركه", "النوع", "نوع", "العمليه", "الحركه", "type", "kind", "transaction type", "operation"),
        Field.DEBIT to syn("مدين", "عليه", "دين", "الدين", "ديون", "عليه دين", "debit", "debt", "charge", "charges", "owed", "dr"),
        Field.CREDIT to syn("دائن", "له", "سداد", "مسدد", "المسدد", "المدفوع", "مدفوع", "دفعات", "تسديد", "payment", "paid", "credit", "cr", "received"),
        Field.BALANCE to syn("الرصيد", "رصيد", "الصافي", "المتبقي", "الباقي", "balance", "net", "remaining"),
        Field.AMOUNT to syn("المبلغ", "مبلغ", "القيمه", "قيمه", "الاجمالي", "اجمالي", "المجموع", "amount", "value", "total", "sum", "price"),
        Field.DATE to syn("التاريخ", "تاريخ", "اليوم", "تاريخ العمليه", "date", "day", "created", "created at", "time", "datetime"),
        Field.ITEMS to syn("الاصناف", "اصناف", "الصنف", "صنف", "البضاعه", "المشتريات", "items", "item", "product", "products"),
        Field.NOTE to syn("ملاحظات", "ملاحظه", "البيان", "بيان", "الوصف", "وصف", "التفاصيل", "notes", "note", "description", "memo", "details", "remark", "remarks"),
        Field.IGNORE to syn("رقم المحل", "المحل", "م", "#", "id", "no", "رقم", "الرقم", "تسلسل", "مسلسل", "العمله", "عمله", "currency"),
    )

    private val totalWords = syn("الاجمالي", "اجمالي", "المجموع", "مجموع", "الرصيد الاجمالي", "الاجمالى", "الكلي", "total", "grand total", "sum")

    // ------------------------------------------------------------------ reading

    fun decodeText(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        if (!utf.contains('\uFFFD')) return utf
        return runCatching { String(bytes, charset("windows-1256")) }.getOrDefault(utf)
    }

    /** All tables in a CSV / Excel (.xlsx, .xls) / JSON file. SQLite is read by the Android side ([SqliteSource]). */
    fun read(fileName: String, bytes: ByteArray): List<SourceTable> {
        val lower = fileName.lowercase()
        val grids: List<Pair<String, List<List<String>>>> = when {
            XlsReader.isXls(bytes) -> XlsReader.read(bytes).map { it.name to it.rows }
            lower.endsWith(".xlsx") || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && !lower.endsWith(".docx")) -> listOf("Sheet1" to SheetImporter.xlsx(bytes))
            lower.endsWith(".json") || decodeText(bytes).trimStart().let { it.startsWith("{") || it.startsWith("[") } -> return jsonTables(fileName, decodeText(bytes))
            else -> listOf("" to SheetImporter.csv(decodeText(bytes)))
        }
        return grids.mapNotNull { (sheet, g) -> table(fileName, sheet, g) }
    }

    fun table(fileName: String, sheet: String, grid0: List<List<String>>): SourceTable? {
        val grid = grid0.map { r -> r.map { it.replace('\u00A0', ' ').trim() } }.filter { r -> r.any { it.isNotBlank() } }
        if (grid.isEmpty()) return null
        val width = grid.maxOf { it.size }
        val g = grid.map { it + List(width - it.size) { "" } }
        // header = the row (in the first 15) with the most recognised column names
        var best = -1; var bestScore = 0
        for (i in 0 until minOf(15, g.size)) {
            val score = g[i].count { c -> c.isNotBlank() && (headerField(c) != null || monthOf(c) != null) }
            if (score > bestScore) { best = i; bestScore = score }
        }
        if (best < 0) best = g.indexOfFirst { r -> r.count { it.isNotBlank() } >= 2 }.coerceAtLeast(0)
        val title = g.take(best).flatten().firstOrNull { it.isNotBlank() }.orEmpty()
        val header = g[best]
        val rows = g.drop(best + 1)
        val keep = header.indices.filter { c -> header[c].isNotBlank() || rows.any { it[c].isNotBlank() } }
        return SourceTable(fileName, sheet, title, keep.map { header[it].ifBlank { "عمود ${it + 1}" } }, rows.map { r -> keep.map { r[it] } })
    }

    fun jsonTables(fileName: String, text: String): List<SourceTable> {
        val root: Any = runCatching { JSONObject(text) }.getOrNull() ?: runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val arrays = ArrayList<Pair<String, JSONArray>>()
        fun walk(name: String, v: Any?, depth: Int) {
            when (v) {
                is JSONArray -> if (v.length() > 0 && v.opt(0) is JSONObject) arrays += name to v
                is JSONObject -> if (depth < 3) v.keys().forEach { k -> walk(k, v.opt(k), depth + 1) }
            }
        }
        walk("data", root, 0)
        return arrays.sortedByDescending { it.second.length() }.mapNotNull { (name, arr) ->
            val keys = LinkedHashSet<String>(); val rows = ArrayList<Map<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val scalars = LinkedHashMap<String, String>(); var children: JSONArray? = null
                o.keys().forEach { k ->
                    when (val v = o.opt(k)) {
                        is JSONArray -> if (v.length() > 0 && v.opt(0) is JSONObject) children = v
                        is JSONObject -> v.keys().forEach { kk -> v.opt(kk).takeIf { it !is JSONObject && it !is JSONArray }?.let { scalars["$k.$kk"] = it.toString() } }
                        null, JSONObject.NULL -> scalars[k] = ""
                        else -> scalars[k] = v.toString()
                    }
                }
                val ch = children
                if (ch != null) for (j in 0 until ch.length()) {
                    val c = ch.optJSONObject(j) ?: continue
                    val row = LinkedHashMap(scalars)
                    c.keys().forEach { k -> c.opt(k).takeIf { it !is JSONObject && it !is JSONArray }?.let { row[k] = if (it == JSONObject.NULL) "" else it.toString() } }
                    rows += row; keys += row.keys
                } else { rows += scalars; keys += scalars.keys }
            }
            if (rows.isEmpty()) null else SourceTable(fileName, name, "", keys.toList(), rows.map { r -> keys.map { r[it].orEmpty() } })
        }
    }

    // ------------------------------------------------------------------ understanding

    fun monthOf(h: String): Int? { val t = n(h); return months.indexOfFirst { l -> l.any { it == t } }.takeIf { it >= 0 }?.plus(1) }

    fun headerField(h: String): Field? {
        val t = n(h)
        if (t.isBlank()) return null
        synonyms.forEach { (f, l) -> if (l.any { it == t }) return f }
        synonyms.forEach { (f, l) -> if (f != Field.IGNORE && l.any { it.length >= 3 && t.contains(it) }) return f }
        return null
    }

    private fun looksPhone(v: String) = v.replace(Regex("[^0-9]"), "").let { d -> d.length in 9..14 && (d.startsWith("7") || d.startsWith("05") || d.startsWith("9") || d.startsWith("00") || d.startsWith("01")) }
    private fun looksType(v: String) = typeOf(v) != null

    fun guess(t: SourceTable): Mapping {
        val fields = t.header.map { headerField(it) ?: Field.IGNORE }.toMutableList()
        val monthCols = t.header.indices.associateWith { monthOf(t.header[it]) }.filterValues { it != null }.mapValues { it.value!! }
        monthCols.keys.forEach { fields[it] = Field.MONTH }
        val sample = t.rows.take(40)
        fun col(c: Int) = sample.map { it.getOrElse(c) { "" } }.filter { it.isNotBlank() }
        // content sniffing for unrecognised columns
        t.header.indices.forEach { c ->
            val v = col(c)
            if (v.isEmpty()) return@forEach
            if (fields[c] == Field.IGNORE || fields[c] == Field.AMOUNT || fields[c] == Field.DEBIT) {
                when {
                    v.count { looksPhone(it) } * 10 >= v.size * 7 && fields[c] == Field.IGNORE -> fields[c] = Field.PHONE
                    v.count { looksType(it) } * 10 >= v.size * 7 -> fields[c] = Field.TYPE
                    v.count { parseDate(it) != null && amount(it) == null } * 10 >= v.size * 7 && fields[c] == Field.IGNORE -> fields[c] = Field.DATE
                }
            }
        }
        if (Field.NAME !in fields) {
            t.header.indices.firstOrNull { c -> fields[c] == Field.IGNORE && col(c).let { v -> v.isNotEmpty() && v.count { amount(it) == null && parseDate(it) == null } * 10 >= v.size * 8 } }?.let { fields[it] = Field.NAME }
        }
        if (listOf(Field.AMOUNT, Field.DEBIT, Field.CREDIT, Field.BALANCE).none { it in fields } && monthCols.isEmpty()) {
            t.header.indices.firstOrNull { c -> fields[c] == Field.IGNORE && col(c).let { v -> v.isNotEmpty() && v.count { amount(it) != null } * 10 >= v.size * 8 } }?.let { fields[it] = Field.AMOUNT }
        }
        // two amount-ish columns: «المبلغ» + «الرصيد» → amount is the movement, balance is ignored for transactions
        val nameIdx = fields.indexOf(Field.NAME)
        val repeated = nameIdx >= 0 && t.rows.map { n(it.getOrElse(nameIdx) { "" }) }.filter { it.isNotBlank() }.let { it.size != it.toSet().size }
        val layout = when {
            monthCols.size >= 2 -> Layout.MONTHLY
            Field.DATE in fields || Field.TYPE in fields || (Field.DEBIT in fields && Field.CREDIT in fields) || repeated -> Layout.TRANSACTIONS
            else -> Layout.BALANCES
        }
        val year = Regex("(19|20)\\d{2}").find(n(t.title))?.value?.toInt() ?: LocalDate.now().year
        return Mapping(fields, layout, year, monthCols)
    }

    // ------------------------------------------------------------------ values

    /** «2,000» «٢٠٠٠» «202,050 محلي» «(500)» «500-» «1.250,50» «-300 له» → minor units; sign kept. */
    fun amount(raw: String): Long? {
        var t = ArabicText.normalize(raw)
        if (t.isBlank()) return null
        var neg = false
        if (t.contains("(") && t.contains(")")) neg = true
        val words = t.split(' ')
        if (words.any { it in setOf("له", "دائن", "cr") }) neg = true
        t = t.replace(Regex("[^0-9.,\\-]"), "")
        if (t.startsWith("-") || t.endsWith("-")) neg = true
        t = t.replace("-", "")
        if (t.isEmpty() || t.none { it.isDigit() }) return null
        if (t.contains(',') && t.contains('.')) t = if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        else if (t.contains(',')) t = if (Regex("\\d{1,3}(,\\d{3})+").matches(t)) t.replace(",", "") else t.replace(',', '.')
        if (t.count { it == '.' } > 1) t = t.replace(".", "")
        val v = runCatching { BigDecimal(t) }.getOrNull() ?: return null
        val m = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        return if (neg) -m else m
    }

    fun typeOf(v: String): EntryType? {
        val t = n(v)
        return when {
            t.isBlank() -> null
            listOf("سداد", "تسديد", "دفعه", "دفع", "مدفوع", "دائن", "له", "قبض", "استلام", "مقبوض", "سند قبض", "payment", "paid", "credit", "receipt", "cr", "in").any { t == n(it) || t.startsWith(n(it) + " ") } -> EntryType.PAYMENT
            listOf("دين", "مدين", "عليه", "شراء", "بيع", "فاتوره", "سحب", "اجل", "بالاجل", "charge", "debit", "debt", "sale", "invoice", "dr", "out").any { t == n(it) || t.startsWith(n(it) + " ") } -> EntryType.CHARGE
            else -> null
        }
    }

    private val dateFormats = listOf("yyyy-M-d", "yyyy/M/d", "d/M/yyyy", "d-M-yyyy", "d.M.yyyy", "d/M/yy", "yyyy.M.d").map { DateTimeFormatter.ofPattern(it) }

    fun parseDate(raw: String): LocalDate? {
        val t = ArabicText.normalize(raw).replace('\\', '/').trim()
        if (t.isBlank()) return null
        val core = t.split(' ', 'T').first()
        dateFormats.forEach { f -> runCatching { return LocalDate.parse(core, f) } }
        // Excel serial day (1900 system)
        core.toDoubleOrNull()?.let { if (it in 20000.0..80000.0) return LocalDate.of(1899, 12, 30).plusDays(it.toLong()) }
        // epoch millis
        core.toLongOrNull()?.let { if (it in 946_684_800_000L..4_102_444_800_000L) return java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        return null
    }

    private fun isTotalRow(name: String): Boolean { val t = n(name); return t.isNotBlank() && totalWords.any { t == it || t.startsWith("$it ") || t.contains(" $it") || t.startsWith(it) } }

    fun key(name: String) = n(name).replace(" ", "")
    private fun phoneKey(p: String) = p.replace(Regex("[^0-9]"), "").takeLast(9)

    // ------------------------------------------------------------------ the plan

    fun build(t: SourceTable, m: Mapping, existing: List<Party>, existingEntries: List<Entry>, today: LocalDate, skipFileDups: Boolean = true): ImportPlan {
        val f = m.fields
        fun idx(x: Field) = f.indexOf(x)
        val iName = idx(Field.NAME); val iPhone = idx(Field.PHONE); val iAmount = idx(Field.AMOUNT); val iDebit = idx(Field.DEBIT); val iCredit = idx(Field.CREDIT)
        val iBal = idx(Field.BALANCE); val iType = idx(Field.TYPE); val iDate = idx(Field.DATE); val iNote = idx(Field.NOTE); val iItems = idx(Field.ITEMS)
        val monthCols = f.indices.filter { f[it] == Field.MONTH }.mapNotNull { c -> (m.months[c] ?: monthOf(t.header[c]))?.let { c to it } }
        val warnings = ArrayList<String>()
        val out = ArrayList<ImportTxn>()
        var skipped = 0
        var fileTotal: Long? = null
        if (iName < 0) warnings += "حدد عمود الاسم"
        t.rows.forEachIndexed { r, row ->
            fun cell(i: Int) = if (i >= 0) row.getOrElse(i) { "" } else ""
            val name = cell(iName).trim()
            if (name.isBlank()) { if (row.any { it.isNotBlank() }) skipped++; return@forEachIndexed }
            if (isTotalRow(name)) {
                val tv = amount(cell(iBal)) ?: amount(cell(iAmount)) ?: monthCols.sumOf { amount(cell(it.first)) ?: 0L }.takeIf { monthCols.isNotEmpty() }
                    ?: (amount(cell(iDebit)) ?: 0L) - (amount(cell(iCredit)) ?: 0L)
                fileTotal = if (n(name).contains("له")) -kotlin.math.abs(tv) else tv
                return@forEachIndexed
            }
            val phone = cell(iPhone).let { p -> if (looksPhone(p)) p.replace(Regex("[^0-9+]"), "") else "" }
            val date = parseDate(cell(iDate)) ?: today
            val note = cell(iNote); val items = cell(iItems)
            fun add(type: EntryType, minor: Long, d: LocalDate = date, nt: String = note) {
                if (minor == 0L) return
                val (tp, am) = if (minor < 0) (if (type == EntryType.CHARGE) EntryType.PAYMENT else EntryType.CHARGE) to -minor else type to minor
                out += ImportTxn(name, phone, tp, am, d, nt, items, r)
            }
            when (m.layout) {
                Layout.MONTHLY -> {
                    val vals = monthCols.map { (c, mo) -> mo to (amount(cell(c)) ?: 0L) }.filter { it.second != 0L }
                    val bal = amount(cell(iBal))
                    if (bal != null && vals.sumOf { it.second } != bal) add(EntryType.CHARGE, bal, today, "رصيد سابق (مستورد)")
                    else vals.forEach { (mo, v) ->
                        var d = LocalDate.of(m.year, mo, 1); if (d.isAfter(today)) d = d.minusYears(1)
                        add(EntryType.CHARGE, v, d, "مستورد: ${monthNamesAr[mo - 1]} ${d.year}")
                    }
                    if (vals.isEmpty() && bal == null) skipped++
                }
                Layout.BALANCES -> {
                    val v = amount(cell(iBal)) ?: amount(cell(iAmount)) ?: if (iDebit >= 0 || iCredit >= 0) (amount(cell(iDebit)) ?: 0L) - (amount(cell(iCredit)) ?: 0L) else null
                    if (v == null) skipped++ else add(EntryType.CHARGE, v, date, note.ifBlank { if (v >= 0) "رصيد سابق (مستورد)" else "رصيد له (مستورد)" })
                }
                Layout.TRANSACTIONS -> {
                    val d = amount(cell(iDebit)); val c = amount(cell(iCredit))
                    if (d != null || c != null) { d?.let { add(EntryType.CHARGE, it) }; c?.let { add(EntryType.PAYMENT, it) } }
                    else {
                        val v = amount(cell(iAmount)) ?: amount(cell(iBal))
                        if (v == null) skipped++ else add(typeOf(cell(iType)) ?: EntryType.CHARGE, v)
                    }
                }
            }
        }
        // customers: match existing by phone, then by normalized name
        val byPhone = existing.filter { it.phone.isNotBlank() }.associateBy { phoneKey(it.phone) }
        val byName = existing.associateBy { key(it.name) }
        fun matchOf(name: String, phone: String): Party? = (if (phone.isNotBlank()) byPhone[phoneKey(phone)] else null) ?: byName[key(name)]
        // duplicates
        val seen = HashSet<String>()
        val existingByParty = existingEntries.groupBy { it.partyId }
        val marked = out.map { x ->
            val k = listOf(key(x.name), x.type, x.amount, x.date, n(x.items), n(x.note)).joinToString("|")
            val p = matchOf(x.name, x.phone)
            val inExisting = p != null && existingByParty[p.id].orEmpty().any { e ->
                e.type == x.type && e.amount == x.amount && e.date == x.date && (x.items.isBlank() || n(e.items) == n(x.items)) && (x.note.isBlank() || n(e.note) == n(x.note) || e.note.contains("مستورد"))
            }
            when {
                inExisting -> x.copy(dup = Dup.EXISTING)
                !seen.add(k) && skipFileDups -> x.copy(dup = Dup.IN_FILE)
                else -> x
            }
        }
        val customers = marked.groupBy { key(it.name) }.map { (_, l) ->
            val first = l.first(); val p = matchOf(first.name, l.firstOrNull { it.phone.isNotBlank() }?.phone.orEmpty())
            val live = l.filter { it.dup == null }
            CustomerTotal(first.name, l.firstOrNull { it.phone.isNotBlank() }?.phone.orEmpty(), p?.id,
                live.filter { it.type == EntryType.CHARGE }.sumOf { it.amount }, live.filter { it.type == EntryType.PAYMENT }.sumOf { it.amount }, live.size)
        }.sortedByDescending { it.net }
        return ImportPlan(t, m, marked, customers, fileTotal, skipped, warnings)
    }
}
