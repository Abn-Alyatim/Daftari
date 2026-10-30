package com.daftari.app

import com.daftari.app.core.Subs
import com.daftari.app.data.ApiException
import com.daftari.app.data.AppSettings
import com.daftari.app.data.DaftariApi
import com.daftari.app.data.PayMethod
import com.daftari.app.data.SubConfig
import com.daftari.app.data.SubPlan
import com.daftari.app.data.SubRequestDto
import com.daftari.app.ui.subs.SubFlowState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

object Samples115 {
    private fun m(id: String, kind: String, label: String, other: Boolean = false) = PayMethod(id, kind, label, "780647484", "مدين محافظ حميد اليتيم", other)
    val config = SubConfig(
        plans = listOf(SubPlan("pro_month", "برو شهري", 30, 3000.0), SubPlan("pro_year", "برو سنوي", 365, 30000.0)),
        methods = listOf(m("kuraimi", "transfer", "الكريمي"), m("hazmi", "transfer", "الحزمي"), m("najm", "transfer", "النجم"), m("transfer_other", "transfer", "أخرى", true),
            m("jaib", "wallet", "جيب"), m("jawali", "wallet", "جوالي"), m("onecash", "wallet", "ون كاش"), m("floosak", "wallet", "فلوسك"), m("hasib", "wallet", "حاسب"), m("wallet_other", "wallet", "أخرى", true)),
    )
    const val T0 = 1_793_350_000_000L
    val approved = SubRequestDto("sr_1", "pro_month", "برو شهري", 30, 3000.0, "YER", "jaib", "جيب", "wallet", "780647484", "556677", "مدين محافظ حميد اليتيم",
        "approved", "", T0, T0 + 3_600_000, T0 + 3_600_000, T0 + 3_600_000 + 30 * 86_400_000L)
    val pending = approved.copy(status = "pending", decidedAt = 0, startsAt = 0, endsAt = 0)
    val rejected = approved.copy(status = "rejected", rejectReason = "لم يصل المبلغ إلى المحفظة، تأكد من الرقم ثم أعد الإرسال.")
}

/** 1.1.5 subscriptions: validation, texts, badge / expiry, and the server's error shape. */
class Features115Test {
    @Test fun fourPartName() {
        assertTrue(Subs.isFourPartName("مدين محافظ حميد اليتيم"))
        assertTrue(Subs.isFourPartName("  علي  محمد صالح أحمد  "))
        assertFalse(Subs.isFourPartName("مدين محافظ اليتيم"))
        assertFalse(Subs.isFourPartName("a b c d"))
    }

    @Test fun transactionNumber() {
        assertEquals("123456", Subs.cleanTxn("١٢٣ ٤٥٦"))
        assertTrue(Subs.isTxnValid("JB-55120"))
        assertFalse(Subs.isTxnValid("12"))
        assertFalse(Subs.isTxnValid("رقم"))
    }

    @Test fun durationsArePlainFusha() {
        assertEquals("شهر واحد", Subs.durationText(30))
        assertEquals("سنة كاملة", Subs.durationText(365))
        assertEquals("7 أيام", Subs.durationText(7))
        assertEquals("45 يوماً", Subs.durationText(45))
    }

    @Test fun flowProblems() {
        val m = Samples115.config.methods.first { it.id == "kuraimi" }
        val other = Samples115.config.methods.first { it.id == "wallet_other" }
        val ok = SubFlowState(planId = "pro_month", methodId = "kuraimi", txn = "556677", name = "مدين محافظ حميد اليتيم", receiptPath = "/x.jpg")
        assertNull(ok.problem(m))
        assertEquals("أرفق صورة الإيصال", ok.copy(receiptPath = null).problem(m))
        assertEquals("اكتب الاسم الرباعي كاملاً", ok.copy(name = "مدين اليتيم").problem(m))
        assertEquals("اكتب رقم العملية كما في الإيصال", ok.copy(txn = "").problem(m))
        assertEquals("اكتب اسم المحفظة", ok.copy(methodId = other.id).problem(other))
        assertNull(ok.copy(methodId = other.id, other = "كاش").problem(other))
    }

    @Test fun badgeWhileThePlanRunsThenExpiry() {
        val now = Samples115.T0
        val s = AppSettings(proUntil = now + 86_400_000, planName = "برو شهري", planBadge = "برو")
        assertEquals("برو", Subs.badge(s, now))
        assertNull(Subs.expired(s, now))
        val later = now + 2 * 86_400_000
        assertNull(Subs.badge(s, later))
        assertEquals("برو شهري", Subs.expired(s, later))
        assertNull(Subs.badge(AppSettings(proUntil = now + 86_400_000), now)) // activation code / old Pro: no plan badge
    }

    @Test fun approvedMessageCarriesPlanDurationPayerAndEnd() {
        val lines = Subs.approvedLines(Samples115.approved).toMap()
        assertEquals("برو شهري", lines["الخطة"])
        assertEquals("شهر واحد", lines["المدة"])
        assertEquals("مدين محافظ حميد اليتيم", lines["باسم"])
        assertEquals("محفظة جيب", lines["طريقة الدفع"])
        assertTrue(lines["ينتهي"]!!.isNotBlank())
    }

    @Test fun serverErrorShapeIsRead() = runBlocking {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"pending_exists","message":"لديك طلب قيد المراجعة، انتظر حتى تتم مراجعته"}}"""))
        srv.enqueue(MockResponse().setResponseCode(200).setBody("""{"plans":[{"id":"pro_month","name":"برو شهري","days":30,"price":3000,"currency":"YER","badge":"برو"}],"methods":[{"id":"jaib","kind":"wallet","label":"جيب","number":"780647484","accountName":"مدين محافظ حميد اليتيم","other":false}],"note":""}"""))
        srv.enqueue(MockResponse().setResponseCode(200).setBody("""{"maintenance":{"on":true,"message":"صيانة","mode":"full","until":null},"broadcast":{"id":"bc_1","title":"تحديث","body":"نص","at":1},"serverTime":1}"""))
        srv.start()
        val api = DaftariApi({ srv.url("/").toString() }, { "tok" })
        try { api.subStatus(); fail() } catch (e: ApiException) { assertEquals("pending_exists", e.code); assertTrue(e.message.contains("قيد المراجعة")) }
        val cfg = api.subConfig()
        assertEquals("780647484", cfg.methods[0].number)
        val st = api.appStatus()
        assertTrue(st.maintenance.fullScreen); assertEquals("bc_1", st.broadcast!!.id)
        assertEquals("Bearer tok", srv.takeRequest().getHeader("Authorization"))
        srv.shutdown()
    }

    @Test fun serverNotDeployedIsAClearMessage() {
        assertTrue(Subs.errorText(ApiException("http_404", "x", 404)).contains("قيد التجهيز"))
        assertTrue(Subs.errorText(ApiException("offline", "x")).contains("الاتصال"))
        assertTrue(Subs.errorText(ApiException("no_server", "x")).contains("قيد التجهيز"))
    }

    @Test fun telegramOnlyInSettingsNotOnSubscriptionScreens() {
        val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        val subs = File(main, "java/com/daftari/app/ui/subs/SubscriptionScreens.kt").readText()
        assertFalse(subs.contains("DEV_TELEGRAM") || subs.contains("تيليجرام") || subs.contains("abnalyatim"))
        val settings = File(main, "java/com/daftari/app/ui/settings/SettingsScreen.kt").readText()
        assertTrue(settings.contains("DEV_TELEGRAM"))
    }

    @Test fun serverUrlIsBakedWithAFallback() {
        assertTrue(BuildConfig.SERVER_URL.startsWith("https://"))
        assertTrue(BuildConfig.VERSION_CODE >= 8) // exact version pinned in Features116Test
    }
}
