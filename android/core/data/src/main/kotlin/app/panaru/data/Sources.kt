package app.panaru.data

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.panaru.gokapi.ChunkSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** Metadati di un file da caricare, ricavati dall'URI. */
data class SourceInfo(val uri: String, val name: String, val size: Long, val mimeType: String?)

/** Sorgente aperta per l'upload; [close] libera eventuali file temporanei. */
interface OpenedSource : ChunkSource, AutoCloseable

interface SourceOpener {
    fun resolve(uri: String): SourceInfo
    fun open(uri: String): OpenedSource
}

/**
 * Legge gli URI `content://` / `file://` ricevuti da Share Sheet o selettore documenti
 * **senza copiarli**: ogni chunk si apre con un file descriptor posizionato sull'offset.
 * Solo se il provider non dichiara la dimensione (raro: stream generati al volo) il contenuto
 * viene copiato in una cartella di cache privata, eliminata a fine upload.
 */
class ContentSourceOpener(
    private val resolver: ContentResolver,
    private val cacheDir: File,
) : SourceOpener {

    override fun resolve(uri: String): SourceInfo {
        val u = Uri.parse(uri)
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) size = statSize(u)
        val finalName = sanitizeName(name ?: u.lastPathSegment ?: "file")
        val mime = resolver.getType(u)?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(finalName.substringAfterLast('.', "").lowercase())
        return SourceInfo(uri, finalName, size, mime)
    }

    override fun open(uri: String): OpenedSource {
        val u = Uri.parse(uri)
        val size = statSize(u)
        return if (size >= 0) DescriptorSource(resolver, u, size) else copyToCache(u)
    }

    private fun statSize(u: Uri): Long = try {
        resolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: -1
    } catch (e: Exception) {
        if (e is SecurityException || e is IOException) throw e
        -1
    }

    private fun copyToCache(u: Uri): OpenedSource {
        val dir = File(cacheDir, "pending-uploads").apply { mkdirs() }
        val tmp = File.createTempFile("upload", ".bin", dir)
        try {
            (resolver.openInputStream(u) ?: throw IOException("Cannot open $u")).use { input ->
                tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return object : OpenedSource {
            override val size: Long = tmp.length()
            override fun open(offset: Long): InputStream = FileInputStream(tmp).apply { channel.position(offset) }
            override fun close() { tmp.delete() }
        }
    }

    private class DescriptorSource(
        private val resolver: ContentResolver,
        private val uri: Uri,
        override val size: Long,
    ) : OpenedSource {
        override fun open(offset: Long): InputStream {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            try {
                stream.channel.position(offset) // accesso diretto: nessuna rilettura dall'inizio
                return stream
            } catch (e: IOException) {
                stream.close()
            }
            // Descrittore non posizionabile (pipe): si salta fino all'offset.
            val fallback = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            var toSkip = offset
            while (toSkip > 0) {
                val skipped = fallback.skip(toSkip)
                if (skipped <= 0) {
                    if (fallback.read() < 0) throw IOException("Unexpected end of stream")
                    toSkip--
                } else toSkip -= skipped
            }
            return fallback
        }

        override fun close() = Unit
    }

    companion object {
        /** Rimuove separatori di percorso e caratteri di controllo dai nomi forniti da altre app. */
        fun sanitizeName(raw: String): String =
            raw.substringAfterLast('/').substringAfterLast('\\')
                .filter { it >= ' ' && it != '\u007f' }
                .trim().ifEmpty { "file" }.take(255)
    }
}
