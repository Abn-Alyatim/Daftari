package com.daftari.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/** Shrinks photos/PDF pages before the smart import sends them (bad internet in Yemen: keep requests small). */
object ImageUtil {
    fun jpegBase64(bytes: ByteArray, maxSide: Int = 1600): String {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        return encode(scale(bmp, maxSide))
    }

    fun pdfPagesBase64(ctx: Context, bytes: ByteArray, maxPages: Int): List<String> {
        val f = File(ctx.cacheDir, "import.pdf").apply { writeBytes(bytes) }
        val out = ArrayList<String>()
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                for (i in 0 until minOf(r.pageCount, maxPages)) {
                    r.openPage(i).use { page ->
                        val w = 1400; val h = (w * page.height / page.width.toFloat()).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out += encode(bmp)
                    }
                }
            }
        }
        f.delete()
        return out
    }

    private fun scale(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val k = maxSide / m.toFloat()
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    private fun encode(b: Bitmap): String {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
