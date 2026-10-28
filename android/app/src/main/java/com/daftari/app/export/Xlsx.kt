package com.daftari.app.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A minimal, valid .xlsx (one right-to-left sheet): title rows, a bold bordered header, data rows, a totals row. Numbers stay numbers. */
object XlsxWriter {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun colName(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** "12,500.50" → 12500.5; anything else stays text. */
    fun numberOrNull(v: String): Double? {
        val t = v.trim().replace(",", "").replace("\u066C", "")
        if (t.isEmpty() || !t.matches(Regex("-?\\d+(\\.\\d+)?"))) return null
        if (t.length > 1 && t.startsWith("0") && !t.startsWith("0.")) return null // phone numbers, shop codes
        return t.toDouble()
    }

    private fun cell(ref: String, v: String, style: Int): String {
        val n = numberOrNull(v)
        return if (n != null) "<c r=\"$ref\" s=\"$style\"><v>${if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()}</v></c>"
        else "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
    }

    fun build(r: Report): ByteArray {
        val cols = r.columns.size
        val sb = StringBuilder()
        var row = 1
        fun line(values: List<String>, style: Int) {
            sb.append("<row r=\"$row\">")
            values.forEachIndexed { i, v -> sb.append(cell(colName(i) + row, v, style)) }
            sb.append("</row>"); row++
        }
        val merges = ArrayList<String>()
        fun titleRow(text: String, style: Int) {
            sb.append("<row r=\"$row\">").append(cell("A$row", text, style)).append("</row>")
            if (cols > 1) merges += "A$row:${colName(cols - 1)}$row"
            row++
        }
        titleRow(r.businessName + (if (r.ownerName.isNotBlank()) "  ·  ${r.ownerName}" else "") + "  ·  ${r.year}", 3)
        titleRow(r.title, 3)
        if (r.subtitle.isNotBlank()) titleRow(r.subtitle, 0)
        if (r.summary.isNotEmpty()) titleRow(r.summary.joinToString("   ·   ") { "${it.first}: ${it.second}" }, 0)
        row++
        line(r.columns, 1)
        r.rows.forEach { line(it, 2) }
        r.totals?.let { line(it, 1) }
        val widths = r.weights.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${(w * 11).coerceIn(6f, 60f)}\" customWidth=\"1\"/>" }.joinToString("")
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheetViews><sheetView workbookViewId=\"0\" rightToLeft=\"1\"/></sheetViews><cols>$widths</cols><sheetData>$sb</sheetData>" +
            (if (merges.isNotEmpty()) "<mergeCells count=\"${merges.size}\">" + merges.joinToString("") { "<mergeCell ref=\"$it\"/>" } + "</mergeCells>" else "") +
            "<pageSetup orientation=\"${if (r.landscape) "landscape" else "portrait"}\" paperSize=\"9\"/></worksheet>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.##\"/></numFmts>" +
            "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Arial\"/></font><font><b/><sz val=\"11\"/><name val=\"Arial\"/></font><font><b/><sz val=\"13\"/><name val=\"Arial\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"2\"><border/><border><left style=\"thin\"><color rgb=\"FF000000\"/></left><right style=\"thin\"><color rgb=\"FF000000\"/></right><top style=\"thin\"><color rgb=\"FF000000\"/></top><bottom style=\"thin\"><color rgb=\"FF000000\"/></bottom></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"4\">" +
            "<xf fontId=\"0\" borderId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"right\" readingOrder=\"2\"/></xf>" +
            "<xf fontId=\"1\" borderId=\"1\" numFmtId=\"164\" applyFont=\"1\" applyBorder=\"1\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\" readingOrder=\"2\"/></xf>" +
            "<xf fontId=\"0\" borderId=\"1\" numFmtId=\"164\" applyBorder=\"1\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\" readingOrder=\"2\"/></xf>" +
            "<xf fontId=\"2\" borderId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" readingOrder=\"2\"/></xf>" +
            "</cellXfs></styleSheet>"
        val files = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView rightToLeft=\"1\"/></bookViews><sheets><sheet name=\"${esc(r.year.ifBlank { "Sheet1" })}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet,
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }
}
