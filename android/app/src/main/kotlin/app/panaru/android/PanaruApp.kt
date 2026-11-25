package app.panaru.android

import android.app.Application
import android.content.Context
import app.panaru.android.transfer.DownloadQueue
import app.panaru.android.transfer.Notifications
import app.panaru.data.ContentSourceOpener
import app.panaru.data.KeystoreSecretBox
import app.panaru.data.PanaruDatabase
import app.panaru.data.ServerRepository
import app.panaru.data.SettingsStore
import app.panaru.data.UploadEngine
import app.panaru.gokapi.GokapiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Grafo delle dipendenze dell'app: semplice, esplicito, senza framework DI. */
class AppGraph(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = PanaruDatabase.create(context)
    val settings = SettingsStore(context)
    private val http = GokapiClient.defaultHttpClient()
    val servers = ServerRepository(database.servers(), KeystoreSecretBox(), http)
    val sources = ContentSourceOpener(context.contentResolver, context.cacheDir)
    val uploads = UploadEngine(
        uploads = database.uploads(),
        servers = servers,
        settings = settings.settings,
        sources = sources,
    )
    val downloads = DownloadQueue(context, servers)
}

class PanaruApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        // Gli upload rimasti "in corso" da un processo precedente sono stati interrotti.
        graph.appScope.launch { graph.uploads.recoverInterrupted() }
    }
}

val Context.graph: AppGraph get() = (applicationContext as PanaruApp).graph
