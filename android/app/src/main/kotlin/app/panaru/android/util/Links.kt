package app.panaru.android.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.panaru.android.R

/** Azioni sui link: copia, condivisione, apertura. */
object Links {
    fun copy(context: Context, urls: List<String>) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(context.getString(R.string.clip_label), urls.joinToString("\n"))
        cm.setPrimaryClip(clip)
    }

    /** Intent di condivisione del sistema per uno o più link (nome file → URL). */
    fun shareIntent(context: Context, links: List<Pair<String, String>>): Intent {
        val text = if (links.size == 1) links[0].second
        else links.joinToString("\n") { (name, url) -> "$name\n$url" }
        val send = Intent(Intent.ACTION_SEND)
            .setType(ClipDescription.MIMETYPE_TEXT_PLAIN)
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, links.singleOrNull()?.first)
        return Intent.createChooser(send, context.getString(R.string.share_links_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

}
