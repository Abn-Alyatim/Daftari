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

    /**
     * 1.1.3: receipt photos (camera or gallery) are stored as one small JPEG: long side ≤ 1600 px, quality 80,
     * turned upright from the EXIF orientation (camera photos are often saved sideways). Returns false when
     * the source is not a readable image.
     */
    fun compressReceipt(open: () -> java.io.InputStream?, dest: File, maxSide: Int = com.daftari.app.core.ReceiptCapture.MAX_SIDE,
                        quality: Int = com.daftari.app.core.ReceiptCapture.QUALITY): Boolean {
        // one read into memory (a phone photo is a few MB), then decoded at a power-of-two step
        val bytes = open()?.use { it.readBytes() } ?: return false
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val sample = com.daftari.app.core.ReceiptCapture.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val degrees = runCatching { android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).rotationDegrees() }.getOrDefault(0)
        val (tw, th) = com.daftari.app.core.ReceiptCapture.targetSize(raw.width, raw.height, maxSide)
        var bmp = if (tw != raw.width || th != raw.height) Bitmap.createScaledBitmap(raw, tw, th, true) else raw
        if (degrees != 0) {
            val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        dest.parentFile?.mkdirs()
        dest.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)) return false }
        return dest.length() > 0
    }

    private fun android.media.ExifInterface.rotationDegrees(): Int = when (getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90, android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
        android.media.ExifInterface.ORIENTATION_ROTATE_270, android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** Small preview for the payment sheet (the receipt thumbnail). */
    fun thumbnail(path: String, maxSide: Int = 256): Bitmap? = runCatching {
        val bytes = File(path).readBytes()
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = com.daftari.app.core.ReceiptCapture.sampleSize(b.outWidth, b.outHeight, maxSide) })
    }.getOrNull()

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
