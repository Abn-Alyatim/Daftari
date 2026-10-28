package com.daftari.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.daftari.app.MainActivity
import com.daftari.app.R

/** Real phone notifications: «وصل السقف» alarm (high importance + alarm sound), due reminders, daily summary. */
object Alerts {
    const val CH_LIMIT = "limit_alarm"
    const val CH_DUE = "due_reminders"
    const val CH_DAILY = "daily_summary"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(CH_LIMIT, "تنبيه سقف الدين", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "منبّه عندما يصل زبون إلى حد الدين"
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(NotificationChannel(CH_DUE, "مواعيد السداد", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "تذكير بالديون التي حلّ موعدها والمتأخرين" })
        nm.createNotificationChannel(NotificationChannel(CH_DAILY, "ملخص يومي", NotificationManager.IMPORTANCE_LOW).apply { description = "إجمالي المستحق وتحصيل اليوم" })
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(ctx: Context, partyId: Long?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            partyId?.let { putExtra(MainActivity.EXTRA_PARTY, it) }
        }
        return PendingIntent.getActivity(ctx, (partyId ?: 0L).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("MissingPermission")
    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }

    fun limitReached(ctx: Context, partyId: Long, name: String, balanceText: String, limitText: String) {
        post(ctx, 10_000 + partyId.toInt(), NotificationCompat.Builder(ctx, CH_LIMIT)
            .setSmallIcon(R.drawable.ic_triangle_alert)
            .setContentTitle("⚠️ $name وصل سقف الدين")
            .setContentText("عليه $balanceText والحد $limitText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("عليه $balanceText والحد المسموح $limitText. لا تعطه آجل جديد قبل ما يسدد، أو أرسل له تذكير من التطبيق."))
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(open(ctx, partyId)))
    }

    fun dueToday(ctx: Context, count: Int, totalText: String, names: List<String>) {
        if (count == 0) return
        post(ctx, 2001, NotificationCompat.Builder(ctx, CH_DUE)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("حان موعد سداد $count ${if (count == 1) "حساب" else "حسابات"}")
            .setContentText("المجموع $totalText")
            .setStyle(NotificationCompat.BigTextStyle().bigText(names.take(6).joinToString("، ") + if (names.size > 6) " وغيرهم" else ""))
            .setAutoCancel(true).setContentIntent(open(ctx, null)))
    }

    fun dailySummary(ctx: Context, owedText: String, lateCount: Int) {
        post(ctx, 2002, NotificationCompat.Builder(ctx, CH_DAILY)
            .setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle("ملخص دفتري")
            .setContentText("المستحق $owedText · المتأخرين $lateCount")
            .setAutoCancel(true).setContentIntent(open(ctx, null)))
    }
}
