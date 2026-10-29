package com.daftari.app.core

import java.io.File

/**
 * 1.1.3: receipt photo from the camera or the gallery.
 *
 * Both sources end up as one small private JPEG in cacheDir/receipts/ while the payment sheet is open
 * (the sheet keeps only its path, which survives rotation and process death). Saving the payment copies it
 * into files/attachments tied to that payment; closing the sheet or removing the photo deletes it.
 * The camera writes into the same folder through our FileProvider, so no storage permission is needed.
 */
object ReceiptCapture {
    const val DIR = "receipts"
    const val MAX_SIDE = 1600
    const val QUALITY = 80
    private const val STALE_MS = 24L * 60 * 60 * 1000

    fun dir(cacheDir: File): File = File(cacheDir, DIR).apply { mkdirs() }

    /** A fresh empty target for the camera app («التقاط صورة»). */
    fun newCameraFile(cacheDir: File, now: Long = System.currentTimeMillis()): File = File(dir(cacheDir), "cam_$now.jpg")

    /** A fresh target for the shrunk copy of a photo (from the camera or the gallery). */
    fun newReceiptFile(cacheDir: File, now: Long = System.currentTimeMillis()): File = File(dir(cacheDir), "rc_$now.jpg")

    /**
     * Result of TakePicture for the pending camera file. Returns the file when a photo was really taken;
     * otherwise (cancelled, failed, empty) deletes whatever the camera left behind and returns null.
     */
    fun settle(success: Boolean, pendingPath: String?): File? {
        val f = pendingPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (success && f.isFile && f.length() > 0) return f
        f.delete()
        return null
    }

    /** Deletes one of our temporary receipt files (never anything outside cacheDir/receipts). */
    fun discard(cacheDir: File, path: String?) {
        val f = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (isOurs(cacheDir, f)) f.delete()
    }

    fun isOurs(cacheDir: File, f: File): Boolean =
        runCatching { f.canonicalFile.parentFile == File(cacheDir, DIR).canonicalFile }.getOrDefault(false)

    /** Removes leftovers older than a day (a sheet that was never closed, a crash), keeping [keep]. */
    fun sweep(cacheDir: File, keep: Collection<String?> = emptyList(), now: Long = System.currentTimeMillis()): Int {
        val keepSet = keep.filterNotNull().map { File(it).name }.toSet()
        val files = File(cacheDir, DIR).listFiles() ?: return 0
        var n = 0
        for (f in files) if (f.isFile && f.name !in keepSet && now - f.lastModified() > STALE_MS && f.delete()) n++
        return n
    }

    /** Power-of-two decode step so a 12 MP photo is never fully decoded in memory. */
    fun sampleSize(w: Int, h: Int, maxSide: Int = MAX_SIDE): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    /** Final size with the long side at most [maxSide], aspect kept. */
    fun targetSize(w: Int, h: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val m = maxOf(w, h)
        if (m <= maxSide || m <= 0) return w to h
        val k = maxSide / m.toDouble()
        return maxOf(1, Math.round(w * k).toInt()) to maxOf(1, Math.round(h * k).toInt())
    }
}
