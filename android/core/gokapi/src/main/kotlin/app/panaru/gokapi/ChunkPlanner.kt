package app.panaru.gokapi

/** Un intervallo di byte da inviare con `/chunk/add`. */
data class Chunk(val offset: Long, val length: Long)

/**
 * Suddivisione di un file in chunk secondo i vincoli di Gokapi:
 * - ogni chunk ≥ 5 MB salvo l'ultimo (`isChunkMinChunkSize`);
 * - la `Content-Length` della richiesta non può superare `MaxFilesize`;
 * - la dimensione preferita è `MaxChunksize` del server.
 */
object ChunkPlanner {
    const val MB: Long = 1024L * 1024L
    const val MIN_CHUNK: Long = 5 * MB
    const val FALLBACK_CHUNK_MB: Int = 45

    /** Margine per l'overhead multipart (campi uuid/filesize/offset e boundary). */
    private const val MULTIPART_OVERHEAD: Long = 64 * 1024

    fun chunkSize(config: ServerConfig?): Long {
        val preferred = (config?.maxChunkSizeMb?.takeIf { it > 0 } ?: FALLBACK_CHUNK_MB) * MB
        val maxRequest = config?.maxFileSizeMb?.takeIf { it > 0 }?.let { it * MB - MULTIPART_OVERHEAD }
        val capped = if (maxRequest != null) minOf(preferred, maxRequest) else preferred
        return maxOf(capped, MIN_CHUNK)
    }

    /** `true` se il file supera il limite del server. */
    fun exceedsLimit(size: Long, config: ServerConfig?): Boolean {
        val max = config?.maxFileSizeMb?.takeIf { it > 0 } ?: return false
        return size > max * MB
    }

    fun plan(size: Long, chunkSize: Long): List<Chunk> {
        require(size >= 0) { "size negativo" }
        require(chunkSize > 0) { "chunkSize non positivo" }
        if (size == 0L) return listOf(Chunk(0, 0))
        val chunks = ArrayList<Chunk>((size / chunkSize + 1).toInt())
        var offset = 0L
        while (offset < size) {
            val len = minOf(chunkSize, size - offset)
            chunks += Chunk(offset, len)
            offset += len
        }
        return chunks
    }
}
