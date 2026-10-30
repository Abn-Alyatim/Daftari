package com.daftari.app

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.daftari.app.backup.DriveWorker
import com.daftari.app.core.WorkspaceProfile
import com.daftari.app.core.WorkspaceStore
import com.daftari.app.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.1.4 on a (Robolectric) phone: the workspace registry and the daily Drive schedule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Workspace114Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun legacyRoleIsFixedOnceAndSwitchingKeepsBothProfiles() {
        val w = WorkspaceStore(ctx)
        assertNull(w.legacy())
        w.initLegacy(Role.LANDLORD)            // the 1.1.3 data was a market
        w.initLegacy(Role.SHOP_OWNER)          // a later call never re-labels his data
        assertEquals(Role.LANDLORD, w.legacy()); assertEquals(Role.LANDLORD, w.active())
        assertTrue(w.has(Role.LANDLORD)); assertFalse(w.has(Role.SHOP_OWNER))
        w.saveProfile(Role.LANDLORD, WorkspaceProfile(onboarded = true, businessName = "سوق الكحلاني", shopCount = 27))
        w.setActive(Role.SHOP_OWNER)
        assertTrue(w.has(Role.SHOP_OWNER))
        assertEquals("سوق الكحلاني", w.profile(Role.LANDLORD)!!.businessName)
        assertNull(w.profile(Role.SHOP_OWNER))
    }

    @Test fun unlockGrantIsOneShotAndExpires() {
        val w = WorkspaceStore(ctx)
        w.grantUnlock(10_000); assertTrue(w.consumeGrant(5_000)); assertFalse(w.consumeGrant(5_000))
        w.grantUnlock(10_000); assertFalse(w.consumeGrant(20_000))
    }

    @Test fun changingTheTimeReschedulesTheSingleDailyJob() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build())
        val wm = WorkManager.getInstance(ctx)
        DriveWorker.schedule(ctx, 120, replace = false)
        val first = wm.getWorkInfosForUniqueWork(DriveWorker.NAME).get().single { it.state == WorkInfo.State.ENQUEUED }
        DriveWorker.schedule(ctx, 120, replace = false)   // app start again: the queued run is kept
        assertEquals(first.id, wm.getWorkInfosForUniqueWork(DriveWorker.NAME).get().single { it.state == WorkInfo.State.ENQUEUED }.id)
        DriveWorker.schedule(ctx, 23 * 60, replace = true) // he moved it to 23:00: the old run is replaced
        val infos = wm.getWorkInfosForUniqueWork(DriveWorker.NAME).get()
        val queued = infos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, queued.size); assertTrue(queued.single().id != first.id)
        // the replaced run is cancelled (and WorkManager may already have pruned it)
        wm.getWorkInfoById(first.id).get()?.let { assertEquals(WorkInfo.State.CANCELLED, it.state) }
    }
}
