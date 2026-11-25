package app.panaru.android.transfer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.panaru.android.R
import app.panaru.android.graph
import app.panaru.android.ui.errorText
import app.panaru.android.util.Links
import app.panaru.data.UploadEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servizio in foreground (tipo `dataSync`) che esegue la coda di upload e i download.
 *
 * Gli URI ricevuti dallo Share Sheet vengono passati qui con `FLAG_GRANT_READ_URI_PERMISSION`
 * nel `ClipData`: il sistema concede la lettura al servizio finché resta attivo, quindi i file
 * vengono letti direttamente dalla sorgente, senza copiarli.
 */
class TransferService : LifecycleService() {
    private var runner: Job? = null
    private val kicked = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = Notifications.progress(this, getString(R.string.notif_preparing), null, null)
        ServiceCompat.startForeground(
            this, Notifications.ID_FOREGROUND, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        kicked.set(true)
        if (runner?.isActive != true) runner = lifecycleScope.launch { runAll() }
        return START_NOT_STICKY
    }

    @OptIn(FlowPreview::class)
    private suspend fun runAll() {
        val g = graph
        coroutineScope {
            val progressJob = launch {
                combine(g.database.uploads().observeActive(), g.uploads.live, g.downloads.state) { active, live, dl ->
                    Triple(active, live, dl)
                }.sample(500).collect { (active, live, dl) ->
                    val total = active.sumOf { live[it.id]?.total ?: it.sizeBytes } + dl.sumOf { it.total.coerceAtLeast(0) }
                    val sent = active.sumOf { live[it.id]?.sent ?: 0 } + dl.sumOf { it.done }
                    val percent = if (total > 0) ((sent * 100) / total).toInt() else null
                    val title = when {
                        active.isNotEmpty() -> resources.getQuantityString(R.plurals.notif_uploading, active.size, active.size)
                        dl.isNotEmpty() -> resources.getQuantityString(R.plurals.notif_downloading, dl.size, dl.size)
                        else -> getString(R.string.notif_preparing)
                    }
                    val text = active.firstOrNull { it.id in live }?.fileName ?: dl.firstOrNull()?.name
                    Notifications.post(
                        this@TransferService, Notifications.ID_FOREGROUND,
                        Notifications.progress(this@TransferService, title, text, percent),
                    )
                }
            }
            val eventsJob = launch {
                g.uploads.events.collect { event ->
                    when (event) {
                        is UploadEvent.Completed -> {
                            val copy = g.settings.settings.value.autoCopyLink
                            if (copy) event.upload.url?.let { Links.copy(this@TransferService, listOf(it)) }
                            Notifications.linkReady(this@TransferService, event.upload, copy)
                        }
                        is UploadEvent.Failed -> Notifications.failed(
                            this@TransferService,
                            getString(R.string.notif_failed, event.upload.fileName),
                            errorText(this@TransferService, event.upload.errorKind, event.upload.errorDetail),
                        )
                    }
                }
            }
            // Upload e download in parallelo; si ripete se nel frattempo è arrivato nuovo lavoro.
            do {
                kicked.set(false)
                coroutineScope {
                    launch { g.uploads.runUntilIdle() }
                    launch { g.downloads.runUntilIdle(this@TransferService) }
                }
            } while (kicked.get() || g.downloads.state.value.isNotEmpty() || g.database.uploads().nextQueued() != null)
            progressJob.cancel()
            eventsJob.cancel()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15: limite di tempo dei servizi dataSync. Gli upload in corso risultano interrotti. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runner?.cancel()
        stopSelf()
    }

    companion object {
        /** Avvia (o risveglia) il servizio; gli [uris] ricevono un permesso di lettura per il servizio. */
        fun start(context: Context, uris: List<Uri> = emptyList()) {
            val intent = Intent(context, TransferService::class.java)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri(null, uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
