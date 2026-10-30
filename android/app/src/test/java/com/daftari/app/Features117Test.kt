/*
 * دفتري · test/Features117Test.kt
 * اختبارات 1.1.7: قرار الميكروفون (النموذج ثم الإذن)، تنزيل النموذج (الإكمال من حيث توقّف، بصمة sha256، الإلغاء، المساحة)،
 * كشف نهاية الكلام، الإرسال التلقائي في المحادثة بحالتيه، وفهم نصوص Whisper بالمحلّل، والإصدار.
 * 1.1.7 unit tests (pure JVM): the mic decision, the resumable sha256-checked model download (fake source), end of
 * speech, chat auto-send on/off, engine order (online ok / online fails → offline / offline only), the WAV sent online,
 * parsing of real whisper outputs (from the on-computer run), the catalog and the version.
 */
package com.daftari.app

import com.daftari.app.voice.ChatVoice
import com.daftari.app.voice.DownloadResult
import com.daftari.app.voice.EndOfSpeech
import com.daftari.app.voice.ModelDownloader
import com.daftari.app.voice.ModelState
import com.daftari.app.voice.RangeResponse
import com.daftari.app.voice.TakeState
import com.daftari.app.voice.VoiceEntryParser
import com.daftari.app.voice.VoiceKind
import com.daftari.app.voice.VoiceModel
import com.daftari.app.voice.VoiceModelStatus
import com.daftari.app.voice.VoiceProblem
import com.daftari.app.voice.VoiceStart
import com.daftari.app.voice.VoiceStarts
import com.daftari.app.voice.VoiceEngines
import com.daftari.app.voice.VoiceMode
import com.daftari.app.voice.VoiceVia
import com.daftari.app.voice.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

class Features117Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun micDecisionModelFirstThenPermission() {
        assertEquals(VoiceStart.OFFER_DOWNLOAD, VoiceStarts.decide(nativeOk = true, modelReady = false, downloading = false, micGranted = true))
        assertEquals(VoiceStart.DOWNLOADING, VoiceStarts.decide(true, false, true, false))
        assertEquals(VoiceStart.ASK_PERMISSION, VoiceStarts.decide(true, true, false, false))
        assertEquals(VoiceStart.LISTEN, VoiceStarts.decide(true, true, false, true))
        assertEquals(VoiceStart.LISTEN, VoiceStarts.decide(true, true, true, true)) // another model downloading: the ready one works
        assertEquals(VoiceStart.UNSUPPORTED, VoiceStarts.decide(false, true, false, true))
    }

    @Test fun catalogMatchesTheFilesOnHuggingFace() {
        assertEquals(VoiceModel.BASE, VoiceModel.DEFAULT)
        assertEquals("57 م.ب", VoiceModel.BASE.sizeText)
        assertEquals("31 م.ب", VoiceModel.TINY.sizeText)
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", VoiceModel.BASE.url)
        VoiceModel.entries.forEach { assertEquals(64, it.sha256.length); assertTrue(it.url.startsWith("https://")) }
        assertEquals(VoiceModel.TINY, VoiceModel.of("TINY")); assertEquals(VoiceModel.BASE, VoiceModel.of("junk")); assertEquals(VoiceModel.BASE, VoiceModel.of(null))
    }

    // ---- download (fake server that honours Range) ----
    private val payload = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    /** Serves [payload]; [cutAt] = the connection drops after that many bytes (absolute position) on the first request. */
    private class FakeServer(val data: ByteArray, var cutAt: Int? = null, val honoursRange: Boolean = true) : com.daftari.app.voice.RangeSource {
        val requests = mutableListOf<Long>()
        override fun open(url: String, from: Long): RangeResponse {
            requests += from
            val start = if (honoursRange) from.toInt() else 0
            val end = cutAt?.also { cutAt = null } ?: data.size
            val body: InputStream = object : InputStream() {
                var pos = start
                override fun read(): Int = if (pos >= end) { if (end < data.size) throw IOException("reset") else -1 } else data[pos++].toInt() and 0xff
            }
            return RangeResponse(if (honoursRange && from > 0) 206 else 200, body, data.size.toLong())
        }
    }

    @Test fun downloadResumesFromThePartFileAndChecksSha() {
        val server = FakeServer(payload, cutAt = 120_000)
        val part = tmp.root.resolve("m.bin.part"); val dest = tmp.root.resolve("m.bin")
        val d = ModelDownloader(server)
        val progress = mutableListOf<Int>()
        assertEquals(DownloadResult.NETWORK, d.download("https://x/m.bin", payload.size.toLong(), sha, part, dest, { progress += it }))
        assertEquals(120_000L, part.length()); assertFalse(dest.exists())
        assertEquals(DownloadResult.OK, d.download("https://x/m.bin", payload.size.toLong(), sha, part, dest, { progress += it }))
        assertEquals(listOf(0L, 120_000L), server.requests) // the second request resumed at the cut
        assertTrue(dest.readBytes().contentEquals(payload)); assertFalse(part.exists())
        assertEquals(100, progress.last()); assertEquals(progress.sorted(), progress)
        // already there: no request at all
        assertEquals(DownloadResult.OK, d.download("https://x/m.bin", payload.size.toLong(), sha, part, dest))
        assertEquals(2, server.requests.size)
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        val part = tmp.root.resolve("p.part").apply { writeBytes(payload.copyOf(50_000)) }
        val dest = tmp.root.resolve("p.bin")
        assertEquals(DownloadResult.OK, ModelDownloader(FakeServer(payload, honoursRange = false)).download("u", payload.size.toLong(), sha, part, dest))
        assertTrue(dest.readBytes().contentEquals(payload))
    }

    @Test fun wrongShaDeletesThePartAndNeverKeepsAFinalFile() {
        val part = tmp.root.resolve("s.part"); val dest = tmp.root.resolve("s.bin")
        val bad = "0".repeat(64)
        assertEquals(DownloadResult.SHA_MISMATCH, ModelDownloader(FakeServer(payload)).download("u", payload.size.toLong(), bad, part, dest))
        assertFalse(part.exists()); assertFalse(dest.exists())
        // a part longer than the file is thrown away and restarted
        part.writeBytes(ByteArray(payload.size + 10))
        assertEquals(DownloadResult.OK, ModelDownloader(FakeServer(payload)).download("u", payload.size.toLong(), sha, part, dest))
    }

    @Test fun cancelKeepsThePartForLater() {
        val part = tmp.root.resolve("c.part"); val dest = tmp.root.resolve("c.bin")
        var calls = 0
        assertEquals(DownloadResult.CANCELLED, ModelDownloader(FakeServer(payload)).download("u", payload.size.toLong(), sha, part, dest, cancelled = { ++calls > 2 }))
        assertTrue(part.length() in 1 until payload.size); assertFalse(dest.exists())
        assertEquals(DownloadResult.NETWORK, ModelDownloader { _, _ -> throw IOException("offline") }.download("u", payload.size.toLong(), sha, part, dest))
        assertEquals(DownloadResult.NETWORK, ModelDownloader { _, _ -> RangeResponse(404, ByteArrayInputStream(ByteArray(0)), null) }.download("u", payload.size.toLong(), sha, part, dest))
        assertEquals("bytes=120-", ModelDownloader.rangeHeader(120)); assertNull(ModelDownloader.rangeHeader(0))
        assertEquals(40, ModelDownloader.percent(40, 100)); assertEquals(0, ModelDownloader.percent(5, 0))
    }

    @Test fun modelLinesAndStatus() {
        assertNull(ModelState.Idle.line)
        assertTrue(ModelState.Downloading(VoiceModel.BASE, 40).line!!.contains("40%"))
        assertTrue(ModelState.Failed(DownloadResult.SHA_MISMATCH).line!!.contains("تالف"))
        assertTrue(ModelState.Failed(DownloadResult.NETWORK).line!!.contains("من حيث توقّف"))
        assertTrue(VoiceModelStatus.text(null, ModelState.Idle).startsWith("غير منزّل"))
        assertTrue(VoiceModelStatus.text(VoiceModel.BASE, ModelState.Idle).contains("57 م.ب"))
        assertTrue(VoiceModelStatus.text(VoiceModel.BASE, ModelState.Idle).contains("دون إنترنت"))
        assertTrue(VoiceModelStatus.text(null, ModelState.Downloading(VoiceModel.TINY, 7)).contains("7%"))
        assertTrue(VoiceProblem.MODEL_MISSING.offerDownload && VoiceProblem.MODEL_BROKEN.offerDownload)
        // Google is gone: nothing mentions it any more
        assertFalse(VoiceProblem.entries.any { it.body.contains("Google") || it.title.contains("Google") })
    }

    // ---- end of speech ----
    private fun tone(ms: Int, amp: Double): ShortArray = ShortArray(16 * ms) { (sin(2 * PI * 220 * it / 16_000.0) * amp * 32767).toInt().toShort() }
    private fun feedAll(e: EndOfSpeech, s: ShortArray): TakeState { var st = e.state; s.toList().chunked(320).forEach { st = e.feed(it.toShortArray()) }; return st }

    @Test fun endOfSpeechAfterQuietFollowingSpeech() {
        val e = EndOfSpeech()
        assertEquals(TakeState.WAITING, feedAll(e, tone(300, 0.001)))   // calibration on room noise
        assertEquals(TakeState.SPEAKING, feedAll(e, tone(1500, 0.3)))
        assertEquals(TakeState.SPEAKING, feedAll(e, tone(800, 0.001)))   // a short pause between words
        assertEquals(TakeState.SPEAKING, feedAll(e, tone(500, 0.3)))
        assertEquals(TakeState.DONE, feedAll(e, tone(1400, 0.001)))
        assertTrue(e.heardSpeech)
    }

    @Test fun noSpeechAndMaxLength() {
        val quiet = EndOfSpeech()
        assertEquals(TakeState.NO_SPEECH, feedAll(quiet, tone(7_100, 0.002)))
        assertFalse(quiet.heardSpeech)
        val long = EndOfSpeech()
        feedAll(long, tone(300, 0.001))
        assertEquals(TakeState.MAX_LENGTH, feedAll(long, tone(15_000, 0.3)))
        // a noisy shop raises the floor: noise alone is not speech
        val shop = EndOfSpeech()
        feedAll(shop, tone(300, 0.05))
        assertEquals(TakeState.WAITING, feedAll(shop, tone(1000, 0.06)))
        assertEquals(TakeState.SPEAKING, feedAll(shop, tone(500, 0.4)))
    }

    // ---- chat ----
    @Test fun chatAutoSendOffPutsTextInTheBox() {
        assertEquals("من الأكثر ديناً؟" to null, ChatVoice.apply("", " من الأكثر ديناً؟ ", autoSend = false))
        assertEquals("ملخص اليوم وأمس" to null, ChatVoice.apply("ملخص اليوم ", "وأمس", autoSend = false))
        assertEquals("كتبت" to null, ChatVoice.apply("كتبت", "  ", autoSend = false))
    }

    @Test fun chatAutoSendOnSendsAtOnceAndClearsTheBox() {
        assertEquals("" to "محمد سدد ألفين", ChatVoice.apply("", "محمد سدد ألفين", autoSend = true))
        assertEquals("" to "سجل على علي خمسة آلاف", ChatVoice.apply("سجل على علي", "خمسة آلاف", autoSend = true))
        assertEquals("" to null, ChatVoice.apply("", "", autoSend = true))
    }

    // ---- what the model actually wrote on the computer run (Google-TTS Arabic samples, base-q5_1) ----
    @Test fun parserUnderstandsWhisperOutputs() {
        val d = VoiceEntryParser.parse("سجل على محمد أحمد خمسة ألاف ريالاً", emptyList())
        assertEquals(VoiceKind.DEBT, d.kind)
        assertEquals("5000", d.amountText)
        val p = VoiceEntryParser.parse("علي صالح سدد الفين", emptyList())
        assertEquals(VoiceKind.PAYMENT, p.kind)
        assertEquals("2000", p.amountText)
        val q = VoiceEntryParser.parse("عليه ثلاثة ألاف", emptyList())
        assertEquals("3000", q.amountText)
    }

    // ---- online first, offline fallback ----
    @Test fun engineOrderOnlineThenOffline() {
        // connected, «تلقائي», server configured, voice not known to be off, model downloaded → online, then offline
        assertEquals(listOf(VoiceVia.ONLINE, VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.AUTO, networkUp = true, serverConfigured = true, serverVoice = null, offlineReady = true))
        assertEquals(listOf(VoiceVia.ONLINE, VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.AUTO, true, true, true, true))
        // online only (no model yet): online failure → the download offer
        assertEquals(listOf(VoiceVia.ONLINE), VoiceEngines.plan(VoiceMode.AUTO, true, true, null, false))
        assertEquals(VoiceProblem.MODEL_MISSING, VoiceEngines.noEngineProblem(nativeOk = true))
        assertEquals(VoiceProblem.UNSUPPORTED, VoiceEngines.noEngineProblem(nativeOk = false))
    }

    @Test fun engineOrderOfflineCases() {
        // no network / server says voice is off / no server URL → straight to the phone's model
        assertEquals(listOf(VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.AUTO, networkUp = false, serverConfigured = true, serverVoice = null, offlineReady = true))
        assertEquals(listOf(VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.AUTO, true, true, serverVoice = false, offlineReady = true))
        assertEquals(listOf(VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.AUTO, true, serverConfigured = false, serverVoice = null, offlineReady = true))
        // «دون إنترنت فقط» never sends audio, even when connected
        assertEquals(listOf(VoiceVia.OFFLINE), VoiceEngines.plan(VoiceMode.OFFLINE_ONLY, true, true, true, true))
        assertEquals(emptyList<VoiceVia>(), VoiceEngines.plan(VoiceMode.OFFLINE_ONLY, true, true, true, false))
        assertEquals(emptyList<VoiceVia>(), VoiceEngines.plan(VoiceMode.AUTO, false, true, null, false))
        assertEquals("عبر الإنترنت", VoiceVia.ONLINE.label); assertEquals("دون إنترنت", VoiceVia.OFFLINE.label)
    }

    @Test fun micDecisionWithOnlineVoice() {
        // no model, but online voice is possible → record now (no forced 57 MB download first)
        assertEquals(VoiceStart.ASK_PERMISSION, VoiceStarts.decide(nativeOk = true, modelReady = false, downloading = false, micGranted = false, online = true))
        assertEquals(VoiceStart.LISTEN, VoiceStarts.decide(true, false, false, true, online = true))
        assertEquals(VoiceStart.LISTEN, VoiceStarts.decide(nativeOk = false, modelReady = false, downloading = false, micGranted = true, online = true))
        assertEquals(VoiceStart.OFFER_DOWNLOAD, VoiceStarts.decide(true, false, false, true, online = false))
    }

    @Test fun wavHeaderIs16kMonoPcm16() {
        val pcm = ShortArray(16_000) { (it % 200 - 100).toShort() }
        val w = Wav.encode(pcm, 8_000)
        assertEquals(44 + 16_000, w.size)
        assertEquals("RIFF", String(w, 0, 4, Charsets.US_ASCII)); assertEquals("WAVE", String(w, 8, 4, Charsets.US_ASCII)); assertEquals("data", String(w, 36, 4, Charsets.US_ASCII))
        fun i32(at: Int) = (w[at].toInt() and 0xff) or ((w[at + 1].toInt() and 0xff) shl 8) or ((w[at + 2].toInt() and 0xff) shl 16) or ((w[at + 3].toInt() and 0xff) shl 24)
        assertEquals(16_000, i32(24)); assertEquals(32_000, i32(28)); assertEquals(16_000, i32(40))
        assertEquals(pcm[1], ((w[46].toInt() and 0xff) or (w[47].toInt() shl 8)).toShort())
        assertTrue(Wav.encode(ShortArray(16_000 * 15)).size < 1024 * 1024) // a full 15 s take fits the server's 1 MB
    }

    @Test fun versionIs117() { assertEquals(10, BuildConfig.VERSION_CODE); assertEquals("1.1.7", BuildConfig.VERSION_NAME) }
}
