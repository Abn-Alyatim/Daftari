package com.daftari.app

import com.daftari.app.data.Backup
import com.daftari.app.data.Demo
import com.daftari.app.data.PasswordHasher
import com.daftari.app.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupTest {
    @Test fun roundTrip() {
        val seed = Demo.forRole(Role.SHOP_OWNER, LocalDate.of(2026, 10, 28))
        val parties = seed.parties.mapIndexed { i, p -> p.copy(id = i + 1L, category = if (i == 0) "الحارة" else "") }
        val entries = seed.entries(parties.map { it.id })
        val json = Backup.toJson(parties, entries, "بقالة", "عبدالله", "SHOP_OWNER")
        val back = Backup.parse(json)
        assertEquals(parties.size, back.parties.size)
        assertEquals(entries.size, back.entries.size)
        assertEquals("الحارة", back.parties[0].category)
        assertEquals(entries.sumOf { it.amount }, back.entries.sumOf { it.amount })
    }

    @Test fun rejectsOtherFiles() {
        val r = runCatching { Backup.parse("{\"x\":1}") }
        assertTrue(r.isFailure)
    }

    @Test fun passwordHash() {
        val h = PasswordHasher.hash("4321")
        assertTrue(PasswordHasher.verify("4321", h))
        assertFalse(PasswordHasher.verify("1234", h))
    }
}
