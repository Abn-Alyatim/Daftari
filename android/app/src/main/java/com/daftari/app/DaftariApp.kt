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
    val db = DaftariDb.create(context)
    val repo = LedgerRepository(db, context)
    val settings = SettingsStore(context)
    private val current = settings.settings.stateIn(scope, SharingStarted.Eagerly, null)
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
