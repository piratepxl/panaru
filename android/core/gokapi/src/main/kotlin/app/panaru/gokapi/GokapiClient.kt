package app.panaru.gokapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sorgente di un upload. [open] restituisce uno stream posizionato su `offset`:
 * le implementazioni devono evitare di leggere il file da capo quando possibile
 * (es. `FileChannel.position`). Viene aperto uno stream per chunk (e per tentativo).
 */
interface ChunkSource {
    val size: Long
    fun open(offset: Long): InputStream
}

/** Callback di avanzamento: byte confermati/inviati finora sul totale. */
fun interface ProgressListener {
    fun onProgress(sent: Long, total: Long)
}

/**
 * Client per l'API di Gokapi. Ogni chiamata è `suspend` e annullabile: l'annullamento
 * della coroutine interrompe la richiesta HTTP in corso.
 *
 * Riferimento: docs/GOKAPI_API.md (comportamento verificato su openapi.json e sorgenti).
 */
class GokapiClient(
    baseUrl: HttpUrl,
    private val apiKey: String,
    httpClient: OkHttpClient = defaultHttpClient(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    private val api: HttpUrl = baseUrl.newBuilder().addPathSegment("api").build()
    private val http = httpClient
    /** La finalizzazione di file grandi può richiedere minuti (hash, cifratura, copia su storage). */
    private val slowHttp = httpClient.newBuilder().readTimeout(15, TimeUnit.MINUTES).build()

    data class RetryPolicy(val maxAttempts: Int = 3, val initialDelayMs: Long = 1_000)

    // ---------------------------------------------------------------- info

    suspend fun version(): VersionInfo = getJson("info/version")

    suspend fun config(): ServerConfig = getJson("info/config")

    // --------------------------------------------------------------- files

    /** Elenco dei file non scaduti (Gokapi restituisce `null` se non ce ne sono). */
    suspend fun listFiles(): List<GokapiFile> {
        val body = execute(request("files/list").get().build()) { it.body?.string().orEmpty() }
        if (body.isBlank() || body.trim() == "null") return emptyList()
        return decode(body)
    }

    suspend fun file(id: String): GokapiFile = getJson("files/list/${encodeSegment(id)}")

    suspend fun delete(id: String) {
        val req = request("files/delete").header("id", id).delete().build()
        execute(req) { }
    }

    /**
     * Scarica un file nello [sink] senza incrementare il contatore dei download.
     * I file cifrati end-to-end non sono scaricabili via API.
     */
    suspend fun download(id: String, sink: OutputStream, listener: ProgressListener? = null) {
        val req = request("files/download/${encodeSegment(id)}").get().build()
        execute(req, client = slowHttp) { response ->
            val body = response.body ?: throw GokapiException.Protocol("Empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                val buf = ByteArray(BUFFER)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    done += n
                    listener?.onProgress(done, total)
                }
            }
        }
    }

    // -------------------------------------------------------------- upload

    /**
     * Carica [source] con `/chunk/add` + `/chunk/complete` e restituisce il file creato.
     * I chunk falliti per errori di rete o 5xx vengono ritentati con backoff esponenziale.
     * Se la finalizzazione fallisce, Gokapi elimina i chunk: serve un nuovo upload.
     */
    suspend fun upload(
        source: ChunkSource,
        fileName: String,
        mimeType: String?,
        options: UploadOptions,
        config: ServerConfig?,
        listener: ProgressListener? = null,
    ): GokapiFile {
        val size = source.size
        if (ChunkPlanner.exceedsLimit(size, config)) throw GokapiException.FileTooLarge(config?.maxFileSizeMb)
        val uuid = UUID.randomUUID().toString()
        var confirmed = 0L
        listener?.onProgress(0, size)
        for (chunk in ChunkPlanner.plan(size, ChunkPlanner.chunkSize(config))) {
            retrying {
                val body = ChunkBody(source, chunk) { sentInChunk ->
                    listener?.onProgress(confirmed + sentInChunk, size)
                }
                val form = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("uuid", uuid)
                    .addFormDataPart("filesize", size.toString())
                    .addFormDataPart("offset", chunk.offset.toString())
                    .addFormDataPart("file", "chunk", body)
                    .build()
                execute(request("chunk/add").post(form).build()) { }
            }
            confirmed += chunk.length
            listener?.onProgress(confirmed, size)
        }

        val complete = request("chunk/complete")
            .header("uuid", uuid)
            .header("filename", encodeFileName(fileName))
            .header("filesize", size.toString())
            .header("contenttype", mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            .header("allowedDownloads", options.allowedDownloads.toString())
            .header("expiryDays", options.expiryDays.toString())
            .apply { options.password?.takeIf { it.isNotEmpty() }?.let { header("password", it) } }
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val result: UploadResult = execute(complete, client = slowHttp) { decode(it.body?.string().orEmpty()) }
        return result.fileInfo ?: throw GokapiException.Protocol("Missing FileInfo in upload result")
    }

    // ------------------------------------------------------------ internals

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 1
        var wait = retryPolicy.initialDelayMs
        while (true) {
            try {
                return block()
            } catch (e: GokapiException) {
                val retryable = e is GokapiException.Network ||
                    (e is GokapiException.Server && e.isRetryable)
                if (!retryable || attempt >= retryPolicy.maxAttempts) throw e
                delay(wait)
                wait *= 2
                attempt++
            }
        }
    }

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url(api.newBuilder().addEncodedPathSegments(path).build())
            .header("apikey", apiKey)
            .header("Accept", "application/json")

    private suspend inline fun <reified T> getJson(path: String): T =
        execute(request(path).get().build()) { decode(it.body?.string().orEmpty()) }

    private suspend fun <T> execute(
        request: Request,
        client: OkHttpClient = http,
        handle: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            ensureActive()
            throw GokapiException.fromIo(e)
        }
        response.use {
            if (!it.isSuccessful) throw errorFor(it)
            try {
                handle(it)
            } catch (e: IOException) {
                ensureActive()
                throw GokapiException.fromIo(e)
            }
        }
    }

    private fun errorFor(response: Response): GokapiException {
        val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val err = runCatching { json.decodeFromString(ErrorBody.serializer(), raw) }.getOrNull()
        return when {
            response.code == 401 -> GokapiException.Unauthorized(err?.message ?: "Unauthorized")
            response.code == 404 -> GokapiException.NotFound(err?.message ?: "Not found")
            response.code == 429 -> GokapiException.RateLimited()
            err?.code == ERROR_FILE_TOO_LARGE -> GokapiException.FileTooLarge(null)
            else -> GokapiException.Server(
                response.code,
                err?.code,
                err?.message?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}",
            )
        }
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString(body)
    } catch (e: SerializationException) {
        throw GokapiException.Protocol("Unexpected response: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw GokapiException.Protocol("Unexpected response: ${e.message}")
    }

    /** Corpo di un chunk letto in streaming dalla sorgente: mai il file intero in memoria. */
    private class ChunkBody(
        private val source: ChunkSource,
        private val chunk: Chunk,
        private val onWritten: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = OCTET
        override fun contentLength(): Long = chunk.length
        override fun isOneShot(): Boolean = false

        override fun writeTo(sink: BufferedSink) {
            val input = try {
                source.open(chunk.offset)
            } catch (e: Exception) {
                throw SourceReadException("Cannot open source", e)
            }
            input.use {
                val buf = ByteArray(BUFFER)
                var remaining = chunk.length
                var written = 0L
                while (remaining > 0) {
                    val n = try {
                        it.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    } catch (e: IOException) {
                        throw SourceReadException("Cannot read source", e)
                    }
                    if (n < 0) throw SourceReadException("Source ended early: file changed while uploading")
                    sink.write(buf, 0, n)
                    remaining -= n
                    written += n
                    onWritten(written)
                }
            }
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val ERROR_FILE_TOO_LARGE = 9
        private val OCTET = "application/octet-stream".toMediaType()

        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .followRedirects(false) // un redirect potrebbe inoltrare l'header apikey altrove
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * Gokapi accetta il nome file in un header HTTP: i nomi non ASCII (o che iniziano con
         * `base64:`) vengono inviati come `base64:<UTF-8 in base64 standard>`.
         */
        fun encodeFileName(name: String): String {
            val clean = name.replace('\r', ' ').replace('\n', ' ').ifBlank { "file" }
            val plainAscii = clean.all { it in ' '..'~' } && !clean.startsWith("base64:")
            return if (plainAscii) clean
            else "base64:" + Base64.getEncoder().encodeToString(clean.toByteArray(Charsets.UTF_8))
        }

        private fun encodeSegment(id: String): String {
            require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Invalid file id" }
            return id
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() }
        }
    })
}

