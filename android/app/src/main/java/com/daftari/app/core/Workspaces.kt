package com.daftari.app.core

import android.content.Context
import com.daftari.app.data.AppSettings
import com.daftari.app.domain.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.ZonedDateTime

/**
 * 1.1.4 — one Google account, two separate books: «صاحب محل» and «صاحب سوق». Each role is a workspace with its own database,
 * «السجل», reminders and business profile. The workspace that existed before 1.1.4 (the "legacy" role — the role his data was
 * set up with) keeps the original file names, so upgrading never moves or loses data; the other role gets suffixed files.
 */
object Workspaces {
    fun suffix(role: Role, legacy: Role?): String = if (legacy == null || role == legacy) "" else "_" + role.name.lowercase()
    fun dbName(role: Role, legacy: Role?) = "daftari${suffix(role, legacy)}.db"
    fun logDbName(role: Role, legacy: Role?) = "daftari_log${suffix(role, legacy)}.db"
    fun anchorPrefs(role: Role, legacy: Role?) = "daftari_log_anchor${suffix(role, legacy)}"
    fun remindersPrefs(role: Role, legacy: Role?) = "daftari_reminders${suffix(role, legacy)}"

    /** Drive copies are per workspace: «Daftari-shop-…» / «Daftari-market-…». Copies from before 1.1.4 (no tag) belong to the legacy role. */
    fun driveTag(role: Role) = if (role == Role.SHOP_OWNER) "shop" else "market"
    fun backupFileName(role: Role, plain: String): String = plain.replaceFirst("Daftari-", "Daftari-${driveTag(role)}-")
    fun belongs(fileName: String, role: Role, legacy: Role?): Boolean {
        val tagged = Role.entries.firstOrNull { fileName.startsWith("Daftari-${driveTag(it)}-") }
        return if (tagged != null) tagged == role else (legacy == null || legacy == role)
    }
}

/** The per-workspace part of the settings, parked while the other role's book is open. */
@Serializable
data class WorkspaceProfile(
    val onboarded: Boolean = false, val businessName: String = "", val ownerName: String = "", val shopCount: Int = 0, val currency: String = "YER",
    val demoData: Boolean = false, val lastBackupAt: Long = 0, val lastDriveBackupAt: Long = 0, val bookId: String = "", val currencyFilled: Boolean = true,
) {
    fun applyTo(s: AppSettings, role: Role): AppSettings = s.copy(
        role = role, onboarded = onboarded, businessName = businessName, ownerName = ownerName.ifBlank { s.ownerName }, shopCount = shopCount,
        currency = Currency.of(currency), demoData = demoData, lastBackupAt = lastBackupAt, lastDriveBackupAt = lastDriveBackupAt, bookId = bookId, currencyFilled = currencyFilled,
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun of(s: AppSettings) = WorkspaceProfile(s.onboarded, s.businessName, s.ownerName, s.shopCount, s.currency.code, s.demoData, s.lastBackupAt, s.lastDriveBackupAt, s.bookId, s.currencyFilled)
        fun decode(text: String?): WorkspaceProfile? = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** Which roles have a workspace, which one is open, and the one-shot unlock that survives the restart of a role switch. */
class WorkspaceStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("daftari_workspaces", Context.MODE_PRIVATE)
    fun active(): Role? = p.getString("active", null)?.let { n -> Role.entries.firstOrNull { it.name == n } }
    fun legacy(): Role? = p.getString("legacy", null)?.let { n -> Role.entries.firstOrNull { it.name == n } }
    /** First call wins: the role the pre-1.1.4 (or first) data belongs to. */
    fun initLegacy(role: Role) { if (legacy() == null) p.edit().putString("legacy", role.name).putString("active", active()?.name ?: role.name).commit() }
    fun setActive(role: Role) { p.edit().putString("active", role.name).commit() }
    fun has(role: Role): Boolean = role == legacy() || role == active() || p.contains("profile_${role.name}")
    fun profile(role: Role): WorkspaceProfile? = WorkspaceProfile.decode(p.getString("profile_${role.name}", null))
    fun saveProfile(role: Role, profile: WorkspaceProfile) { p.edit().putString("profile_${role.name}", profile.encode()).commit() }
    fun grantUnlock(untilElapsed: Long) { p.edit().putLong("grant", untilElapsed).commit() }
    fun consumeGrant(nowElapsed: Long): Boolean { val g = p.getLong("grant", 0); p.edit().remove("grant").commit(); return g > nowElapsed }
}

/** What the launch shows (pure, unit-tested). */
enum class Gate { FIRST_RUN, SET_PASSWORD, RETURNING, SETUP, MAIN }

object AuthGate {
    fun of(s: AppSettings, unlocked: Boolean, resettingPassword: Boolean = false): Gate = when {
        !s.hasAccount -> Gate.FIRST_RUN
        !s.hasPassword || resettingPassword -> Gate.SET_PASSWORD
        !unlocked -> Gate.RETURNING
        !s.onboarded -> Gate.SETUP
        else -> Gate.MAIN
    }

    /** «نسيت كلمة المرور؟»: only the Google account that owns this دفتري may reset it. */
    fun sameAccount(owner: String, picked: String): Boolean = owner.isNotBlank() && owner.trim().equals(picked.trim(), ignoreCase = true)

    fun passwordProblem(pw: String, confirm: String): String? = when {
        pw.length < 4 -> "كلمة السر 4 أرقام على الأقل"
        pw != confirm -> "كلمتا السر غير متطابقتين"
        else -> null
    }
}

/** The mandatory daily Drive copy: the next run at HH:MM local time, strictly after [now]. */
object BackupClock {
    fun next(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        var t = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        if (!t.isAfter(now)) t = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
        return t
    }
    fun delay(now: ZonedDateTime, hour: Int, minute: Int): Duration = Duration.between(now, next(now, hour, minute))
    fun label(minuteOfDay: Int): String {
        val h = minuteOfDay / 60; val m = minuteOfDay % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        val part = when { h < 12 -> "صباحاً"; h < 17 -> "ظهراً"; else -> "مساءً" }
        return "%d:%02d %s".format(java.util.Locale.US, h12, m, part)
    }
}
