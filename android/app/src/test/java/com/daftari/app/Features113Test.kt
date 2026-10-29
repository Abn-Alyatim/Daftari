package com.daftari.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.daftari.app.core.ReceiptCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 1.1.3: «التقاط صورة» beside «اختيار من المعرض» — the pending camera file, cancel cleanup, and the shared shrink step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Features113Test {
    @get:Rule val tmp = TemporaryFolder()
    private val cache get() = tmp.root

    @Test fun cameraTargetLivesInPrivateCacheFolder() {
        val f = ReceiptCapture.newCameraFile(cache, 123)
        assertEquals(File(cache, "receipts"), f.parentFile)
        assertEquals("cam_123.jpg", f.name)
        assertTrue(f.parentFile!!.isDirectory)
        assertFalse("nothing is written before the camera app writes", f.exists())
    }

    @Test fun cancelLeavesNoFileBehind() {
        val f = ReceiptCapture.newCameraFile(cache).apply { writeBytes(ByteArray(0)) }
        assertNull(ReceiptCapture.settle(false, f.path))
        assertFalse(f.exists())
    }

    @Test fun cancelWithPartialFileStillDeletesIt() {
        val f = ReceiptCapture.newCameraFile(cache).apply { writeBytes(ByteArray(10) { 1 }) }
        assertNull(ReceiptCapture.settle(false, f.path))
        assertFalse(f.exists())
    }

    @Test fun okButEmptyFileCountsAsCancelled() {
        val f = ReceiptCapture.newCameraFile(cache).apply { writeBytes(ByteArray(0)) }
        assertNull(ReceiptCapture.settle(true, f.path))
        assertFalse(f.exists())
    }

    @Test fun okButMissingFileIsNull() {
        assertNull(ReceiptCapture.settle(true, File(cache, "receipts/none.jpg").path))
        assertNull(ReceiptCapture.settle(true, null))
        assertNull(ReceiptCapture.settle(false, ""))
    }

    @Test fun takenPhotoIsReturned() {
        val f = ReceiptCapture.newCameraFile(cache).apply { writeBytes(ByteArray(50) { 7 }) }
        assertEquals(f, ReceiptCapture.settle(true, f.path))
        assertTrue(f.exists())
    }

    @Test fun pendingPathSurvivesAsPlainString() {
        // what rememberSaveable keeps across rotation / process death is only the path string
        val f = ReceiptCapture.newCameraFile(cache).apply { writeBytes(ByteArray(5) { 1 }) }
        val restored = String(f.path.toCharArray())
        assertEquals(f.canonicalFile, ReceiptCapture.settle(true, restored)!!.canonicalFile)
    }

    @Test fun discardOnlyTouchesOurFolder() {
        val ours = ReceiptCapture.newReceiptFile(cache, 1).apply { writeText("x") }
        val other = File(cache, "attachments/p1.jpg").apply { parentFile!!.mkdirs(); writeText("keep") }
        ReceiptCapture.discard(cache, other.path)
        ReceiptCapture.discard(cache, File(cache, "receipts/../attachments/p1.jpg").path)
        assertTrue(other.exists())
        ReceiptCapture.discard(cache, ours.path)
        assertFalse(ours.exists())
        ReceiptCapture.discard(cache, null) // no crash
    }

    @Test fun sweepRemovesOnlyStaleLeftovers() {
        val now = 10L * 24 * 60 * 60 * 1000
        val old = ReceiptCapture.newReceiptFile(cache, 1).apply { writeText("o"); setLastModified(now - 3L * 24 * 60 * 60 * 1000) }
        val keep = ReceiptCapture.newCameraFile(cache, 2).apply { writeText("k"); setLastModified(now - 3L * 24 * 60 * 60 * 1000) }
        val fresh = ReceiptCapture.newReceiptFile(cache, 3).apply { writeText("f"); setLastModified(now - 60_000) }
        assertEquals(1, ReceiptCapture.sweep(cache, listOf(keep.path, null), now))
        assertFalse(old.exists()); assertTrue(keep.exists()); assertTrue(fresh.exists())
    }

    @Test fun sizing() {
        assertEquals(1600 to 1200, ReceiptCapture.targetSize(4000, 3000))
        assertEquals(900 to 1600, ReceiptCapture.targetSize(2700, 4800))
        assertEquals(800 to 600, ReceiptCapture.targetSize(800, 600))
        assertEquals(2, ReceiptCapture.sampleSize(4000, 3000))   // decoded at 2000 px, then scaled to 1600
        assertEquals(1, ReceiptCapture.sampleSize(3000, 2000))
        assertEquals(4, ReceiptCapture.sampleSize(8000, 6000))
        assertTrue(4000 / ReceiptCapture.sampleSize(4000, 3000) >= 1600)
    }

    private fun photo(w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (x in 0 until w step 7) for (y in 0 until h step 97) b.setPixel(x, y, Color.BLACK)
        return File(cache, "src_${w}x$h.png").apply { outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun bigPhotoIsShrunkToSmallJpeg() {
        val src = photo(3200, 2400)
        val out = ReceiptCapture.newReceiptFile(cache)
        assertTrue(ImageUtil.compressReceipt({ src.inputStream() }, out))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        out.readBytes().let { BitmapFactory.decodeByteArray(it, 0, it.size, o) }
        assertEquals(1600, maxOf(o.outWidth, o.outHeight))
        assertEquals(1200, minOf(o.outWidth, o.outHeight))
        assertEquals("image/jpeg", o.outMimeType)
        assertTrue("small: ${out.length()}", out.length() in 1..600_000)
    }

    @Test fun smallPhotoKeepsItsSize() {
        val src = photo(640, 480)
        val out = ReceiptCapture.newReceiptFile(cache)
        assertTrue(ImageUtil.compressReceipt({ src.inputStream() }, out))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        out.readBytes().let { BitmapFactory.decodeByteArray(it, 0, it.size, o) }
        assertEquals(640, o.outWidth); assertEquals(480, o.outHeight)
        assertNotNull(ImageUtil.thumbnail(out.path))
    }

    @Test fun notAnImageIsRefused() {
        val src = File(cache, "x.txt").apply { writeText("ليس صورة") }
        val out = ReceiptCapture.newReceiptFile(cache)
        assertFalse(ImageUtil.compressReceipt({ src.inputStream() }, out))
        assertFalse(ImageUtil.compressReceipt({ null }, out))
    }

    @Test fun cameraSourceIsAPrivateFileProviderPath() {
        val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        val xml = File(main, "res/xml/file_paths.xml").readText()
        assertTrue(xml.contains("<cache-path name=\"receipts\" path=\"receipts/\""))
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertFalse("TakePicture needs no CAMERA permission unless one is declared", manifest.contains("android.permission.CAMERA"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"))
    }
}
