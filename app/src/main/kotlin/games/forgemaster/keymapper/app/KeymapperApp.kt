package games.forgemaster.keymapper.app

import android.app.Application
import android.content.Context
import games.forgemaster.keymapper.app.data.HelperRepository
import games.forgemaster.keymapper.app.data.KmDatabase
import games.forgemaster.keymapper.app.data.PairingStore
import games.forgemaster.keymapper.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KeymapperApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.helper.start()
        if (graph.settings.hudEnabled.value && android.provider.Settings.canDrawOverlays(this)) {
            runCatching { OverlayService.start(this) }
        }
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as KeymapperApp).graph

        /** Null before Application.onCreate, which a provider call can beat while the process starts. */
        fun graphOrNull(context: Context): AppGraph? =
            (context.applicationContext as? KeymapperApp)?.takeIf { it::graph.isInitialized }?.graph
    }
}

/** Process-wide singletons. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db = KmDatabase.build(app)
    val settings = Settings(app)
    val pairing = PairingStore.get(app)
    val helper = HelperRepository(db, settings, pairing, scope)
}
