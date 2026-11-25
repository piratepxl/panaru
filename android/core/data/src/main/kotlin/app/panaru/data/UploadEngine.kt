package app.panaru.data

import app.panaru.gokapi.GokapiException
import app.panaru.gokapi.UploadOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Avanzamento in tempo reale, solo in memoria (un upload interrotto riparte comunque da zero). */
data class LiveProgress(val sent: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
}

sealed interface UploadEvent {
    data class Completed(val upload: UploadEntity) : UploadEvent
    data class Failed(val upload: UploadEntity) : UploadEvent
}

/**
 * Coda di upload persistente.
 *
 * - Le voci sono salvate in Room: la coda sopravvive al riavvio (le voci interrotte diventano
 *   "non riuscite" e si possono ritentare, Gokapi non permette di riprendere un upload).
 * - Al massimo `maxConcurrentUploads` upload in parallelo; ogni file è caricato a chunk.
 * - Le password restano solo in memoria: non vengono mai scritte su disco.
 */
class UploadEngine(
    private val uploads: UploadDao,
    private val servers: ServerRepository,
    private val settings: StateFlow<PanaruSettings>,
    private val sources: SourceOpener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<Long, LiveProgress>> = _live.asStateFlow()

    private val _events = MutableSharedFlow<UploadEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<UploadEvent> = _events.asSharedFlow()

    private val passwords = ConcurrentHashMap<Long, String>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val userCancelled = ConcurrentHashMap.newKeySet<Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val runLock = Mutex()

    /** Da chiamare all'avvio del processo: gli upload rimasti "in corso" sono stati interrotti. */
    suspend fun recoverInterrupted(): Int = if (runLock.isLocked) 0 else uploads.markInterrupted()

    suspend fun enqueue(serverId: Long, files: List<SourceInfo>, options: UploadOptions): List<Long> {
        val server = servers.byId(serverId) ?: return emptyList()
        val now = clock()
        val ids = uploads.insertAll(files.mapIndexed { i, f ->
            UploadEntity(
                serverId = server.id,
                serverName = server.name,
                sourceUri = f.uri,
                fileName = f.name,
                sizeBytes = f.size.coerceAtLeast(0),
                mimeType = f.mimeType,
                expiryDays = options.expiryDays,
                allowedDownloads = options.allowedDownloads,
                hasPassword = !options.password.isNullOrEmpty(),
                state = UploadState.QUEUED,
                createdAt = now + i, // conserva l'ordine di selezione
            )
        })
        options.password?.takeIf { it.isNotEmpty() }?.let { pw -> ids.forEach { passwords[it] = pw } }
        wake.trySend(Unit)
        return ids
    }

    suspend fun cancel(id: Long) {
        userCancelled += id
        if (uploads.transition(id, UploadState.QUEUED, UploadState.CANCELLED) == 0) {
            jobs[id]?.cancel()
        } else {
            userCancelled -= id
            passwords.remove(id)
        }
    }

    /**
     * Rimette in coda un upload non riuscito o annullato.
     * @return `false` se l'upload era protetto da password e la password non è più in memoria.
     */
    suspend fun retry(id: Long): Boolean {
        val item = uploads.byId(id) ?: return false
        if (item.state != UploadState.FAILED && item.state != UploadState.CANCELLED) return false
        if (item.hasPassword && !passwords.containsKey(id)) return false
        uploads.update(item.copy(state = UploadState.QUEUED, bytesSent = 0, errorKind = null, errorDetail = null))
        wake.trySend(Unit)
        return true
    }

    /** Esegue la coda finché ci sono upload in attesa o in corso. Usato dal servizio in foreground. */
    suspend fun runUntilIdle() = runLock.withLock {
        coroutineScope {
            while (true) {
                val limit = settings.value.maxConcurrentUploads
                if (jobs.size < limit) {
                    val next = uploads.nextQueued()
                    if (next != null) {
                        if (uploads.transition(next.id, UploadState.QUEUED, UploadState.UPLOADING) == 1) {
                            jobs[next.id] = launch { process(next.copy(state = UploadState.UPLOADING)) }
                        }
                        continue
                    }
                    if (jobs.isEmpty()) break
                }
                withTimeoutOrNull(1_000) { wake.receive() }
            }
        }
    }

    val hasWork: Boolean get() = jobs.isNotEmpty()

    private suspend fun process(item: UploadEntity) {
        val id = item.id
        var opened: OpenedSource? = null
        try {
            val client = servers.client(item.serverId)
                ?: return fail(item, ErrorKind.NO_SERVER, null)
            if (item.hasPassword && !passwords.containsKey(id)) return fail(item, ErrorKind.INTERRUPTED, null)
            val config = servers.config(item.serverId, client)
            val source = try {
                sources.open(item.sourceUri)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return fail(item, ErrorKind.SOURCE, e.message)
            }
            opened = source
            _live.update { it + (id to LiveProgress(0, source.size)) }
            val file = client.upload(
                source = source,
                fileName = item.fileName,
                mimeType = item.mimeType,
                options = UploadOptions(item.expiryDays, item.allowedDownloads, passwords[id]),
                config = config,
            ) { sent, total ->
                _live.update { it + (id to LiveProgress(sent, total)) }
            }
            val done = item.copy(
                state = UploadState.COMPLETED,
                sizeBytes = source.size,
                bytesSent = source.size,
                fileId = file.id,
                url = file.urlDownload,
                hotlinkUrl = file.urlHotlink.ifBlank { null },
                expiresAt = file.expireAt.takeIf { it > 0 },
                unlimitedTime = file.unlimitedTime,
                unlimitedDownloads = file.unlimitedDownloads,
                downloadsRemaining = file.downloadsRemaining,
                completedAt = clock(),
            )
            uploads.update(done)
            passwords.remove(id)
            _events.tryEmit(UploadEvent.Completed(done))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val kind = if (userCancelled.remove(id)) null else ErrorKind.INTERRUPTED
                if (kind == null) {
                    uploads.update(item.copy(state = UploadState.CANCELLED, bytesSent = 0))
                    passwords.remove(id)
                } else {
                    uploads.update(item.copy(state = UploadState.FAILED, errorKind = kind))
                }
            }
            throw e
        } catch (e: GokapiException) {
            fail(item, e.toKind(), e.message)
        } catch (e: IOException) {
            fail(item, ErrorKind.SOURCE, e.message)
        } catch (e: SecurityException) {
            fail(item, ErrorKind.SOURCE, e.message)
        } finally {
            runCatching { opened?.close() }
            _live.update { it - id }
            jobs.remove(id)
            wake.trySend(Unit)
        }
    }

    private suspend fun fail(item: UploadEntity, kind: ErrorKind, detail: String?) {
        val failed = item.copy(state = UploadState.FAILED, errorKind = kind, errorDetail = detail?.take(300))
        withContext(NonCancellable) { uploads.update(failed) }
        _events.tryEmit(UploadEvent.Failed(failed))
    }

    // ------------------------------------------------------------ cronologia

    enum class SyncResult { OK, NO_PERMISSION, FAILED }

    /**
     * Sincronizza la cronologia con i file presenti sul server (`/files/list`, permesso VIEW):
     * - i file caricati da altri dispositivi (o dal web) vengono aggiunti alla cronologia;
     * - le voci note vengono aggiornate (download rimasti, scadenza);
     * - i file non più presenti (scaduti o eliminati) vengono segnati come tali.
     * Le voci rimosse dall'utente restano nascoste e non vengono riaggiunte.
     */
    suspend fun syncServer(serverId: Long): SyncResult {
        val server = servers.byId(serverId) ?: return SyncResult.FAILED
        val client = servers.client(serverId) ?: return SyncResult.FAILED
        val remote = try {
            client.listFiles().filterNot { it.isPendingDeletion }
        } catch (e: GokapiException.Unauthorized) {
            return SyncResult.NO_PERMISSION
        } catch (e: GokapiException) {
            return SyncResult.FAILED
        }
        val local = uploads.withFileIdOn(serverId).associateBy { it.fileId }
        val now = clock()
        val missing = mutableListOf<UploadEntity>()
        for (f in remote) {
            val u = local[f.id]
            if (u == null) {
                val uploaded = if (f.uploadDate > 0) f.uploadDate * 1000 else now
                missing += UploadEntity(
                    serverId = serverId,
                    serverName = server.name,
                    sourceUri = "",
                    fileName = f.name,
                    sizeBytes = f.sizeBytes,
                    mimeType = f.contentType.ifBlank { null },
                    expiryDays = 0,
                    allowedDownloads = 0,
                    hasPassword = f.isPasswordProtected,
                    state = UploadState.COMPLETED,
                    bytesSent = f.sizeBytes,
                    fileId = f.id,
                    url = f.urlDownload,
                    hotlinkUrl = f.urlHotlink.ifBlank { null },
                    expiresAt = f.expireAt.takeIf { it > 0 },
                    unlimitedTime = f.unlimitedTime,
                    unlimitedDownloads = f.unlimitedDownloads,
                    downloadsRemaining = f.downloadsRemaining,
                    createdAt = uploaded,
                    completedAt = uploaded,
                )
            } else {
                val updated = u.copy(
                    removedFromServer = false,
                    downloadsRemaining = f.downloadsRemaining,
                    expiresAt = f.expireAt.takeIf { it > 0 } ?: u.expiresAt,
                    unlimitedDownloads = f.unlimitedDownloads,
                    unlimitedTime = f.unlimitedTime,
                    hasPassword = f.isPasswordProtected,
                )
                if (updated != u) uploads.update(updated)
            }
        }
        if (missing.isNotEmpty()) uploads.insertAll(missing)
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        for (u in local.values) {
            if (u.state == UploadState.COMPLETED && !u.removedFromServer && u.fileId !in remoteIds) {
                uploads.update(u.copy(removedFromServer = true))
            }
        }
        return SyncResult.OK
    }

    /** Elimina il file dal server (permesso DELETE) e lo segna nella cronologia. */
    suspend fun deleteFromServer(id: Long) {
        val item = uploads.byId(id) ?: return
        val fileId = item.fileId ?: return
        val client = servers.client(item.serverId) ?: throw GokapiException.NotFound("Server removed")
        try {
            client.delete(fileId)
        } catch (e: GokapiException.NotFound) {
            // già eliminato o scaduto: lo stato locale va comunque aggiornato
        }
        uploads.update(item.copy(removedFromServer = true))
    }
}

fun GokapiException.toKind(): ErrorKind = when (this) {
    is GokapiException.Unauthorized -> ErrorKind.UNAUTHORIZED
    is GokapiException.NotFound -> ErrorKind.NOT_FOUND
    is GokapiException.FileTooLarge -> ErrorKind.TOO_LARGE
    is GokapiException.RateLimited -> ErrorKind.RATE_LIMITED
    is GokapiException.Server -> ErrorKind.SERVER
    is GokapiException.Network -> ErrorKind.NETWORK
    is GokapiException.Tls -> ErrorKind.TLS
    is GokapiException.SourceUnavailable -> ErrorKind.SOURCE
    is GokapiException.Protocol -> ErrorKind.PROTOCOL
}
