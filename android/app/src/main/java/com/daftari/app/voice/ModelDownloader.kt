/*
 * دفتري · voice/ModelDownloader.kt
 * تنزيل نموذج الصوت مرة واحدة: يكمل من حيث توقّف (Range)، ويعرض النسبة، ويتحقق من بصمة sha256 قبل اعتماد الملف.
 * One-time model download: resumable (HTTP Range from the .part file's length; a server that ignores the range restarts
 * from zero), progress in percent, sha256 checked before the file gets its final name (a mismatch deletes the part so the
 * next try starts clean), cancellable, and it never leaves a half file under the final name. The HTTP side is behind
 * [RangeSource] so the logic is unit-tested on the JVM with a fake source; [HttpRangeSource] is the real one.
 */
package com.daftari.app.voice

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One answer of the server: HTTP status, the body, and the full size when it says so. */
class RangeResponse(val code: Int, val body: InputStream?, val totalBytes: Long?)

fun interface RangeSource {
    /** GET [url] from byte [from] (0 = the whole file). */
    @Throws(IOException::class)
    fun open(url: String, from: Long): RangeResponse
}

enum class DownloadResult { OK, SHA_MISMATCH, NETWORK, NO_SPACE, CANCELLED }

class ModelDownloader(private val source: RangeSource) {
    /**
     * Downloads [url] into [part] (resuming) and, when its sha256 equals [sha256], renames it to [dest].
     * [onProgress] gets 0…100; [cancelled] is polled between chunks.
     */
    fun download(
        url: String, expectedBytes: Long, sha256: String, part: File, dest: File,
        onProgress: (Int) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): DownloadResult {
        if (dest.isFile && dest.length() == expectedBytes) return DownloadResult.OK
        part.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        if (have > expectedBytes) { part.delete(); have = 0 }
        if (have < expectedBytes) {
            val free = part.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (free in 0 until (expectedBytes - have) + SPARE_BYTES) return DownloadResult.NO_SPACE
            val r = try { source.open(url, have) } catch (_: IOException) { return DownloadResult.NETWORK }
            val append = when (r.code) {
                206 -> true
                200 -> { have = 0; false } // the server sent the whole file: start over
                416 -> true                // range past the end: the part may already be complete — the hash decides
                else -> { r.body?.close(); return DownloadResult.NETWORK }
            }
            if (r.code != 416) {
                val body = r.body ?: return DownloadResult.NETWORK
                try {
                    FileOutputStream(part, append).use { out ->
                        body.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var lastPct = -1
                            while (true) {
                                if (cancelled()) return DownloadResult.CANCELLED
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                                val pct = percent(have, expectedBytes)
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                } catch (e: IOException) {
                    return if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) DownloadResult.NO_SPACE else DownloadResult.NETWORK
                }
            } else r.body?.close()
        }
        if (part.length() < expectedBytes) return DownloadResult.NETWORK // connection ended early: resume next time
        if (part.length() != expectedBytes || !sha256Of(part).equals(sha256, ignoreCase = true)) { part.delete(); return DownloadResult.SHA_MISMATCH }
        dest.delete()
        if (!part.renameTo(dest)) return DownloadResult.NO_SPACE
        onProgress(100)
        return DownloadResult.OK
    }

    companion object {
        /** room kept free beyond the model itself */
        const val SPARE_BYTES = 20L * 1024 * 1024

        fun percent(have: Long, total: Long): Int = if (total <= 0) 0 else ((have * 100) / total).toInt().coerceIn(0, 100)

        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** The «Range» header for resuming at [from] (null = a plain GET). */
        fun rangeHeader(from: Long): String? = if (from > 0) "bytes=$from-" else null
    }
}

/** The real source: HttpURLConnection, https only, redirects followed (Hugging Face sends the file from its CDN). */
object HttpRangeSource : RangeSource {
    override fun open(url: String, from: Long): RangeResponse {
        var target = URL(url)
        repeat(5) {
            val c = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Daftari-Android")
                ModelDownloader.rangeHeader(from)?.let { setRequestProperty("Range", it) }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                c.disconnect()
                target = URL(target, loc)
                if (target.protocol != "https") throw IOException("not https")
                return@repeat
            }
            val total = when (code) {
                206 -> c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                200 -> c.contentLengthLong.takeIf { it > 0 }
                else -> null
            }
            return RangeResponse(code, if (code == 200 || code == 206) c.inputStream else null, total)
        }
        throw IOException("too many redirects")
    }
}
