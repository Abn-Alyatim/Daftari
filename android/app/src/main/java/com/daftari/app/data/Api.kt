package com.daftari.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ServerInfo(
    val name: String = "",
    val version: String = "",
    val otpProvider: String = "none",
    val otpChannels: List<String> = emptyList(),
    val otpReady: Boolean = false,
    val googleClientId: String = "",
    val aiReady: Boolean = false,
    /** 1.2.0 server: online voice → text is enabled (a Groq key is set on the server) */
    val voiceReady: Boolean = false,
)

@Serializable data class OtpRequest(val phone: String, val channel: String = "whatsapp")
@Serializable data class OtpRequestResult(val ok: Boolean = false, val channel: String = "", val retryAfterSec: Int = 60, val devCode: String? = null)
@Serializable data class OtpVerify(val phone: String, val code: String, val role: String, val email: String = "", val name: String = "")
@Serializable data class GoogleLogin(val idToken: String, val role: String)
@Serializable data class AuthUser(val id: String = "", val phone: String = "", val email: String = "", val name: String = "", val role: String = "")
@Serializable data class AuthResult(val token: String, val user: AuthUser)

@Serializable data class ChatTurn(val role: String, val content: String)
@Serializable data class ChatRequest(val messages: List<ChatTurn>, val context: JsonElement)
@Serializable data class ChatTable(val title: String = "", val columns: List<String> = emptyList(), val rows: List<List<String>> = emptyList())
@Serializable data class ChatAction(
    val type: String,
    val party: String = "",
    val amount: Double = 0.0,
    val note: String = "",
    val name: String = "",
    val phone: String = "",
    val activity: String = "",
    val shopNumber: String = "",
    val periodicAmount: Double = 0.0,
    val entryId: Long = 0,
    // ---- 1.1.0: offline «مدين» (resolved on the phone; never sent by the server)
    val partyId: Long = 0,
    val minor: Long = 0,
    val items: String = "",
    val day: Long = 0,
    val remindAt: Long = 0,
    val auditSeq: Long = 0,
    /** pre-rendered lines of the confirmation card */
    val lines: List<String> = emptyList(),
    val origCurrency: String = "",
    val origMinor: Long = 0,
    val rate: String = "",
    val method: String = "",
    val transferNo: String = "",
    val offline: Boolean = false,
    /** 1.1.1: wallet payments — sender phone (optional) */
    val senderPhone: String = "",
)
@Serializable data class ImportRequest(val fileName: String, val mime: String, val text: String = "", val images: List<String> = emptyList(), val role: String = "")
@Serializable data class ImportRowDto(
    val name: String = "", val shopNumber: String = "", val phone: String = "", val activity: String = "",
    val amount: Double = 0.0, val arrears: Double = 0.0, val note: String = "", val doubtful: Boolean = false, val reason: String = "",
)
@Serializable data class ImportResponse(val rows: List<ImportRowDto> = emptyList(), val businessName: String = "", val ownerName: String = "", val warnings: List<String> = emptyList())
@Serializable data class ChatReply(val reply: String = "", val table: ChatTable? = null, val action: ChatAction? = null)

/** An error the UI can show as is (Arabic). */
@Serializable data class ActivateRequest(val code: String)
@Serializable data class ActivateResult(val ok: Boolean = true, val proUntil: Long = 0, val plan: String = "pro")

// ---- 1.1.5: subscriptions (transfer / wallet receipt → Madyan Panel), maintenance and broadcast from the server ----
@Serializable data class Maintenance(val on: Boolean = false, val message: String = "", val mode: String = "banner", val until: Long? = null) {
    val fullScreen: Boolean get() = on && mode == "full"
}
@Serializable data class Broadcast(val id: String = "", val title: String = "", val body: String = "", val at: Long = 0)
@Serializable data class AppStatus(val maintenance: Maintenance = Maintenance(), val broadcast: Broadcast? = null, val serverTime: Long = 0)
@Serializable data class SubPlan(val id: String, val name: String, val days: Int, val price: Double, val currency: String = "YER", val badge: String = "برو")
@Serializable data class PayMethod(val id: String, val kind: String, val label: String, val number: String, val accountName: String = "", val other: Boolean = false)
@Serializable data class SubConfig(val plans: List<SubPlan> = emptyList(), val methods: List<PayMethod> = emptyList(), val note: String = "")
@Serializable data class ActivePlan(val name: String = "", val badge: String = "برو", val startsAt: Long = 0, val endsAt: Long = 0, val active: Boolean = false, val daysLeft: Int = 0)
@Serializable data class SubRequestDto(
    val id: String, val planId: String = "", val planName: String = "", val days: Int = 0, val price: Double = 0.0, val currency: String = "YER",
    val methodId: String = "", val methodLabel: String = "", val methodKind: String = "", val payTo: String = "", val txnNo: String = "", val payerName: String = "",
    val status: String = "pending", val rejectReason: String = "", val createdAt: Long = 0, val decidedAt: Long = 0, val startsAt: Long = 0, val endsAt: Long = 0, val seen: Boolean = false,
)
@Serializable data class SubStatus(val email: String = "", val plan: ActivePlan? = null, val pending: SubRequestDto? = null, val notice: SubRequestDto? = null, val last: SubRequestDto? = null)
@Serializable data class SubSubmit(val planId: String, val methodId: String, val methodOther: String = "", val txnNo: String, val payerName: String, val receipt: String, val receiptMime: String = "image/jpeg")
@Serializable data class SubSubmitResult(val request: SubRequestDto)
@Serializable data class Ok(val ok: Boolean = true)

class ApiException(val code: String, override val message: String, val status: Int = 0) : Exception(message)

class DaftariApi(private val baseUrl: () -> String, private val token: () -> String = { "" }, client: OkHttpClient? = null) {
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val configured: Boolean get() = baseUrl().isNotBlank()

    suspend fun info(): ServerInfo = get("/api/v1/info")

    /**
     * 1.1.7: online speech → text (the server forwards to Groq Whisper; its key never reaches the app). Blocking — call it
     * off the main thread. [wav] is 16 kHz mono PCM16; the whole call gives up after [timeoutSec]. Throws [ApiException]
     * ("voice_disabled" when the server has no key, "offline" on no network / timeout).
     */
    fun transcribeVoice(wav: ByteArray, timeoutSec: Long = 8, prompt: String = ""): String {
        val client = http.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(timeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        // 1.1.8: the optional hint (customer names + phrases) rides as ?prompt= — server 1.2.2 forwards it to Whisper, older ones ignore it
        val q = if (prompt.isBlank()) "" else "?prompt=" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val rb = Request.Builder().url(url("/api/v1/voice/transcribe") + q).post(wav.toRequestBody("audio/wav".toMediaType()))
        token().takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        val resp = try { client.newCall(rb.header("Accept", "application/json").build()).execute() }
            catch (e: IOException) { throw ApiException("offline", "لا يوجد اتصال بالإنترنت أو أن الخادم لا يستجيب") }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful) {
                val code = runCatching { obj?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull() ?: "http_${r.code}"
                throw ApiException(code, "تعذّر التعرّف عبر الإنترنت", r.code)
            }
            return runCatching { obj?.get("text")?.jsonPrimitive?.content }.getOrNull().orEmpty().trim()
        }
    }
    suspend fun google(g: GoogleLogin): AuthResult = post("/api/v1/auth/google", g)
    suspend fun chat(r: ChatRequest): ChatReply = post("/api/v1/chat", r)
    suspend fun smartImport(r: ImportRequest): ImportResponse = post("/api/v1/import", r)
    suspend fun backup(snapshot: JsonElement): JsonObject = post("/api/v1/backup", snapshot)
    suspend fun latestBackup(): JsonObject = get("/api/v1/backup/latest")
    suspend fun activate(code: String): ActivateResult = post("/api/v1/plan/activate", ActivateRequest(code))
    suspend fun appStatus(): AppStatus = get("/api/v1/app/status")
    suspend fun subConfig(): SubConfig = get("/api/v1/subscription/config")
    suspend fun subStatus(): SubStatus = get("/api/v1/subscription/status")
    suspend fun submitSub(s: SubSubmit): SubSubmitResult = post("/api/v1/subscription/requests", s)
    suspend fun subSeen(id: String): Ok = post("/api/v1/subscription/requests/$id/seen", Ok())

    private suspend inline fun <reified T> get(path: String): T = call(Request.Builder().url(url(path)).get())
    private suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        call(Request.Builder().url(url(path)).post(json.encodeToString(kotlinx.serialization.serializer<B>(), body).toRequestBody(jsonType)))

    private fun url(path: String): String {
        val b = baseUrl().trimEnd('/')
        if (b.isBlank()) throw ApiException("no_server", "الخادم غير مضبوط بعد — التطبيق يعمل دون إنترنت")
        return b + path
    }

    private suspend inline fun <reified T> call(rb: Request.Builder): T = withContext(Dispatchers.IO) {
        val t = token()
        if (t.isNotBlank()) rb.header("Authorization", "Bearer $t")
        rb.header("Accept", "application/json")
        val resp = try {
            http.newCall(rb.build()).execute()
        } catch (e: IOException) {
            throw ApiException("offline", "لا يوجد اتصال بالإنترنت أو أن الخادم لا يستجيب")
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val top = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                // the server answers {"error":{"code","message"}}; older shapes put code/message at the top
                val obj = runCatching { top?.get("error")?.jsonObject }.getOrNull() ?: top
                val code = runCatching { obj?.get("code")?.jsonPrimitive?.content }.getOrNull() ?: "http_${r.code}"
                val msg = runCatching { obj?.get("message")?.jsonPrimitive?.content }.getOrNull() ?: when (r.code) {
                    404 -> "الخادم غير منشور بعد"
                    429 -> "طلبات كثيرة، انتظر قليلاً ثم حاول مجدداً"
                    in 500..599 -> "في الخادم مشكلة مؤقتة"
                    else -> "تعذّر الطلب (${r.code})"
                }
                throw ApiException(code, msg, r.code)
            }
            json.decodeFromString(kotlinx.serialization.serializer<T>(), text)
        }
    }
}
