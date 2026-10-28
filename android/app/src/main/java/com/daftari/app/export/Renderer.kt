package com.daftari.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.daftari.app.R
import java.io.File
import java.io.FileOutputStream

/**
 * Draws a [Report] on a Canvas: one code path for PDF pages (A4, paginated, header repeated) and for a single tall PNG.
 * All text goes through StaticLayout with an RTL direction so Arabic is shaped and ordered correctly.
 */
class ReportRenderer(context: Context) {
    private val regular: Typeface = ResourcesCompat.getFont(context, R.font.noto_sans_arabic_regular) ?: Typeface.DEFAULT
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.noto_sans_arabic_bold) ?: Typeface.DEFAULT_BOLD

    // Official-paper look (Madyan, Oct 28): black text on white, thin black borders, bold header row only, no fills, no logo.
    private val ink = Color.BLACK
    private val muted = Color.BLACK

    private fun paint(size: Float, b: Boolean = false, color: Int = Color.parseColor("#111111")) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; typeface = if (b) bold else regular; this.color = color
    }

    private fun layout(text: String, p: TextPaint, width: Int, align: Layout.Alignment, maxLines: Int = 2): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun drawText(c: Canvas, text: String, p: TextPaint, left: Float, top: Float, width: Float, align: Layout.Alignment, maxLines: Int = 2): Float {
        val l = layout(text, p, width.toInt(), align, maxLines)
        c.save(); c.translate(left, top); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    private class Geo(val pageW: Float, val margin: Float, val scale: Float) {
        val contentW get() = pageW - 2 * margin
    }

    /** Header block; returns the y after it. */
    private fun header(c: Canvas, r: Report, g: Geo, y0: Float, first: Boolean): Float {
        val s = g.scale
        var y = y0
        val right = g.pageW - g.margin
        // top row: business (right), logo (center), year (left)
        val hp = paint(11f * s, true)
        val sp = paint(8.5f * s, false, muted)
        drawText(c, r.businessName, hp, g.margin, y, g.contentW * 0.42f, Layout.Alignment.ALIGN_NORMAL, 1)
        if (r.ownerName.isNotBlank()) drawText(c, "المالك / المؤجر: ${r.ownerName}", sp, g.margin, y + 15f * s, g.contentW * 0.42f, Layout.Alignment.ALIGN_NORMAL, 1)
        drawText(c, r.year, hp, right - g.contentW * 0.3f, y, g.contentW * 0.3f, Layout.Alignment.ALIGN_OPPOSITE, 1)
        drawText(c, "السنة", sp, right - g.contentW * 0.3f, y + 15f * s, g.contentW * 0.3f, Layout.Alignment.ALIGN_OPPOSITE, 1)
        y += 38f * s
        if (first) {
            y += drawText(c, r.title, paint(15f * s, true, ink), g.margin, y, g.contentW, Layout.Alignment.ALIGN_CENTER, 2) + 3f * s
            y += drawText(c, r.subtitle, paint(8.5f * s, false, muted), g.margin, y, g.contentW, Layout.Alignment.ALIGN_CENTER, 2) + 6f * s
            if (r.summary.isNotEmpty()) {
                val line = r.summary.joinToString("      ·      ") { "${it.first}: ${it.second}" }
                y += drawText(c, line, paint(9.5f * s), g.margin, y, g.contentW, Layout.Alignment.ALIGN_CENTER, 2) + 8f * s
            }
        } else {
            y += drawText(c, r.title + " (تابع)", paint(10f * s, true, ink), g.margin, y, g.contentW, Layout.Alignment.ALIGN_CENTER, 1) + 6f * s
        }
        return y
    }

    private fun colXs(r: Report, g: Geo): List<Pair<Float, Float>> {
        // right→left: column 0 at the right edge
        val sum = r.weights.sum()
        var x = g.pageW - g.margin
        return r.weights.map { w ->
            val cw = w / sum * g.contentW
            val left = x - cw
            val res = left to cw
            x = left
            res
        }
    }

    private fun row(c: Canvas, r: Report, g: Geo, y: Float, values: List<String>, kind: Int, index: Int): Float {
        // kind 0 header, 1 body, 2 totals
        val s = g.scale
        val cols = colXs(r, g)
        val p = if (kind == 0) paint(8.6f * s, true, ink) else paint(8.6f * s, false, ink)
        val pad = 4f * s
        val h = values.indices.maxOfOrNull { i ->
            layout(values[i], p, (cols.getOrElse(i) { 0f to 40f }.second - 2 * pad).toInt(), Layout.Alignment.ALIGN_CENTER).height.toFloat()
        }?.plus(2 * 5f * s) ?: (20f * s)
        val right = g.pageW - g.margin
        val gp = Paint().apply { color = ink; strokeWidth = 0.5f * s; style = Paint.Style.STROKE }
        c.drawRect(g.margin, y, right, y + h, gp)
        cols.forEachIndexed { i, (left, w) ->
            if (i > 0) c.drawLine(left + w, y, left + w, y + h, gp)
            val v = values.getOrElse(i) { "" }
            val l = layout(v, p, (w - 2 * pad).toInt(), Layout.Alignment.ALIGN_CENTER)
            c.save(); c.translate(left + pad, y + (h - l.height) / 2f); l.draw(c); c.restore()
        }
        if (kind == 2) c.drawLine(g.margin, y + 1.2f * s, right, y + 1.2f * s, gp)
        return h
    }

    private fun footer(c: Canvas, r: Report, g: Geo, y: Float, page: Int, pages: Int) {
        val s = g.scale
        val fp = paint(7.2f * s, false, muted)
        drawText(c, "التوقيع: ........................", fp, g.pageW / 2, y, g.contentW / 2, Layout.Alignment.ALIGN_NORMAL, 1)
        drawText(c, (if (pages > 0) "صفحة $page من $pages · " else "") + r.footer, fp, g.margin, y, g.contentW / 2, Layout.Alignment.ALIGN_OPPOSITE, 1)
    }

    /** Paginated A4 PDF. */
    fun pdf(r: Report, out: File): File {
        val w = if (r.landscape) 842 else 595; val h = if (r.landscape) 595 else 842
        val g = Geo(w.toFloat(), 32f, 1f)
        // first pass: compute pagination by measuring with a scratch canvas
        val scratch = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val pagesRows = ArrayList<List<Int>>()
        run {
            var y = header(scratch, r, g, 30f, true) + row(scratch, r, g, 0f, r.columns, 0, 0)
            var cur = ArrayList<Int>()
            r.rows.forEachIndexed { i, rowV ->
                val rh = row(scratch, r, g, 0f, rowV, 1, i)
                if (y + rh > h - 60f && cur.isNotEmpty()) {
                    pagesRows += cur; cur = ArrayList()
                    y = header(scratch, r, g, 30f, false) + row(scratch, r, g, 0f, r.columns, 0, 0)
                }
                cur += i; y += rh
            }
            pagesRows += cur
        }
        val doc = PdfDocument()
        pagesRows.forEachIndexed { pi, idxs ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pi + 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)
            var y = header(c, r, g, 30f, pi == 0)
            y += row(c, r, g, y, r.columns, 0, 0)
            idxs.forEach { i -> y += row(c, r, g, y, r.rows[i], 1, i) }
            if (pi == pagesRows.lastIndex) r.totals?.let { y += row(c, r, g, y, it, 2, 0) }
            footer(c, r, g, h - 34f, pi + 1, pagesRows.size)
            doc.finishPage(page)
        }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** One tall PNG (1240 px wide), handy for WhatsApp. Very long tables are capped at [maxRows] rows. */
    fun bitmap(r: Report, maxRows: Int = 400): Bitmap {
        val g = if (r.landscape) Geo(1754f, 70f, 2.08f) else Geo(1240f, 60f, 2.08f)
        val rows = r.rows.take(maxRows)
        val scratch = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        var total = header(scratch, r, g, 50f, true) + row(scratch, r, g, 0f, r.columns, 0, 0)
        rows.forEachIndexed { i, v -> total += row(scratch, r, g, 0f, v, 1, i) }
        r.totals?.let { total += row(scratch, r, g, 0f, it, 2, 0) }
        total += 120f
        val bmp = Bitmap.createBitmap(g.pageW.toInt(), total.toInt().coerceAtMost(30000), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = header(c, r, g, 50f, true)
        y += row(c, r, g, y, r.columns, 0, 0)
        rows.forEachIndexed { i, v -> y += row(c, r, g, y, v, 1, i) }
        r.totals?.let { y += row(c, r, g, y, it, 2, 0) }
        footer(c, r, g, y + 30f, 0, 0)
        return bmp
    }

    fun png(r: Report, out: File): File {
        val b = bitmap(r)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return out
    }
}
