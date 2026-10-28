package com.daftari.app.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A minimal, valid .docx (Office Open XML) with a right-to-left table. No library needed; opens in Word / Google Docs / WPS. */
object DocxWriter {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun run(text: String, bold: Boolean = false, size: Int = 20, color: String? = null) =
        "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>" +
            (if (bold) "<w:b/><w:bCs/>" else "") + (color?.let { "<w:color w:val=\"$it\"/>" } ?: "") +
            "<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/><w:rtl/></w:rPr><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"

    private fun para(text: String, bold: Boolean = false, size: Int = 20, align: String = "right", color: String? = null) =
        "<w:p><w:pPr><w:bidi/>" + (if (align == "center") "<w:jc w:val=\"center\"/>" else "") + "<w:spacing w:after=\"60\"/></w:pPr>${run(text, bold, size, color)}</w:p>"

    private fun cell(text: String, widthTw: Int, fill: String?, bold: Boolean, color: String?) =
        "<w:tc><w:tcPr><w:tcW w:w=\"$widthTw\" w:type=\"dxa\"/>" + (fill?.let { "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$it\"/>" } ?: "") +
            "<w:vAlign w:val=\"center\"/></w:tcPr><w:p><w:pPr><w:bidi/><w:jc w:val=\"center\"/><w:spacing w:before=\"40\" w:after=\"40\"/></w:pPr>${run(text, bold, 18, color)}</w:p></w:tc>"

    fun build(r: Report): ByteArray {
        val total = if (r.landscape) 15300 else 10400 // usable twips (A4 portrait/landscape, 0.75in margins)
        val sum = r.weights.sum()
        val widths = r.weights.map { (it / sum * total).toInt() }
        val sb = StringBuilder()
        sb.append(para(r.businessName + (if (r.ownerName.isNotBlank()) " — المالك/المؤجر: " + r.ownerName else "") + " — سنة " + r.year, true, 22))
        sb.append(para(r.title, true, 32, "center"))
        sb.append(para(r.subtitle, false, 18, "center"))
        if (r.summary.isNotEmpty()) sb.append(para(r.summary.joinToString("     ·     ") { "${it.first}: ${it.second}" }, false, 20, "center"))
        sb.append("<w:tbl><w:tblPr><w:bidiVisual/><w:tblW w:w=\"$total\" w:type=\"dxa\"/><w:jc w:val=\"center\"/>")
        sb.append("<w:tblBorders>")
        for (b in listOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$b w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>")
        sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
        widths.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
        sb.append("</w:tblGrid>")
        sb.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>")
        r.columns.forEachIndexed { i, c -> sb.append(cell(c, widths[i], null, true, null)) }
        sb.append("</w:tr>")
        r.rows.forEachIndexed { ri, row ->
            sb.append("<w:tr>")
            row.forEachIndexed { i, v ->
                sb.append(cell(v, widths.getOrElse(i) { 1000 }, null, false, null))
            }
            sb.append("</w:tr>")
        }
        r.totals?.let { t ->
            sb.append("<w:tr>")
            t.forEachIndexed { i, v -> sb.append(cell(v, widths.getOrElse(i) { 1000 }, null, false, null)) }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        sb.append(para("", size = 12))
        sb.append(para("التوقيع: ..............................", false, 18))
        sb.append(para(r.footer, false, 16, "center"))

        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + sb +
            "<w:sectPr>" + (if (r.landscape) "<w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>" else "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>") + "<w:pgMar w:top=\"1000\" w:right=\"750\" w:bottom=\"1000\" w:left=\"750\" w:header=\"500\" w:footer=\"500\" w:gutter=\"0\"/><w:bidi/></w:sectPr></w:body></w:document>"
        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"
        val core = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>${esc(r.title)}</dc:title><dc:creator>${esc(r.footer)}</dc:creator></cp:coreProperties>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("docProps/core.xml", core)
            put("word/document.xml", document)
        }
        return bos.toByteArray()
    }
}
