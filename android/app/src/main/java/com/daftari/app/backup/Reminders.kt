package com.daftari.app.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.daftari.app.notify.Alerts
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Reminders set from «مدين»: kept on the phone (and in backups), fired by WorkManager even after a restart. */
class ReminderStore(context: Context) {
    private val ctx = context.applicationContext
    private val p = ctx.getSharedPreferences("daftari_reminders", Context.MODE_PRIVATE)

    fun all(): List<Reminder> = runCatching {
        val a = JSONArray(p.getString("list", "[]")); (0 until a.length()).map { i -> a.getJSONObject(i).let { Reminder(it.getLong("id"), it.optLong("partyId"), it.getLong("at"), it.optString("text"), it.optBoolean("done")) } }
    }.getOrDefault(emptyList())

    private fun save(l: List<Reminder>) = p.edit().putString("list", JSONArray().apply {
        l.forEach { r -> put(JSONObject().put("id", r.id).put("partyId", r.partyId).put("at", r.at).put("text", r.text).put("done", r.done)) }
    }.toString()).apply()

    fun add(partyId: Long, at: Long, text: String): Reminder {
        val r = Reminder((all().maxOfOrNull { it.id } ?: 0) + 1, partyId, at, text)
        save(all() + r); schedule(r); return r
    }

    fun merge(list: List<Reminder>) {
        val cur = all().associateBy { it.id }.toMutableMap()
        list.forEach { if (it.id !in cur) { cur[it.id] = it; if (!it.done && it.at > System.currentTimeMillis()) schedule(it) } }
        save(cur.values.sortedBy { it.id })
    }

    fun markDone(id: Long) = save(all().map { if (it.id == id) it.copy(done = true) else it })

    private fun schedule(r: Reminder) {
        val delay = (r.at - System.currentTimeMillis()).coerceAtLeast(0)
        runCatching {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("id" to r.id, "party" to r.partyId, "text" to r.text)).build(),
            )
        }
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", 0)
        Alerts.reminder(applicationContext, id, inputData.getLong("party", 0), inputData.getString("text").orEmpty())
        ReminderStore(applicationContext).markDone(id)
        return Result.success()
    }
}

/** The key derived from «كلمة سر النسخ» (salt + PBKDF2), kept on this phone so the daily copy can be encrypted unattended. */
class BackupKeys(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("daftari_backup_key", Context.MODE_PRIVATE)
    private val b64e = java.util.Base64.getEncoder(); private val b64d = java.util.Base64.getDecoder()

    fun get(): BackupKey? {
        val k = p.getString("k", null) ?: return null; val s = p.getString("s", null) ?: return null
        return BackupKey(b64d.decode(k), b64d.decode(s), p.getInt("i", DaftariFile.ITERATIONS))
    }

    fun set(password: String): BackupKey = DaftariFile.deriveKey(password).also { k ->
        p.edit().putString("k", b64e.encodeToString(k.key)).putString("s", b64e.encodeToString(k.salt)).putInt("i", k.iterations).apply()
    }

    fun clear() = p.edit().clear().apply()
}
