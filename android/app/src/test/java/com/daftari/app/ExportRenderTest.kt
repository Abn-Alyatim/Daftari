package com.daftari.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.daftari.app.core.Currency
import com.daftari.app.data.Demo
import com.daftari.app.domain.Ledger
import com.daftari.app.domain.Role
import com.daftari.app.export.DocxWriter
import com.daftari.app.export.ReportBuilder
import com.daftari.app.export.ReportRenderer
import com.daftari.app.export.XlsxWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Renders the real export files (PNG through the same renderer as the PDF, plus DOCX/XLSX) to build/outputs/exports for review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExportRenderTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val out = File("build/outputs/exports").apply { mkdirs() }

    private fun reports(role: Role): List<com.daftari.app.export.Report> {
        val seed = Demo.forRole(role, today)
        val parties = seed.parties.mapIndexed { i, p -> p.copy(id = i + 1L) }
        val entries = seed.entries(parties.map { it.id })
        val by = entries.groupBy { it.partyId }
        val sums = parties.map { Ledger.summarize(it, by[it.id].orEmpty(), today) }
        val (b, o) = Demo.businessFor(role)
        val all = ReportBuilder.allParties(sums, entries, role, b, o, Currency.YER, today.withDayOfMonth(1), today)
        val one = ReportBuilder.partyStatement(Ledger.statement(parties[2], entries, today.minusMonths(1).withDayOfMonth(1), today), role, b, o, Currency.YER)
        return listOf(all, one)
    }

    @Test fun renderAll() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (role in Role.entries) {
            reports(role).forEachIndexed { i, r ->
                val tag = "${role.name.lowercase()}_${if (i == 0) "all" else "one"}"
                val bmp = ReportRenderer(ctx).bitmap(r, 60)
                File(out, "$tag.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(out, "$tag.docx").writeBytes(DocxWriter.build(r))
                File(out, "$tag.xlsx").writeBytes(XlsxWriter.build(r))
                assertTrue(bmp.width > 500)
            }
        }
    }
}
