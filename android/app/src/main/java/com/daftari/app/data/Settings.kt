package com.daftari.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.daftari.app.core.Currency
import com.daftari.app.domain.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class ThemeMode(val labelAr: String) { DARK_BLUE("أسود وأزرق"), LIGHT("أبيض وأسود") }

data class AppSettings(
    val onboarded: Boolean = false,
    val signedIn: Boolean = false,
    val role: Role = Role.LANDLORD,
    val businessName: String = "",
    val ownerName: String = "",
    val shopCount: Int = 0,
    val currency: Currency = Currency.YER,
    val theme: ThemeMode = ThemeMode.DARK_BLUE,
    val lockEnabled: Boolean = false,
    val passwordHash: String = "",
    val phone: String = "",
    val email: String = "",
    val authToken: String = "",
    val serverOverride: String = "",
    val lateAfterDays: Int = 3,
    val lastBackupAt: Long = 0,
    val demoData: Boolean = false,
    /** epoch millis until which Pro is active (0 = never bought). */
    val proUntil: Long = 0,
    /** epoch millis when the free Pro trial started (0 = not yet). */
    val trialStartedAt: Long = 0,
    val alertsEnabled: Boolean = true,
    /** «كلمة سر النسخ» is set: backups are encrypted (the derived key lives in BackupKeys, never the password). */
    val backupEncrypted: Boolean = false,
    /** Google Drive (hidden app folder) linked: the account e-mail, "" = not linked. */
    val driveAccount: String = "",
    val driveAuto: Boolean = true,
    val lastDriveBackupAt: Long = 0,
    /** a random id for this book, carried in backups so restoring on the same book merges by ids. */
    val bookId: String = "",
    val ratesJson: String = "",
    /** 1.1.0 filled the currency of accounts from before multi-currency. */
    val currencyFilled: Boolean = false,
    /** 1.1.4: the Google account that owns this دفتري ("" = not signed in → the first-run screen). */
    val googleEmail: String = "",
    val googleName: String = "",
    /** 1.1.4: Google sign-in failed on this phone and he chose to continue without it (password only); the Google button stays offered. */
    val authLocal: Boolean = false,
    /** 1.1.4: open the app with the fingerprint (the app password is always the fallback). */
    val biometricOn: Boolean = false,
    /** 1.1.4: the mandatory daily Drive copy runs at this local time (default 02:00); it cannot be switched off, only moved. */
    val backupMinuteOfDay: Int = 120,
) {
    val hasAccount: Boolean get() = googleEmail.isNotBlank() || authLocal
    val backupHour: Int get() = (backupMinuteOfDay / 60).coerceIn(0, 23)
    val backupMinute: Int get() = (backupMinuteOfDay % 60).coerceIn(0, 59)
    val rates: com.daftari.app.core.Rates get() = com.daftari.app.core.Rates.parse(ratesJson)
    val hasPassword: Boolean get() = passwordHash.isNotBlank()
}

private val Context.store by preferencesDataStore(name = "daftari_settings")

class SettingsStore(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val signedIn = booleanPreferencesKey("signed_in")
        val role = stringPreferencesKey("role")
        val business = stringPreferencesKey("business")
        val owner = stringPreferencesKey("owner")
        val shopCount = intPreferencesKey("shop_count")
        val currency = stringPreferencesKey("currency")
        val theme = stringPreferencesKey("theme")
        val lock = booleanPreferencesKey("lock")
        val pw = stringPreferencesKey("pw")
        val phone = stringPreferencesKey("phone")
        val email = stringPreferencesKey("email")
        val token = stringPreferencesKey("token")
        val server = stringPreferencesKey("server")
        val late = intPreferencesKey("late_days")
        val backup = stringPreferencesKey("last_backup")
        val demo = booleanPreferencesKey("demo")
        val proUntil = longPreferencesKey("pro_until")
        val trial = longPreferencesKey("trial_started")
        val alerts = booleanPreferencesKey("alerts")
        val bEnc = booleanPreferencesKey("backup_enc")
        val drive = stringPreferencesKey("drive_account")
        val driveAuto = booleanPreferencesKey("drive_auto")
        val lastDrive = longPreferencesKey("last_drive")
        val bookId = stringPreferencesKey("book_id")
        val rates = stringPreferencesKey("rates")
        val curFilled = booleanPreferencesKey("currency_filled")
        val gEmail = stringPreferencesKey("google_email")
        val gName = stringPreferencesKey("google_name")
        val authLocal = booleanPreferencesKey("auth_local")
        val bio = booleanPreferencesKey("biometric_on")
        val backupAt = intPreferencesKey("backup_minute_of_day")
    }

    val settings: Flow<AppSettings> = context.store.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        onboarded = this[K.onboarded] ?: false,
        signedIn = this[K.signedIn] ?: false,
        role = runCatching { Role.valueOf(this[K.role] ?: "") }.getOrDefault(Role.LANDLORD),
        businessName = this[K.business] ?: "",
        ownerName = this[K.owner] ?: "",
        shopCount = this[K.shopCount] ?: 0,
        currency = Currency.of(this[K.currency]),
        theme = runCatching { ThemeMode.valueOf(this[K.theme] ?: "") }.getOrDefault(ThemeMode.DARK_BLUE),
        lockEnabled = this[K.lock] ?: false,
        passwordHash = this[K.pw] ?: "",
        phone = this[K.phone] ?: "",
        email = this[K.email] ?: "",
        authToken = this[K.token] ?: "",
        serverOverride = this[K.server] ?: "",
        lateAfterDays = this[K.late] ?: 3,
        lastBackupAt = this[K.backup]?.toLongOrNull() ?: 0,
        demoData = this[K.demo] ?: false,
        proUntil = this[K.proUntil] ?: 0L,
        trialStartedAt = this[K.trial] ?: 0L,
        alertsEnabled = this[K.alerts] ?: true,
        backupEncrypted = this[K.bEnc] ?: false,
        driveAccount = this[K.drive] ?: "",
        driveAuto = this[K.driveAuto] ?: true,
        lastDriveBackupAt = this[K.lastDrive] ?: 0L,
        bookId = this[K.bookId] ?: "",
        ratesJson = this[K.rates] ?: "",
        currencyFilled = this[K.curFilled] ?: false,
        googleEmail = this[K.gEmail] ?: "",
        googleName = this[K.gName] ?: "",
        authLocal = this[K.authLocal] ?: false,
        biometricOn = this[K.bio] ?: false,
        backupMinuteOfDay = this[K.backupAt] ?: 120,
    )

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.store.edit { p ->
            val n = block(p.toSettings())
            p[K.onboarded] = n.onboarded; p[K.signedIn] = n.signedIn; p[K.role] = n.role.name
            p[K.business] = n.businessName; p[K.owner] = n.ownerName; p[K.shopCount] = n.shopCount
            p[K.currency] = n.currency.code; p[K.theme] = n.theme.name; p[K.lock] = n.lockEnabled; p[K.pw] = n.passwordHash
            p[K.phone] = n.phone; p[K.email] = n.email; p[K.token] = n.authToken; p[K.server] = n.serverOverride
            p[K.late] = n.lateAfterDays; p[K.backup] = n.lastBackupAt.toString(); p[K.demo] = n.demoData
            p[K.proUntil] = n.proUntil; p[K.trial] = n.trialStartedAt; p[K.alerts] = n.alertsEnabled
            p[K.bEnc] = n.backupEncrypted; p[K.drive] = n.driveAccount; p[K.driveAuto] = n.driveAuto; p[K.lastDrive] = n.lastDriveBackupAt
            p[K.bookId] = n.bookId; p[K.rates] = n.ratesJson; p[K.curFilled] = n.currencyFilled
            p[K.gEmail] = n.googleEmail; p[K.gName] = n.googleName; p[K.authLocal] = n.authLocal; p[K.bio] = n.biometricOn
            p[K.backupAt] = n.backupMinuteOfDay.coerceIn(0, 24 * 60 - 1)
        }
    }
}

/** PBKDF2 for the app-lock password (never stored in clear). Format: iterations$saltHex$hashHex. */
object PasswordHasher {
    private const val ITER = 60_000
    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "$ITER$" + salt.hex() + "$" + derive(password, salt, ITER).hex()
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 3) return false
        val iter = parts[0].toIntOrNull() ?: return false
        val salt = parts[1].unhex()
        val expected = parts[2].unhex()
        val got = derive(password, salt, iter)
        return java.security.MessageDigest.isEqual(expected, got)
    }

    private fun derive(pw: String, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
