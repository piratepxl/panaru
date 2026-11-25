package app.panaru.android.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import app.panaru.android.R
import app.panaru.android.util.Links

/** Azione "Copia link" dalla notifica "Link pronto". */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        Links.copy(context, listOf(url))
        // Da Android 13 il sistema conferma da sé la copia negli appunti.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, -1)
        if (id >= 0) NotificationManagerCompat.from(context).cancel(id)
    }

    companion object {
        const val ACTION_COPY = "app.panaru.action.COPY_LINK"
        const val EXTRA_URL = "url"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
