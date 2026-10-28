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
) {
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
