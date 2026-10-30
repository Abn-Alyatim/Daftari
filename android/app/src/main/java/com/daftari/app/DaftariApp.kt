package com.daftari.app

import android.app.Application
import android.content.Context
import com.daftari.app.data.DaftariApi
import com.daftari.app.data.DaftariDb
import com.daftari.app.data.LedgerRepository
import com.daftari.app.data.SettingsStore
import com.daftari.app.export.Exporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** Manual wiring (no DI framework: the app is small and starts fast on cheap phones). */
class AppGraph(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 1.1.4: which role's book is open (a role switch restarts the process, so these are fixed for its life). */
    val workspaces = com.daftari.app.core.WorkspaceStore(context)
    private val legacy = workspaces.legacy()
    val role: com.daftari.app.domain.Role? = workspaces.active()
    private val ws = role ?: legacy
    val db = if (ws == null) DaftariDb.create(context) else DaftariDb.create(context, name = com.daftari.app.core.Workspaces.dbName(ws, legacy))
    val settings = SettingsStore(context)
    private val current = settings.settings.stateIn(scope, SharingStarted.Eagerly, null)
    /** «السجل»: its own database file, never wiped (one per workspace). */
    val auditDb = if (ws == null) com.daftari.app.audit.AuditDb.create(context) else com.daftari.app.audit.AuditDb.create(context, name = com.daftari.app.core.Workspaces.logDbName(ws, legacy))
    val audit = com.daftari.app.audit.AuditLog(auditDb.dao(), if (ws == null) com.daftari.app.audit.PrefsAnchor(context) else com.daftari.app.audit.PrefsAnchor(context, com.daftari.app.core.Workspaces.anchorPrefs(ws, legacy)))
    val repo = LedgerRepository(db, context, audit) {
        val s = current.value ?: settings.current()
        com.daftari.app.data.Actor(listOf(s.ownerName.ifBlank { "المالك" }, s.phone).filter { it.isNotBlank() }.joinToString(" · "),
            (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim())
    }
    val reminders = if (ws == null) com.daftari.app.backup.ReminderStore(context) else com.daftari.app.backup.ReminderStore(context, com.daftari.app.core.Workspaces.remindersPrefs(ws, legacy))
    val backupKeys = com.daftari.app.backup.BackupKeys(context)
    val api = DaftariApi(
        baseUrl = { current.value?.serverOverride?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL },
        token = { current.value?.authToken.orEmpty() },
    )
    val exporter = Exporter(context)
}

class DaftariApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
