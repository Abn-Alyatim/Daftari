package com.daftari.app.importer

import com.daftari.app.core.Money
import com.daftari.app.core.asciiDigits
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** One tenant/customer found in an imported file, before the user confirms it. */
@Serializable
data class ImportRow(
    val name: String,
    val shopNumber: String = "",
    val phone: String = "",
    val activity: String = "",
    /** periodic rent / debt amount, minor units */
    val amount: Long = 0,
    /** المتأخرات, minor units */
    val arrears: Long = 0,
    val note: String = "",
    /** true when the importer is not sure (shown highlighted in the review screen) */
    val doubtful: Boolean = false,
    val reason: String = "",
    val include: Boolean = true,
)

data class ImportResult(
    val rows: List<ImportRow>,
    val businessName: String = "",
    val ownerName: String = "",
    val warnings: List<String> = emptyList(),
    /** the plain text that was read, for the AI pass */
    val rawText: String = "",
)

/**
 * Offline parser for clean tables: .docx (Word), .xlsx (Excel), .csv/.txt. Finds the header row (a cell with «اسم»),
 * maps the other columns by keywords, and returns the named rows. Messy files and photos go to the AI import instead.
 */
object SheetImporter {

    fun parse(fileName: String, bytes: ByteArray): ImportResult {
        val lower = fileName.lowercase()
        val table: List<List<String>>
        var paragraphs = emptyList<String>()
        when {
            lower.endsWith(".docx") -> { val d = docx(bytes); table = d.first; paragraphs = d.second }
            lower.endsWith(".xlsx") -> table = xlsx(bytes)
            lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv") -> table = csv(String(bytes, Charsets.UTF_8))
            else -> return ImportResult(emptyList(), warnings = listOf("نوع الملف يحتاج الاستيراد الذكي"))
        }
        val (business, owner) = detectHeader(paragraphs + table.take(3).flatten())
        val raw = (paragraphs + table.map { it.joinToString(" | ") }).joinToString("\n")
        return fromTable(table).copy(businessName = business, ownerName = owner, rawText = raw)
    }

    /** «سوق الكحلاني...المؤجر/ سامي الهلباء» → (سوق الكحلاني, سامي الهلباء) */
    fun detectHeader(texts: List<String>): Pair<String, String> {
        val re = Regex("(سوق\\s*[^.\\n/]+?)\\s*\\.{2,}\\s*المؤجر\\s*/?\\s*([^.\\n]+?)(?:سوق|حافظة|$)")
        for (t in texts) {
            val m = re.find(t) ?: continue
            return m.groupValues[1].trim() to m.groupValues[2].trim()
        }
        return "" to ""
    }

    fun fromTable(table: List<List<String>>): ImportResult {
        val rows = table.map { r -> r.map { clean(it) } }.filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ImportResult(emptyList(), warnings = listOf("ما لقيت جدول في الملف"))
        val headerIdx = rows.indexOfFirst { r -> r.any { it.contains("اسم") || it == "الاسم" || it.contains("المستأجر") || it.contains("الزبون") } }
        val warnings = ArrayList<String>()
        val header: List<String>
        val body: List<List<String>>
        if (headerIdx >= 0) { header = rows[headerIdx]; body = rows.drop(headerIdx + 1) } else {
            header = emptyList(); body = rows; warnings += "ما لقيت عنوان الأعمدة، خمّنت عمود الأسماء"
        }
        fun col(vararg keys: String, exclude: List<String> = emptyList()): Int =
            header.indexOfFirst { h -> keys.any { h.contains(it) } && exclude.none { h.contains(it) } }
        var nameCol = col("اسم", "المستأجر", "الزبون", exclude = listOf("المحصل ", "اسم المحصل"))
        if (nameCol < 0) {
            val width = body.maxOfOrNull { it.size } ?: 0
            nameCol = (0 until width).maxByOrNull { c -> body.count { r -> r.getOrNull(c)?.any { ch -> ch in '\u0621'..'\u064A' } == true } } ?: 0
        }
        val shopCol = col("رقم المحل", "المحل", "الدكان", exclude = listOf("عدد")).let { if (it >= 0) it else header.indexOfFirst { it.trim() == "رقم" } }
        val phoneCol = col("جوال", "هاتف", "تلفون", "موبايل", "واتس")
        val activityCol = col("يبيع", "النشاط", "نوع", "البضاعة")
        val amountCol = col("الإيجار", "الايجار", "اليومي", "الشهري", "المبلغ المحصل", "المبلغ", exclude = listOf("المتأخر"))
        val arrearsCol = col("متأخرات", "المتأخرات", "المتبقي", "الرصيد", "عليه")
        val noteCol = col("ملاحظ")
        val out = ArrayList<ImportRow>()
        for (r in body) {
            val name = r.getOrNull(nameCol).orEmpty().trim()
            if (name.isBlank()) continue
            if (name.contains("الإجمالي") || name.contains("المجموع")) continue
            val amountText = r.getOrNull(amountCol).orEmpty()
            val arrearsText = r.getOrNull(arrearsCol).orEmpty()
            val amount = if (amountCol >= 0) Money.parse(amountText) ?: 0 else 0
            val arrears = if (arrearsCol >= 0) Money.parse(arrearsText) ?: 0 else 0
            val reasons = ArrayList<String>()
            if (name.any { it.isDigit() }) reasons += "الاسم فيه أرقام"
            if (name.length < 3) reasons += "الاسم قصير"
            if (amountText.isNotBlank() && Money.parse(amountText) == null) reasons += "مبلغ غير واضح: $amountText"
            if (arrearsText.isNotBlank() && Money.parse(arrearsText) == null) reasons += "متأخرات غير واضحة: $arrearsText"
            out += ImportRow(
                name = name,
                shopNumber = r.getOrNull(shopCol).orEmpty().asciiDigits().trim(),
                phone = r.getOrNull(phoneCol).orEmpty().asciiDigits().filter { it.isDigit() || it == '+' },
                activity = r.getOrNull(activityCol).orEmpty(),
                amount = amount,
                arrears = arrears,
                note = r.getOrNull(noteCol).orEmpty(),
                doubtful = reasons.isNotEmpty(),
                reason = reasons.joinToString("، "),
            )
        }
        // duplicates
        val seen = HashMap<String, Int>()
        val marked = out.map { row ->
            val key = row.name.replace(" ", "")
            val n = (seen[key] ?: 0) + 1; seen[key] = n
            if (n > 1) row.copy(doubtful = true, reason = listOf(row.reason, "اسم مكرر").filter { it.isNotBlank() }.joinToString("، ")) else row
        }
        if (marked.isEmpty()) warnings += "ما لقيت أسماء"
        return ImportResult(marked, warnings = warnings)
    }

    private fun clean(s: String) = s.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun xmlText(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Word: every table row → cells; also the paragraphs outside tables (for the market/landlord title). */
    fun docx(bytes: ByteArray): Pair<List<List<String>>, List<String>> {
        val xml = String(unzip(bytes)["word/document.xml"] ?: return emptyList<List<String>>() to emptyList(), Charsets.UTF_8)
        // drop the fallback copy of text boxes so text is not doubled
        val noFallback = xml.replace(Regex("<mc:Fallback>.*?</mc:Fallback>", RegexOption.DOT_MATCHES_ALL), "")
        val text = { frag: String -> xmlText(Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(frag).joinToString("") { it.groupValues[1] }) }
        val rows = Regex("<w:tr[ >].*?</w:tr>", RegexOption.DOT_MATCHES_ALL).findAll(noFallback).map { tr ->
            Regex("<w:tc>.*?</w:tc>|<w:tc .*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(tr.value).map { text(it.value) }.toList()
        }.toList()
        val outside = noFallback.replace(Regex("<w:tbl>.*?</w:tbl>", RegexOption.DOT_MATCHES_ALL), "")
        val paras = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(outside).map { text(it.value) }.filter { it.isNotBlank() }.toList()
        // text boxes inside the first row (titles) are also useful
        val boxes = Regex("<w:txbxContent>.*?</w:txbxContent>", RegexOption.DOT_MATCHES_ALL).findAll(noFallback).map { text(it.value) }.toList()
        return rows to (paras + boxes)
    }

    /** Excel: first worksheet, shared + inline strings. */
    fun xlsx(bytes: ByteArray): List<List<String>> {
        val files = unzip(bytes)
        val shared = files["xl/sharedStrings.xml"]?.let { String(it, Charsets.UTF_8) }?.let { s ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(s).map { si ->
                xmlText(Regex("<t(?: [^>]*)?>([^<]*)</t>").findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] })
            }.toList()
        }.orEmpty()
        val sheetName = files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }.minOrNull() ?: return emptyList()
        val sheet = String(files[sheetName]!!, Charsets.UTF_8)
        return Regex("<row[ >].*?</row>", RegexOption.DOT_MATCHES_ALL).findAll(sheet).map { row ->
            val cells = ArrayList<String>()
            Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.value).forEach { c ->
                val attrs = c.groupValues[1]
                val inner = c.groupValues[2]
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                val idx = ref?.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) }?.minus(1) ?: cells.size
                val type = Regex("t=\"([a-zA-Z]+)\"").find(attrs)?.groupValues?.get(1)
                val v = Regex("<v>([^<]*)</v>").find(inner)?.groupValues?.get(1).orEmpty()
                val value = when (type) {
                    "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                    "inlineStr" -> xmlText(Regex("<t(?: [^>]*)?>([^<]*)</t>").findAll(inner).joinToString("") { it.groupValues[1] })
                    else -> xmlText(v).let { if (it.endsWith(".0")) it.dropLast(2) else it }
                }
                while (cells.size < idx) cells += ""
                if (cells.size == idx) cells += value else cells[idx] = value
            }
            cells
        }.toList()
    }

    fun csv(text: String): List<List<String>> {
        val lines = text.replace("\r\n", "\n").split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val first = lines.first()
        val delim = listOf('\t', ';', ',', '|', '،').maxByOrNull { d -> first.count { it == d } } ?: ','
        return lines.map { line ->
            val out = ArrayList<String>(); val cur = StringBuilder(); var q = false
            for (ch in line) {
                when {
                    ch == '"' -> q = !q
                    ch == delim && !q -> { out += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            out += cur.toString(); out
        }
    }
}
