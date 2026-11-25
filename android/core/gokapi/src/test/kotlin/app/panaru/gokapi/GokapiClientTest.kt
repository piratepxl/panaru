package app.panaru.gokapi

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

class GokapiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GokapiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = GokapiClient(
            server.url("/gokapi/"),
            apiKey = "test-key",
            retryPolicy = GokapiClient.RetryPolicy(maxAttempts = 3, initialDelayMs = 1),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private class MemorySource(val data: ByteArray) : ChunkSource {
        var opens = 0
        override val size: Long get() = data.size.toLong()
        override fun open(offset: Long): InputStream {
            opens++
            return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
    }

    private fun fileJson(name: String = "a.txt") =
        """{"Result":"OK","FileInfo":{"Id":"abc123","Name":"$name","SizeBytes":3,
        "UrlDownload":"https://g.example/d?id=abc123","ExpireAt":1900000000,"DownloadsRemaining":1,
        "UnknownField":true},"IncludeFilename":false}"""

    /** Estrae il valore di un campo dal corpo multipart registrato. */
    private fun RecordedRequest.formField(name: String): String {
        val text = body.clone().readUtf8()
        val marker = "name=\"$name\""
        val start = text.indexOf(marker)
        check(start >= 0) { "field $name missing" }
        val valueStart = text.indexOf("\r\n\r\n", start) + 4
        return text.substring(valueStart, text.indexOf("\r\n", valueStart))
    }

    private fun RecordedRequest.filePart(): ByteArray {
        val buf: Buffer = body.clone()
        val raw = buf.readByteArray()
        val text = String(raw, Charsets.ISO_8859_1)
        val start = text.indexOf("name=\"file\"")
        val dataStart = text.indexOf("\r\n\r\n", start) + 4
        val boundary = "\r\n--" + headers["Content-Type"]!!.substringAfter("boundary=")
        val dataEnd = text.indexOf(boundary, dataStart)
        return raw.copyOfRange(dataStart, dataEnd)
    }

    @Test
    fun `version sends apikey header under api path`() = runBlocking {
        server.enqueue(json("""{"Version":"2.2.4","VersionInt":20204}"""))
        val v = client.version()
        assertEquals(20204, v.versionInt)
        assertTrue(v.supportsBase64FileNames)
        val req = server.takeRequest()
        assertEquals("/gokapi/api/info/version", req.path)
        assertEquals("test-key", req.getHeader("apikey"))
    }

    @Test
    fun `config is parsed in megabytes`() = runBlocking {
        server.enqueue(json("""{"MaxFilesize":102400,"MaxChunksize":45,"EndToEndEncryptionEnabled":true}"""))
        val c = client.config()
        assertEquals(45, c.maxChunkSizeMb)
        assertTrue(c.endToEndEncryptionEnabled)
    }

    @Test
    fun `small upload sends one chunk and complete headers`() = runBlocking {
        server.enqueue(json("""{"result":"OK"}"""))
        server.enqueue(json(fileJson()))
        val data = bytes(3)
        val progress = mutableListOf<Long>()
        val file = client.upload(
            MemorySource(data), "a.txt", "text/plain",
            UploadOptions(expiryDays = 7, allowedDownloads = 0, password = "pw"),
            ServerConfig(maxFileSizeMb = 100, maxChunkSizeMb = 45),
        ) { sent, _ -> progress += sent }

        assertEquals("abc123", file.id)
        assertEquals("https://g.example/d?id=abc123", file.urlDownload)
        assertEquals(3L, progress.last())

        val add = server.takeRequest()
        assertEquals("/gokapi/api/chunk/add", add.path)
        assertEquals("POST", add.method)
        val uuid = add.formField("uuid")
        assertTrue(uuid.length >= 10)
        assertEquals("3", add.formField("filesize"))
        assertEquals("0", add.formField("offset"))
        assertArrayEquals(data, add.filePart())

        val complete = server.takeRequest()
        assertEquals("/gokapi/api/chunk/complete", complete.path)
        assertEquals(uuid, complete.getHeader("uuid"))
        assertEquals("a.txt", complete.getHeader("filename"))
        assertEquals("3", complete.getHeader("filesize"))
        assertEquals("text/plain", complete.getHeader("contenttype"))
        assertEquals("0", complete.getHeader("allowedDownloads"))
        assertEquals("7", complete.getHeader("expiryDays"))
        assertEquals("pw", complete.getHeader("password"))
    }

    @Test
    fun `large upload is split respecting server chunk size`() = runBlocking {
        val data = bytes((12 * ChunkPlanner.MB).toInt())
        repeat(3) { server.enqueue(json("""{"result":"OK"}""")) }
        server.enqueue(json(fileJson("big.bin")))
        client.upload(MemorySource(data), "big.bin", null, UploadOptions(), ServerConfig(100, 5))

        val offsets = (0 until 3).map { server.takeRequest() }.map { req ->
            val off = req.formField("offset").toLong()
            val part = req.filePart()
            assertArrayEquals(data.copyOfRange(off.toInt(), off.toInt() + part.size), part)
            off to part.size
        }
        assertEquals(listOf(0L, 5 * ChunkPlanner.MB, 10 * ChunkPlanner.MB), offsets.map { it.first })
        assertEquals(2 * ChunkPlanner.MB, offsets.last().second.toLong())
        val complete = server.takeRequest()
        assertEquals("application/octet-stream", complete.getHeader("contenttype"))
        assertEquals("1", complete.getHeader("allowedDownloads"))
        assertEquals("14", complete.getHeader("expiryDays"))
        assertEquals(null, complete.getHeader("password"))
    }

    @Test
    fun `non ascii file names are base64 encoded`() = runBlocking {
        server.enqueue(json("""{"result":"OK"}"""))
        server.enqueue(json(fileJson()))
        val name = "Città — foto 📷.jpg"
        client.upload(MemorySource(bytes(1)), name, "image/jpeg", UploadOptions(), null)
        server.takeRequest()
        val header = server.takeRequest().getHeader("filename")!!
        assertTrue(header.startsWith("base64:"))
        assertEquals(name, String(Base64.getDecoder().decode(header.removePrefix("base64:")), Charsets.UTF_8))
    }

    @Test
    fun `chunk is retried after a server error`() = runBlocking {
        server.enqueue(json("""{"Result":"error","ErrorMessage":"boom","ErrorCode":8}""", 500))
        server.enqueue(json("""{"result":"OK"}"""))
        server.enqueue(json(fileJson()))
        val source = MemorySource(bytes(10))
        client.upload(source, "a.txt", null, UploadOptions(), null)
        assertEquals(3, server.requestCount)
        assertEquals(2, source.opens)
    }

    @Test
    fun `client errors are not retried and are typed`() = runBlocking {
        server.enqueue(json("""{"Result":"error","ErrorMessage":"Unauthorized","ErrorCode":2}""", 401))
        try {
            client.upload(MemorySource(bytes(10)), "a.txt", null, UploadOptions(), null)
            fail("expected Unauthorized")
        } catch (e: GokapiException.Unauthorized) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `server error message is exposed`() = runBlocking {
        server.enqueue(json("""{"Result":"error","ErrorMessage":"chunk too small","ErrorCode":11}""", 400))
        try {
            client.upload(MemorySource(bytes(10)), "a.txt", null, UploadOptions(), null)
            fail()
        } catch (e: GokapiException.Server) {
            assertEquals(400, e.status)
            assertEquals(11, e.code)
            assertEquals("chunk too small", e.message)
        }
    }

    @Test
    fun `file over server limit fails before any request`() = runBlocking {
        val source = object : ChunkSource {
            override val size = 2 * ChunkPlanner.MB + 1
            override fun open(offset: Long): InputStream = error("must not be read")
        }
        try {
            client.upload(source, "a", null, UploadOptions(), ServerConfig(maxFileSizeMb = 2, maxChunkSizeMb = 1))
            fail()
        } catch (e: GokapiException.FileTooLarge) {
            assertEquals(2, e.maxMb)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `source that shrinks is reported as unavailable`() = runBlocking {
        server.enqueue(json("""{"result":"OK"}"""))
        val lying = object : ChunkSource {
            override val size = 100L
            override fun open(offset: Long): InputStream = ByteArrayInputStream(ByteArray(10))
        }
        try {
            client.upload(lying, "a", null, UploadOptions(), null)
            fail()
        } catch (e: GokapiException.SourceUnavailable) {
            // ok
        }
    }

    @Test
    fun `network errors are retried then reported`() = runBlocking {
        server.shutdown()
        try {
            client.version()
            fail()
        } catch (e: GokapiException.Network) {
            // ok
        }
    }

    @Test
    fun `list returns empty for null body and parses files`() = runBlocking {
        server.enqueue(json("null"))
        assertEquals(emptyList<GokapiFile>(), client.listFiles())
        server.enqueue(json("""[{"Id":"x1","Name":"n","SizeBytes":5}]"""))
        assertEquals("x1", client.listFiles().single().id)
        assertEquals("/gokapi/api/files/list", server.takeRequest().path)
    }

    @Test
    fun `delete sends id header with DELETE`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        client.delete("abc123")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/gokapi/api/files/delete", req.path)
        assertEquals("abc123", req.getHeader("id"))
    }

    @Test
    fun `not found is typed`() = runBlocking {
        server.enqueue(json("""{"Result":"error","ErrorMessage":"File not found","ErrorCode":5}""", 404))
        try {
            client.file("missing1")
            fail()
        } catch (e: GokapiException.NotFound) {
            assertEquals("/gokapi/api/files/list/missing1", server.takeRequest().path)
        }
    }

    @Test
    fun `download streams body and reports progress`() = runBlocking {
        val data = bytes(200_000)
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        val out = ByteArrayOutputStream()
        var last = 0L
        client.download("abc123", out) { sent, _ -> last = sent }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), last)
        assertEquals("/gokapi/api/files/download/abc123", server.takeRequest().path)
    }

    @Test
    fun `non gokapi response is a protocol error`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>login</html>"))
        try {
            client.version()
            fail()
        } catch (e: GokapiException.Protocol) {
            // ok
        }
    }

    @Test
    fun `redirects are not followed so the key is not leaked`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
        try {
            client.version()
            fail()
        } catch (e: GokapiException.Server) {
            assertEquals(302, e.status)
        }
    }

    @Test
    fun `cancellation aborts the request`() = runBlocking {
        server.enqueue(json("{}").setHeadersDelay(3, TimeUnit.SECONDS))
        val job = async { client.version() }
        delay(200)
        val start = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("cancellation took $elapsedMs ms", elapsedMs < 1_000)
        assertTrue(job.isCancelled)
    }
}
