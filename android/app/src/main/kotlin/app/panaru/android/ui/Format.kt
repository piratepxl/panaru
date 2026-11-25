package app.panaru.android.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import app.panaru.android.R
import app.panaru.data.ErrorKind
import app.panaru.data.UploadEntity
import app.panaru.data.toKind
import app.panaru.gokapi.GokapiException
import app.panaru.gokapi.ServerUrl

fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

fun formatDate(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
)

/** Messaggio comprensibile per ogni categoria di errore, con indicazione su cosa fare. */
fun errorText(context: Context, kind: ErrorKind?, detail: String?): String = when (kind) {
    ErrorKind.UNAUTHORIZED -> context.getString(R.string.err_unauthorized)
    ErrorKind.NOT_FOUND -> context.getString(R.string.err_not_found)
    ErrorKind.TOO_LARGE -> context.getString(R.string.err_too_large)
    ErrorKind.RATE_LIMITED -> context.getString(R.string.err_rate_limited)
    ErrorKind.SERVER -> context.getString(R.string.err_server, detail ?: "")
    ErrorKind.NETWORK -> context.getString(R.string.err_network)
    ErrorKind.TLS -> context.getString(R.string.err_tls)
    ErrorKind.SOURCE -> context.getString(R.string.err_source)
    ErrorKind.PROTOCOL -> context.getString(R.string.err_protocol)
    ErrorKind.INTERRUPTED -> context.getString(R.string.err_interrupted)
    ErrorKind.NO_SERVER -> context.getString(R.string.err_no_server)
    null -> detail ?: context.getString(R.string.err_unknown)
}

fun errorText(context: Context, e: GokapiException): String = when (e) {
    is GokapiException.FileTooLarge -> context.getString(R.string.err_too_large)
    else -> errorText(context, e.toKind(), e.message)
}

fun urlError(context: Context, reason: ServerUrl.Reason): String = context.getString(
    when (reason) {
        ServerUrl.Reason.EMPTY -> R.string.url_err_empty
        ServerUrl.Reason.MALFORMED -> R.string.url_err_malformed
        ServerUrl.Reason.UNSUPPORTED_SCHEME -> R.string.url_err_scheme
        ServerUrl.Reason.CREDENTIALS_IN_URL -> R.string.url_err_credentials
        ServerUrl.Reason.INSECURE_PUBLIC_HOST -> R.string.url_err_insecure
    },
)

/** Riga di stato di una voce della cronologia: scadenza, download rimasti, errore. */
fun statusLine(context: Context, u: UploadEntity, nowSec: Long): String {
    if (u.removedFromServer) return context.getString(R.string.status_removed)
    if (u.isExpired(nowSec)) return context.getString(R.string.status_expired)
    val parts = mutableListOf<String>()
    when {
        u.unlimitedTime -> parts += context.getString(R.string.status_no_expiry)
        u.expiresAt != null -> parts += context.getString(R.string.status_expires, formatDate(context, u.expiresAt!! * 1000))
    }
    when {
        u.unlimitedDownloads -> parts += context.getString(R.string.status_unlimited_downloads)
        u.downloadsRemaining != null -> parts += context.resources.getQuantityString(
            R.plurals.status_downloads_left, u.downloadsRemaining!!, u.downloadsRemaining!!,
        )
    }
    if (u.hasPassword) parts += context.getString(R.string.status_password)
    return parts.joinToString(" · ")
}
