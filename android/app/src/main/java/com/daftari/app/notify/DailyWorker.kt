package com.daftari.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.daftari.app.DaftariApp
import com.daftari.app.core.Money
import com.daftari.app.data.Backup
import com.daftari.app.data.LedgerRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Once a day: due-date reminders, a balance summary, and a local backup (the last 7 kept). Works fully offline. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as DaftariApp).graph
        val s = g.settings.current()
        if (!s.onboarded) return Result.success()
        val today = LocalDate.now()
        val parties = g.repo.allParties()
        val entries = g.repo.allEntries()
        val byParty = entries.groupBy { it.partyId }
        val summaries = parties.map { com.daftari.app.domain.Ledger.summarize(it, byParty[it.id].orEmpty(), today, s.lateAfterDays) }
        val d = LedgerRepository.buildDashboard(summaries, entries, today)
        if (s.alertsEnabled) {
            Alerts.dueToday(applicationContext, d.dueNow.size, Money.format(d.dueNow.sumOf { it.first.balance }, s.currency), d.dueNow.map { it.first.party.title })
            d.overLimit.firstOrNull()?.let { o -> Alerts.limitReached(applicationContext, o.party.id, o.party.name, Money.format(o.balance, s.currency), Money.format(o.party.creditLimit, s.currency)) }
            Alerts.dailySummary(applicationContext, Money.format(d.totalOwed, s.currency), d.lateCount)
        }
        runCatching { writeLocalBackup(applicationContext, Backup.toJson(parties, entries, s.businessName, s.ownerName, s.role.name)) }
        return Result.success()
    }

    companion object {
        private const val NAME = "daftari_daily"

        fun schedule(ctx: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(9, 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = java.time.Duration.between(now, next).toMinutes()
            val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun backupDir(ctx: Context) = File(ctx.filesDir, "backups").apply { mkdirs() }

        fun writeLocalBackup(ctx: Context, json: String): File {
            val dir = backupDir(ctx)
            val f = File(dir, "daftari_backup_${LocalDate.now()}.json")
            f.writeText(json)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
            return f
        }

        fun latestLocalBackup(ctx: Context): File? = backupDir(ctx).listFiles()?.maxByOrNull { it.name }
    }
}
