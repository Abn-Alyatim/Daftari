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
    suspend fun requestOtp(phone: String, channel: String): OtpRequestResult = post("/api/v1/auth/otp/request", OtpRequest(phone, channel))
    suspend fun verifyOtp(v: OtpVerify): AuthResult = post("/api/v1/auth/otp/verify", v)
    suspend fun google(g: GoogleLogin): AuthResult = post("/api/v1/auth/google", g)
    suspend fun chat(r: ChatRequest): ChatReply = post("/api/v1/chat", r)
    suspend fun smartImport(r: ImportRequest): ImportResponse = post("/api/v1/import", r)
    suspend fun backup(snapshot: JsonElement): JsonObject = post("/api/v1/backup", snapshot)
    suspend fun latestBackup(): JsonObject = get("/api/v1/backup/latest")
    suspend fun activate(code: String): ActivateResult = post("/api/v1/plan/activate", ActivateRequest(code))

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
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val code = obj?.get("code")?.jsonPrimitive?.content ?: "http_${r.code}"
                val msg = obj?.get("message")?.jsonPrimitive?.content ?: when (r.code) {
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
