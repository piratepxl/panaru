package app.panaru.android.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.panaru.android.R
import app.panaru.android.ui.errorText
import app.panaru.data.ErrorKind
import app.panaru.data.ServerRepository
import app.panaru.data.toKind
import app.panaru.gokapi.GokapiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Download in corso o in attesa. */
data class DownloadItem(
    val key: Long,
    val serverId: Long,
    val fileId: String,
    val name: String,
    val target: Uri,
    val done: Long = 0,
    val total: Long = -1,
)

/**
 * Download via API (`/files/download/{id}`, permesso DOWNLOAD) verso un documento scelto
 * dall'utente con il selettore di sistema. Sequenziali, eseguiti dal [TransferService].
 */
class DownloadQueue(context: Context, private val servers: ServerRepository) {
    private val app = context.applicationContext
    private val keys = AtomicLong()
    private val _state = MutableStateFlow<List<DownloadItem>>(emptyList())
    val state: StateFlow<List<DownloadItem>> = _state.asStateFlow()

    fun enqueue(serverId: Long, fileId: String, name: String, target: Uri) {
        _state.update { it + DownloadItem(keys.incrementAndGet(), serverId, fileId, name, target) }
        TransferService.start(app)
    }

    suspend fun runUntilIdle(context: Context) {
        while (true) {
            val item = _state.value.firstOrNull() ?: return
            try {
                download(item)
                notifyDone(context, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GokapiException) {
                notifyFailed(context, item, errorText(context, e.toKind(), e.message))
            } catch (e: Exception) {
                notifyFailed(context, item, errorText(context, ErrorKind.SOURCE, e.message))
            } finally {
                _state.update { list -> list.filterNot { it.key == item.key } }
            }
        }
    }

    private suspend fun download(item: DownloadItem) {
        val client = servers.client(item.serverId) ?: throw GokapiException.NotFound("Server removed")
        val out = app.contentResolver.openOutputStream(item.target, "wt") ?: throw IOException("Cannot write target")
        out.use { stream ->
            client.download(item.fileId, stream) { done, total ->
                _state.update { list -> list.map { if (it.key == item.key) it.copy(done = done, total = total) else it } }
            }
        }
    }

    private fun notifyDone(context: Context, item: DownloadItem) {
        val open = Intent(Intent.ACTION_VIEW).setData(item.target)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(
            context, item.key.toInt(), Intent.createChooser(open, null),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        post(context, item, context.getString(R.string.notif_download_done, item.name), pi)
    }

    private fun notifyFailed(context: Context, item: DownloadItem, reason: String) =
        post(context, item, context.getString(R.string.notif_download_failed, item.name), null, reason)

    private fun post(context: Context, item: DownloadItem, title: String, pi: android.app.PendingIntent?, text: String? = null) {
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_panaru)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        Notifications.post(context, 50_000 + item.key.toInt(), n)
    }
}
