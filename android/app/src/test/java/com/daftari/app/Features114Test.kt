package com.daftari.app

import com.daftari.app.backup.GoogleDrive
import com.daftari.app.ui.auth.GoogleErrors
import com.daftari.app.ui.auth.GoogleErrors.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.4: Google sign-in + Drive failure reasons (named, in فصحى). */
class Features114Test {
    @Test fun credentialManagerFailuresAreNamed() {
        assertEquals(Kind.CANCELLED, GoogleErrors.fromCredential("GetCredentialCancellationException", "activity is cancelled by the user."))
        assertEquals(Kind.DEVELOPER, GoogleErrors.fromCredential("GetCredentialCustomException", "[28444] Developer console is not set up correctly."))
        assertEquals(Kind.DEVELOPER, GoogleErrors.fromCredential("NoCredentialException", "During begin sign in, failure response from one tap. 10: [28433] Cannot find a matching credential."))
        assertEquals(Kind.NO_ACCOUNT, GoogleErrors.fromCredential("NoCredentialException", "No credentials available"))
        assertEquals(Kind.PLAY_SERVICES, GoogleErrors.fromCredential("GetCredentialProviderConfigurationException", "getCredentialAsync no provider dependencies found"))
        assertEquals(Kind.INTERRUPTED, GoogleErrors.fromCredential("GetCredentialInterruptedException", null))
        assertEquals(Kind.NETWORK, GoogleErrors.fromCredential("GetCredentialUnknownException", "7: Network error"))
        assertEquals(Kind.CANCELLED, GoogleErrors.fromCredential("GetCredentialUnknownException", "16: Cancelled by user."))
        assertEquals(Kind.UNKNOWN, GoogleErrors.fromCredential("GetCredentialUnknownException", "something odd"))
        // a "110:" or "[28410]" is not developer error 10
        assertEquals(Kind.UNKNOWN, GoogleErrors.fromCredential("GetCredentialUnknownException", "110: other"))
    }

    @Test fun authorizationStatusCodesAreNamed() {
        assertEquals(Kind.DEVELOPER, GoogleErrors.fromStatus(10))
        assertEquals(Kind.NETWORK, GoogleErrors.fromStatus(7))
        assertEquals(Kind.CANCELLED, GoogleErrors.fromStatus(16))
        assertEquals(Kind.CANCELLED, GoogleErrors.fromStatus(12501))
        assertEquals(Kind.PLAY_SERVICES, GoogleErrors.fromStatus(17))
        assertEquals(Kind.UNKNOWN, GoogleErrors.fromStatus(8))
        assertEquals(Kind.UNKNOWN, GoogleErrors.fromStatus(null))
    }

    @Test fun textsNameTheCause() {
        assertNull(GoogleErrors.text(Kind.CANCELLED))
        Kind.values().filter { it != Kind.CANCELLED }.forEach { k ->
            val m = GoogleErrors.text(k, "ربط Google Drive", "رمز 8")
            assertNotNull(m); assertTrue(m!!, m.startsWith("تعذّر ربط Google Drive"))
        }
        assertTrue(GoogleErrors.text(Kind.DEVELOPER)!!.contains("خطأ 10"))
        assertTrue(GoogleErrors.text(Kind.DEVELOPER)!!.contains("SHA-1"))
        assertTrue(GoogleErrors.text(Kind.NO_ACCOUNT)!!.contains("حساب Google"))
        assertTrue(GoogleErrors.text(Kind.UNKNOWN, detail = "رمز 8")!!.contains("(رمز 8)"))
    }

    @Test fun driveAuthFailureMapsToBannerOrNothing() {
        val dev = GoogleDrive.fail(com.google.android.gms.common.api.ApiException(com.google.android.gms.common.api.Status(10)))
        assertTrue(dev is GoogleDrive.Auth.Failed && dev.setupMissing)
        assertEquals(GoogleDrive.Auth.Cancelled, GoogleDrive.fail(com.google.android.gms.common.api.ApiException(com.google.android.gms.common.api.Status(16))))
        val net = GoogleDrive.fail(com.google.android.gms.common.api.ApiException(com.google.android.gms.common.api.Status(7)))
        assertTrue(net is GoogleDrive.Auth.Failed && !net.setupMissing && net.message.contains("الإنترنت"))
    }

    @Test fun driveRestErrorsAreExplained() {
        assertTrue(GoogleDrive.restError(403, "{\"error\":{\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}").contains("غير مفعّل"))
        assertTrue(GoogleDrive.restError(403).contains("403"))
        assertTrue(GoogleDrive.restError(401).contains("من جديد"))
        assertTrue(GoogleDrive.restError(503).contains("503"))
    }

    @Test fun webClientIdIsAWebClientShape() {
        val id = BuildConfig.DF_GOOGLE_WEB_CLIENT_ID
        // empty in CI builds without local.properties; when present it must look like an OAuth client id
        assertTrue(id, id.isEmpty() || Regex("^\\d+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$").matches(id))
        assertEquals("1.1.4", BuildConfig.VERSION_NAME); assertEquals(7, BuildConfig.VERSION_CODE)
    }

    // ------------------------------------------------------------ account gate (first run / returning / upgrade)
    private val google = com.daftari.app.data.AppSettings(onboarded = true, role = com.daftari.app.domain.Role.SHOP_OWNER, googleEmail = "alyateemmadin@gmail.com",
        passwordHash = com.daftari.app.data.PasswordHasher.hash("2468"), lockEnabled = true)

    @Test fun firstRunVersusReturning() {
        val gate = com.daftari.app.core.AuthGate
        assertEquals(com.daftari.app.core.Gate.FIRST_RUN, gate.of(com.daftari.app.data.AppSettings(), unlocked = false))
        assertEquals(com.daftari.app.core.Gate.SET_PASSWORD, gate.of(google.copy(passwordHash = ""), unlocked = false))
        assertEquals(com.daftari.app.core.Gate.RETURNING, gate.of(google, unlocked = false))
        assertEquals(com.daftari.app.core.Gate.MAIN, gate.of(google, unlocked = true))
        assertEquals(com.daftari.app.core.Gate.SETUP, gate.of(google.copy(onboarded = false), unlocked = true))
        assertEquals(com.daftari.app.core.Gate.SET_PASSWORD, gate.of(google, unlocked = false, resettingPassword = true))
        // continued without Google after a failed attempt: password only, still returning
        assertEquals(com.daftari.app.core.Gate.RETURNING, gate.of(google.copy(googleEmail = "", authLocal = true), unlocked = false))
    }

    @Test fun upgradeFrom113IsFirstRunAndKeepsTheDataFiles() {
        // a 1.1.3 user: data set up as «صاحب سوق», a lock password, no Google account yet
        val old = com.daftari.app.data.AppSettings(onboarded = true, role = com.daftari.app.domain.Role.LANDLORD, businessName = "سوق الكحلاني",
            passwordHash = com.daftari.app.data.PasswordHasher.hash("1111"), lockEnabled = true)
        assertEquals(com.daftari.app.core.Gate.FIRST_RUN, com.daftari.app.core.AuthGate.of(old, unlocked = false))
        // the role his data was set up with becomes the legacy workspace: the ORIGINAL file names, nothing moves
        val legacy = old.role
        assertEquals("daftari.db", com.daftari.app.core.Workspaces.dbName(legacy, legacy))
        assertEquals("daftari_log.db", com.daftari.app.core.Workspaces.logDbName(legacy, legacy))
        assertEquals("daftari_reminders", com.daftari.app.core.Workspaces.remindersPrefs(legacy, legacy))
        assertEquals("daftari_log_anchor", com.daftari.app.core.Workspaces.anchorPrefs(legacy, legacy))
        // before the legacy role is recorded the graph opens the same original files
        assertEquals("daftari.db", com.daftari.app.core.Workspaces.dbName(legacy, null))
        // his old Drive copies (no role tag) stay visible in that workspace only
        assertTrue(com.daftari.app.core.Workspaces.belongs("Daftari-2026-10-29-0905.daftari", legacy, legacy))
        assertTrue(!com.daftari.app.core.Workspaces.belongs("Daftari-2026-10-29-0905.daftari", com.daftari.app.domain.Role.SHOP_OWNER, legacy))
    }

    @Test fun passwordHashAndVerify() {
        val h = com.daftari.app.data.PasswordHasher.hash("2468")
        assertTrue(!h.contains("2468")); assertTrue(h.startsWith("60000$"))
        assertTrue(com.daftari.app.data.PasswordHasher.verify("2468", h))
        assertTrue(!com.daftari.app.data.PasswordHasher.verify("2469", h))
        assertTrue(com.daftari.app.data.PasswordHasher.hash("2468") != h) // salted
        assertEquals(null, com.daftari.app.core.AuthGate.passwordProblem("2468", "2468"))
        assertNotNull(com.daftari.app.core.AuthGate.passwordProblem("246", "246"))
        assertNotNull(com.daftari.app.core.AuthGate.passwordProblem("2468", "2467"))
        assertTrue(com.daftari.app.core.AuthGate.sameAccount("AlyateemMadin@gmail.com", " alyateemmadin@gmail.com"))
        assertTrue(!com.daftari.app.core.AuthGate.sameAccount("alyateemmadin@gmail.com", "other@gmail.com"))
        assertTrue(!com.daftari.app.core.AuthGate.sameAccount("", "other@gmail.com"))
    }

    @Test fun roleWorkspacesAreIsolated() {
        val shop = com.daftari.app.domain.Role.SHOP_OWNER; val market = com.daftari.app.domain.Role.LANDLORD
        val w = com.daftari.app.core.Workspaces
        assertTrue(w.dbName(shop, shop) != w.dbName(market, shop))
        assertTrue(w.logDbName(shop, shop) != w.logDbName(market, shop))
        assertTrue(w.remindersPrefs(shop, shop) != w.remindersPrefs(market, shop))
        assertTrue(w.anchorPrefs(shop, shop) != w.anchorPrefs(market, shop))
        assertEquals("daftari_landlord.db", w.dbName(market, shop))
        // Drive copies: each role sees only its own
        val sName = w.backupFileName(shop, "Daftari-2026-10-30-0200.daftari"); val mName = w.backupFileName(market, "Daftari-2026-10-30-0200.daftari")
        assertEquals("Daftari-shop-2026-10-30-0200.daftari", sName); assertEquals("Daftari-market-2026-10-30-0200.daftari", mName)
        assertTrue(w.belongs(sName, shop, shop) && !w.belongs(sName, market, shop))
        assertTrue(w.belongs(mName, market, shop) && !w.belongs(mName, shop, shop))
        // the business profile is parked and restored per role; a new role starts empty (→ its setup screen) under the same owner
        val s = google.copy(businessName = "بقالة مدين", shopCount = 0, bookId = "book-shop")
        val parked = com.daftari.app.core.WorkspaceProfile.decode(com.daftari.app.core.WorkspaceProfile.of(s).encode())!!
        val fresh = com.daftari.app.core.WorkspaceProfile(ownerName = "مدين", bookId = "book-market").applyTo(s, market)
        assertEquals(market, fresh.role); assertTrue(!fresh.onboarded); assertEquals("", fresh.businessName); assertEquals("book-market", fresh.bookId)
        assertEquals("alyateemmadin@gmail.com", fresh.googleEmail); assertEquals(s.passwordHash, fresh.passwordHash) // same account, no re-sign-in
        val back = parked.applyTo(fresh, shop)
        assertEquals("بقالة مدين", back.businessName); assertTrue(back.onboarded); assertEquals("book-shop", back.bookId)
    }

    // ------------------------------------------------------------ the mandatory daily Drive copy at HH:MM
    @Test fun nextBackupTimeAt0200() {
        val aden = java.time.ZoneId.of("Asia/Aden")
        fun at(h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 10, 30, h, m, 0, 0, aden)
        val c = com.daftari.app.core.BackupClock
        assertEquals(at(2, 0).plusDays(0), c.next(at(1, 30), 2, 0))
        assertEquals(java.time.Duration.ofMinutes(30), c.delay(at(1, 30), 2, 0))
        assertEquals(at(2, 0).plusDays(1), c.next(at(2, 0), 2, 0))        // exactly at 02:00 → tomorrow (it just ran)
        assertEquals(at(2, 0).plusDays(1), c.next(at(3, 11), 2, 0))
        assertEquals(java.time.Duration.ofHours(22).plusMinutes(49), c.delay(at(3, 11), 2, 0))
        assertEquals(at(23, 30), c.next(at(22, 0), 23, 30))              // a changed time
        assertEquals("2:00 صباحاً", c.label(120)); assertEquals("11:30 مساءً", c.label(23 * 60 + 30)); assertEquals("12:00 ظهراً", c.label(12 * 60))
        // month/year rollover
        val dec31 = java.time.ZonedDateTime.of(2026, 12, 31, 10, 0, 0, 0, aden)
        assertEquals(java.time.ZonedDateTime.of(2027, 1, 1, 2, 0, 0, 0, aden), c.next(dec31, 2, 0))
    }

    @Test fun developerContactIsTelegramOnly() {
        assertEquals("@abnalyatim", com.daftari.app.core.AppInfo.DEV_TELEGRAM)
        assertEquals("https://t.me/abnalyatim", com.daftari.app.core.AppInfo.DEV_TELEGRAM_URL)
        val fields = com.daftari.app.core.AppInfo::class.java.declaredFields.map { it.name }
        assertTrue(fields.toString(), fields.none { it.contains("WHATSAPP") || it.contains("PHONE") })
    }

    @Test fun noWayToSwitchTheDailyBackupOff() {
        val actions = com.daftari.app.ui.backup.BackupActions::class.java.declaredFields.map { it.name }
        assertTrue(actions.toString(), "onAuto" !in actions && "onUnlink" !in actions && "onTime" in actions)
        val vm = Class.forName("com.daftari.app.AppViewModel").declaredMethods.map { it.name }
        assertTrue("driveAuto" !in vm && "driveUnlink" !in vm && "setBackupTime" in vm)
        val ui = com.daftari.app.ui.backup.BackupUi::class.java.declaredFields.map { it.name }
        assertTrue("auto" !in ui && "backupMinuteOfDay" in ui)
    }
}
