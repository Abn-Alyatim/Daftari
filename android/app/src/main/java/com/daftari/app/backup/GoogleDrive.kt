package com.daftari.app.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.daftari.app.AppGraph
import com.daftari.app.BuildConfig
import com.daftari.app.DaftariApp
import com.daftari.app.ui.auth.GoogleErrors
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    /** The WEB OAuth client id of his Cloud project (serverClientId for Google sign-in, which picks the account and gives its email);
     *  local.properties DF_GOOGLE_WEB_CLIENT_ID. Drive itself is authorized by the Android client (package + SHA-1). */
    val webClientId: String get() = BuildConfig.DF_GOOGLE_WEB_CLIENT_ID

    const val SETUP_AR = "ربط Google Drive يتطلب إعداداً لمرة واحدة في Google Cloud: عميل OAuth من نوع Android باسم الحزمة \u2066com.daftari.app\u2069 وبصمة التوقيع SHA-1، وتفعيل Google Drive API. وحتى يتم ذلك استخدم «حفظ نسخة في Drive» في الأسفل — تعمل الآن دون أي إعداد."
    const val WHAT = "ربط Google Drive"

    sealed interface Auth {
        data class Token(val token: String) : Auth
        data class NeedsUi(val pending: PendingIntent) : Auth
        data object Cancelled : Auth
        data class Failed(val message: String, val setupMissing: Boolean = false) : Auth
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }; addOnFailureListener { c.resumeWithException(it) }; addOnCanceledListener { c.cancel() }
    }

    /** [account] = the Google account he chose at sign-in (or the one already linked), so Drive is asked for THAT account. */
    private fun request(account: String?) = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        .apply { if (account != null && "@" in account) setAccount(Account(account, "com.google")) }.build()

    private fun of(r: AuthorizationResult): Auth = when {
        r.hasResolution() && r.pendingIntent != null -> Auth.NeedsUi(r.pendingIntent!!)
        r.accessToken != null -> Auth.Token(r.accessToken!!)
        else -> Auth.Failed("تعذّر ربط Google Drive: لم يصل إذن الوصول إلى Drive")
    }

    fun fail(e: Throwable): Auth {
        val code = (e as? ApiException)?.statusCode
        val kind = GoogleErrors.fromStatus(code)
        val text = GoogleErrors.text(kind, WHAT, code?.let { "رمز $it" } ?: e.javaClass.simpleName) ?: return Auth.Cancelled
        return Auth.Failed(text, setupMissing = kind == GoogleErrors.Kind.DEVELOPER)
    }

    suspend fun authorize(ctx: Context, account: String? = null): Auth {
        val client = Identity.getAuthorizationClient(ctx)
        return try { of(client.authorize(request(account)).await()) } catch (e: Exception) {
            // an account hint the phone no longer has must not block linking: ask again and let Google show its chooser
            if (account != null && (e as? ApiException)?.statusCode != 7) try { of(client.authorize(request(null)).await()) } catch (e2: Exception) { fail(e2) } else fail(e)
        }
    }

    fun fromIntent(ctx: Context, data: Intent?): Auth = try { of(Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)) } catch (e: Exception) { fail(e) }

    // ------------------------------------------------------------------ REST
    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build() }
    private const val API = "https://www.googleapis.com/drive/v3"

    private fun call(req: Request): String = http.newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw java.io.IOException(restError(r.code, body))
        body
    }

    /** Drive REST failures in فصحى; 403 is almost always «Drive API not enabled in the project» or a missing scope. */
    fun restError(code: Int, body: String = ""): String = when {
        code == 401 -> "انتهت صلاحية الربط، سجّل الدخول بـ Google من جديد"
        code == 403 && ("accessNotConfigured" in body || "SERVICE_DISABLED" in body || "has not been used" in body) -> "Google Drive API غير مفعّل في مشروع Google Cloud. فعّله من APIs & Services ← Library ثم أعِد المحاولة."
        code == 403 -> "رفض Google Drive الطلب (403): تأكّد من تفعيل Google Drive API ومن إضافة صلاحية drive.appdata."
        code == 429 || code >= 500 -> "خدمة Google Drive مشغولة حالياً ($code)، أعِد المحاولة بعد قليل."
        else -> "ردّ Google Drive بخطأ $code"
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

    /** Keeps the newest [keep] copies of THIS workspace (the other role's copies are never touched). */
    suspend fun prune(token: String, role: com.daftari.app.domain.Role, legacy: com.daftari.app.domain.Role?, keep: Int = 30) {
        list(token).filter { com.daftari.app.core.Workspaces.belongs(it.name, role, legacy) }.drop(keep).forEach { delete(token, it.id) }
    }
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

/**
 * The mandatory daily Drive copy (1.1.4): every day at his chosen time (default 02:00, local). A one-time job that, after each run,
 * enqueues the next one at the next HH:MM — so it stays aligned even after a late run. Needs internet: if the phone is offline or off
 * at that time, WorkManager runs it as soon as it can, and the next one goes back to HH:MM. Reboot keeps it (WorkManager persists it);
 * a time-zone or clock change realigns it (TimeChangeReceiver). No exact-alarm permission. Covers the role's book that is open.
 */
class DriveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as DaftariApp).graph
        val s = g.settings.current()
        if (s.driveAccount.isBlank() || !s.onboarded) return Result.success()
        val t = GoogleDrive.authorize(applicationContext, s.driveAccount) as? GoogleDrive.Auth.Token
        if (t == null) { schedule(applicationContext, s.backupMinuteOfDay, replace = false, chain = true); return Result.success() }
        return try {
            GoogleDrive.upload(t.token, com.daftari.app.core.Workspaces.backupFileName(s.role, DaftariFile.fileName()), BackupMaker.bytes(g))
            GoogleDrive.prune(t.token, s.role, g.workspaces.legacy())
            g.settings.update { it.copy(lastDriveBackupAt = System.currentTimeMillis()) }
            schedule(applicationContext, s.backupMinuteOfDay, replace = false, chain = true)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else { schedule(applicationContext, s.backupMinuteOfDay, replace = false, chain = true); Result.success() }
        }
    }

    companion object {
        const val NAME = "daftari_drive_daily"

        /**
         * [replace] = the time changed / the clock or zone moved: drop the queued run and align to the new HH:MM.
         * [chain] = called from inside the running job: the next run is appended after this one finishes.
         * Otherwise (app start) an already-queued run is kept.
         */
        fun schedule(ctx: Context, minuteOfDay: Int, replace: Boolean, chain: Boolean = false) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("daftari_drive") // 1.1.3's periodic «when there is internet» job
            val delay = com.daftari.app.core.BackupClock.delay(java.time.ZonedDateTime.now(), minuteOfDay / 60, minuteOfDay % 60)
            val req = OneTimeWorkRequestBuilder<DriveWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            val policy = when { chain -> ExistingWorkPolicy.APPEND_OR_REPLACE; replace -> ExistingWorkPolicy.REPLACE; else -> ExistingWorkPolicy.KEEP }
            wm.enqueueUniqueWork(NAME, policy, req)
        }
    }
}

/** Time zone / clock / reboot → the daily Drive copy realigns to his HH:MM. */
class TimeChangeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = (context.applicationContext as DaftariApp).graph.settings.current()
                if (s.driveAccount.isNotBlank()) DriveWorker.schedule(context, s.backupMinuteOfDay, replace = true)
            } finally { pending.finish() }
        }
    }
}
