package app.panaru.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.panaru.android.R
import app.panaru.android.graph
import app.panaru.android.transfer.TransferService
import app.panaru.data.ConnectionCheck
import app.panaru.data.LiveProgress
import app.panaru.data.PanaruSettings
import app.panaru.data.ServerEntity
import app.panaru.data.UploadEngine
import app.panaru.data.UploadEntity
import app.panaru.gokapi.GokapiException
import app.panaru.gokapi.GokapiFile
import app.panaru.gokapi.ServerUrl
import app.panaru.gokapi.UploadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stato dell'elenco dei file presenti su un server (`/files/list`). */
sealed interface RemoteFiles {
    data object Loading : RemoteFiles
    data class Loaded(val files: List<GokapiFile>) : RemoteFiles
    data class Error(val error: GokapiException) : RemoteFiles
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    val servers: StateFlow<List<ServerEntity>> =
        g.servers.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history: StateFlow<List<UploadEntity>> =
        g.database.uploads().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val live: StateFlow<Map<Long, LiveProgress>> = g.uploads.live
    val settings: StateFlow<PanaruSettings> = g.settings.settings

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }
    fun say(text: String) { _message.value = text }

    // ------------------------------------------------------------------ upload

    fun upload(uris: List<Uri>, serverId: Long, options: UploadOptions) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val infos = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    // I file scelti dal selettore restano leggibili anche dopo un riavvio (retry).
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { g.sources.resolve(uri.toString()) }.getOrNull()
                }
            }
            if (infos.isEmpty()) return@launch
            g.uploads.enqueue(serverId, infos, options)
            TransferService.start(ctx, uris)
        }
    }

    fun cancel(id: Long) = viewModelScope.launch { g.uploads.cancel(id) }

    fun retry(id: Long, onPasswordLost: () -> Unit) = viewModelScope.launch {
        if (g.uploads.retry(id)) TransferService.start(getApplication()) else onPasswordLost()
    }

    fun removeFromHistory(id: Long) = viewModelScope.launch { g.database.uploads().delete(id) }
    fun clearHistory() = viewModelScope.launch { g.database.uploads().clearHistory() }

    fun deleteFromServer(id: Long, onError: (GokapiException) -> Unit) = viewModelScope.launch {
        try {
            g.uploads.deleteFromServer(id)
        } catch (e: GokapiException) {
            onError(e)
        }
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var lastSync = 0L

    /**
     * Sincronizza la cronologia con i file di tutti i server (anche quelli caricati da altri
     * dispositivi). [manual] = richiesta dall'utente: mostra l'esito e ignora l'intervallo minimo.
     */
    fun sync(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (_refreshing.value || (!manual && now - lastSync < SYNC_INTERVAL_MS)) return
        lastSync = now
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val results = g.servers.all().map { it.name to g.uploads.syncServer(it.id) }
                if (manual) {
                    val noView = results.filter { it.second == UploadEngine.SyncResult.NO_PERMISSION }.map { it.first }
                    val failed = results.filter { it.second == UploadEngine.SyncResult.FAILED }.map { it.first }
                    val ctx = getApplication<Application>()
                    when {
                        noView.isNotEmpty() -> say(ctx.getString(R.string.sync_need_view, noView.joinToString()))
                        failed.isNotEmpty() -> say(ctx.getString(R.string.sync_failed, failed.joinToString()))
                        else -> say(ctx.getString(R.string.sync_done))
                    }
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    init {
        sync()
    }

    // ----------------------------------------------------------------- server

    suspend fun checkServer(url: ServerUrl.Result.Valid, key: String): ConnectionCheck = g.servers.check(url, key)

    suspend fun checkSaved(id: Long, url: ServerUrl.Result.Valid): ConnectionCheck = g.servers.checkSaved(id, url)

    fun saveServer(id: Long?, name: String, url: ServerUrl.Result.Valid, key: String, makeDefault: Boolean, done: () -> Unit) =
        viewModelScope.launch {
            if (id == null) {
                g.servers.add(name, url, key, makeDefault)
                lastSync = 0
                sync() // mostra subito i file già presenti sul nuovo server
            } else {
                g.servers.update(id, name, url, key.ifBlank { null })
                if (makeDefault) g.servers.setDefault(id)
            }
            done()
        }

    fun setDefault(id: Long) = viewModelScope.launch { g.servers.setDefault(id) }
    fun deleteServer(id: Long) = viewModelScope.launch { g.servers.delete(id) }

    // ------------------------------------------------------- file sul server

    private val _remote = MutableStateFlow<RemoteFiles>(RemoteFiles.Loading)
    val remote: StateFlow<RemoteFiles> = _remote.asStateFlow()

    fun loadRemote(serverId: Long) = viewModelScope.launch {
        _remote.value = RemoteFiles.Loading
        _remote.value = try {
            val client = g.servers.client(serverId) ?: throw GokapiException.NotFound()
            RemoteFiles.Loaded(client.listFiles().sortedByDescending { it.uploadDate })
        } catch (e: GokapiException) {
            RemoteFiles.Error(e)
        }
    }

    fun deleteRemote(serverId: Long, fileId: String) = viewModelScope.launch {
        try {
            g.servers.client(serverId)?.delete(fileId)
            loadRemote(serverId)
        } catch (e: GokapiException) {
            say(errorText(getApplication(), e))
        }
    }

    fun download(serverId: Long, file: GokapiFile, target: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        g.downloads.enqueue(serverId, file.id, file.name, target)
    }

    // ------------------------------------------------------------ impostazioni

    fun updateSettings(transform: (PanaruSettings) -> PanaruSettings) = g.settings.update(transform)

    private companion object {
        const val SYNC_INTERVAL_MS = 30_000L
    }
}
