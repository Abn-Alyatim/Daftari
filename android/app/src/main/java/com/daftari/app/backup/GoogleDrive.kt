package com.daftari.app.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.daftari.app.AppGraph
import com.daftari.app.BuildConfig
import com.daftari.app.DaftariApp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DriveFile(val id: String, val name: String, val size: Long, val createdAt: Long)

/**
 * Google Drive backup through the REST API with the `drive.appdata` scope: copies live in the app's hidden Drive folder
 * (only دفتري can see them, they do not clutter his Drive). Authorization is Google Identity's AuthorizationClient: it needs
 * an OAuth client of type Android in HIS Google Cloud project (package com.daftari.app + the signing SHA-1) and the Drive API
 * enabled — nothing secret is in the app. Without that setup Google answers DEVELOPER_ERROR and the screen says so in Arabic.
 */
object GoogleDrive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    /** Optional web client id (Google sign-in for the account name); Drive itself only needs the Android client. */
    val webClientId: String get() = BuildConfig.DF_GOOGLE_WEB_CLIENT_ID

    const val SETUP_AR = "ربط Google Drive يتطلب إعداداً لمرة واحدة في Google Cloud: عميل OAuth من نوع Android باسم الحزمة \u2066com.daftari.app\u2069 وبصمة التوقيع SHA-1، وتفعيل Google Drive API. وحتى يتم ذلك استخدم «حفظ نسخة في Drive» في الأسفل — تعمل الآن دون أي إعداد."

    sealed interface Auth {
        data class Token(val token: String) : Auth
        data class NeedsUi(val pending: PendingIntent) : Auth
        data class Failed(val message: String, val setupMissing: Boolean = false) : Auth
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }; addOnFailureListener { c.resumeWithException(it) }; addOnCanceledListener { c.cancel() }
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    private fun of(r: AuthorizationResult): Auth = when {
        r.hasResolution() && r.pendingIntent != null -> Auth.NeedsUi(r.pendingIntent!!)
        r.accessToken != null -> Auth.Token(r.accessToken!!)
        else -> Auth.Failed("لم يصلنا إذن Google Drive")
    }

    private fun fail(e: Throwable): Auth {
        val code = (e as? ApiException)?.statusCode
        return if (code == 10 || code == 17 || code == 8) Auth.Failed(SETUP_AR, setupMissing = true)
        else if (code == 7) Auth.Failed("لا يوجد اتصال بالإنترنت 📶")
        else Auth.Failed("تعذّر ربط Google Drive" + (code?.let { " ($it)" } ?: ""))
    }

    suspend fun authorize(ctx: Context): Auth = try { of(Identity.getAuthorizationClient(ctx).authorize(request()).await()) } catch (e: Exception) { fail(e) }

    fun fromIntent(ctx: Context, data: Intent?): Auth = try { of(Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)) } catch (e: Exception) { fail(e) }

    // ------------------------------------------------------------------ REST
    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build() }
    private const val API = "https://www.googleapis.com/drive/v3"

    private fun call(req: Request): String = http.newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw java.io.IOException(if (r.code == 401) "انتهت صلاحية الربط، اربط Google من جديد" else "ردّ Google Drive بخطأ ${r.code}")
        body
    }

    suspend fun email(token: String): String = withContext(Dispatchers.IO) {
        runCatching { JSONObject(call(Request.Builder().url("$API/about?fields=user(emailAddress)").header("Authorization", "Bearer $token").build())).getJSONObject("user").optString("emailAddress") }.getOrDefault("")
    }

    suspend fun list(token: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val url = "$API/files?spaces=appDataFolder&pageSize=200&orderBy=createdTime%20desc&fields=files(id,name,size,createdTime)"
        val a = JSONObject(call(Request.Builder().url(url).header("Authorization", "Bearer $token").build())).optJSONArray("files")
        (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONObject(i).let { DriveFile(it.getString("id"), it.getString("name"), it.optString("size").toLongOrNull() ?: 0, runCatching { Instant.parse(it.getString("createdTime")).toEpochMilli() }.getOrDefault(0)) } }
            .filter { it.name.endsWith(".daftari") }
    }

    suspend fun upload(token: String, name: String, bytes: ByteArray): DriveFile = withContext(Dispatchers.IO) {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(DaftariFile.MIME.toMediaType())).build()
        val o = JSONObject(call(Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size,createdTime")
            .header("Authorization", "Bearer $token").post(body).build()))
        DriveFile(o.getString("id"), o.getString("name"), o.optString("size").toLongOrNull() ?: bytes.size.toLong(), System.currentTimeMillis())
    }

    suspend fun download(token: String, id: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$API/files/$id?alt=media").header("Authorization", "Bearer $token").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("تعذّر تنزيل النسخة (${r.code})"); r.body!!.bytes()
        }
    }

    suspend fun delete(token: String, id: String) = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url("$API/files/$id").header("Authorization", "Bearer $token").delete().build()).execute().close() }
    }

    /** Keeps the newest [keep] copies. */
    suspend fun prune(token: String, keep: Int = 30) { list(token).drop(keep).forEach { delete(token, it.id) } }
}

/** Builds the canonical .daftari file from what is on the phone right now. */
object BackupMaker {
    suspend fun data(g: AppGraph): BackupData {
        val s = g.settings.current()
        val bookId = s.bookId.ifBlank { UUID.randomUUID().toString().also { id -> g.settings.update { it.copy(bookId = id) } } }
        return BackupData(bookId, BuildConfig.VERSION_NAME, System.currentTimeMillis(), s.currency.code, s.businessName, s.ownerName, s.role.name,
            g.repo.allParties(), g.repo.allEntries(), g.reminders.all(), g.audit.all(), ratesJson = s.ratesJson)
    }

    suspend fun bytes(g: AppGraph): ByteArray {
        val s = g.settings.current()
        return DaftariFile.write(data(g), if (s.backupEncrypted) g.backupKeys.get() else null)
    }
}

/** «نسخ تلقائي يومي»: once a day when there is internet, a copy goes to Drive (and the newest 30 are kept). */
class DriveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as DaftariApp).graph
        val s = g.settings.current()
        if (s.driveAccount.isBlank() || !s.driveAuto || !s.onboarded) return Result.success()
        val t = GoogleDrive.authorize(applicationContext) as? GoogleDrive.Auth.Token ?: return Result.success()
        return try {
            GoogleDrive.upload(t.token, DaftariFile.fileName(), BackupMaker.bytes(g))
            GoogleDrive.prune(t.token)
            g.settings.update { it.copy(lastDriveBackupAt = System.currentTimeMillis()) }
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }

    companion object {
        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork("daftari_drive"); return }
            val req = PeriodicWorkRequestBuilder<DriveWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork("daftari_drive", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
