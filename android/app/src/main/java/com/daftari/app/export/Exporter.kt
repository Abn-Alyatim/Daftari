package com.daftari.app.export

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val ext: String, val mime: String, val labelAr: String) {
    PDF("pdf", "application/pdf", "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel"),
    PNG("png", "image/png", "صورة"),
}

/** Writes a report to the cache (shared through FileProvider). */
class Exporter(private val context: Context) {
    suspend fun export(r: Report, f: ExportFormat): File = withContext(Dispatchers.Default) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val out = File(dir, "${r.fileStem}.${f.ext}")
        when (f) {
            ExportFormat.PDF -> ReportRenderer(context).pdf(r, out)
            ExportFormat.PNG -> ReportRenderer(context).png(r, out)
            ExportFormat.DOCX -> out.writeBytes(DocxWriter.build(r))
            ExportFormat.XLSX -> out.writeBytes(XlsxWriter.build(r))
        }
        out
    }
}
