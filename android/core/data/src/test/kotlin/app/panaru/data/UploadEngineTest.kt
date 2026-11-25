package app.panaru.data

import app.panaru.gokapi.GokapiClient
import app.panaru.gokapi.ServerUrl
import app.panaru.gokapi.UploadOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Cifratura fittizia per i test (in produzione: Android Keystore). */
private class FakeCipher : SecretCipher {
    override fun seal(plain: String) = Sealed(plain.reversed().toByteArray(), byteArrayOf(1))
    override fun open(sealed: Sealed) = String(sealed.cipherText).reversed()
}

private class FakeServerDao : ServerDao {
    val rows = MutableStateFlow<List<ServerEntity>>(emptyList())
    private var next = 1L
    override fun observeAll(): Flow<List<ServerEntity>> = rows
    override suspend fun all() = rows.value.sortedByDescending { it.isDefault }
    override suspend fun byId(id: Long) = rows.value.find { it.id == id }
    override suspend fun defaultServer() = rows.value.find { it.isDefault }
    override suspend fun insert(server: ServerEntity): Long {
        val id = next++
        rows.value = rows.value + server.copy(id = id)
        return id
    }
    override suspend fun update(server: ServerEntity) { rows.value = rows.value.map { if (it.id == server.id) server else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filter { it.id != id } }
    override suspend fun setDefault(id: Long) { rows.value = rows.value.map { it.copy(isDefault = it.id == id) } }
    override suspend fun count() = rows.value.size
}

private class FakeUploadDao : UploadDao {
    val rows = MutableStateFlow<List<UploadEntity>>(emptyList())
    private var next = 1L
    @Synchronized private fun mutate(f: (List<UploadEntity>) -> List<UploadEntity>) { rows.value = f(rows.value) }
    fun get(id: Long) = rows.value.first { it.id == id }
    override fun observeRecent(limit: Int) = rows.map { l -> l.filter { !it.hidden }.sortedByDescending { r -> r.createdAt } }
    override fun observeActive() = rows.map { l -> l.filter { it.isActive } }
    override suspend fun byId(id: Long) = rows.value.find { it.id == id }
    override fun observeByIds(ids: List<Long>) = rows.map { l -> l.filter { it.id in ids } }
    override suspend fun nextQueued() = rows.value.filter { it.state == UploadState.QUEUED }.minByOrNull { it.createdAt }
    override suspend fun withFileIdOn(serverId: Long) = rows.value.filter { it.serverId == serverId && it.fileId != null }
    override suspend fun insertAll(items: List<UploadEntity>): List<Long> {
        val ids = mutableListOf<Long>()
        mutate { list -> list + items.map { val id = next++; ids += id; it.copy(id = id) } }
        return ids
    }
    override suspend fun update(item: UploadEntity) = mutate { l -> l.map { if (it.id == item.id) item else it } }
    override suspend fun transition(id: Long, expected: UploadState, state: UploadState): Int = synchronized(this) {
        val cur = rows.value.find { it.id == id } ?: return 0
        if (cur.state != expected) return 0
        mutate { l -> l.map { if (it.id == id) it.copy(state = state) else it } }
        return 1
    }
    override suspend fun markInterrupted(): Int {
        var n = 0
        mutate { l -> l.map { if (it.state == UploadState.UPLOADING) { n++; it.copy(state = UploadState.FAILED, errorKind = ErrorKind.INTERRUPTED) } else it } }
        return n
    }
    private fun synced(u: UploadEntity) = u.fileId != null && u.state == UploadState.COMPLETED
    override suspend fun hide(id: Long) = mutate { l -> l.map { if (it.id == id && synced(it)) it.copy(hidden = true) else it } }
    override suspend fun deleteUnsynced(id: Long) = mutate { l -> l.filter { it.id != id || it.isActive || synced(it) } }
    override suspend fun hideAllCompleted() = mutate { l -> l.map { if (synced(it)) it.copy(hidden = true) else it } }
    override suspend fun deleteAllUnsynced() = mutate { l -> l.filter { it.isActive || synced(it) } }
}

private class MemorySources(private val files: Map<String, ByteArray>) : SourceOpener {
    override fun resolve(uri: String) = SourceInfo(uri, uri.substringAfterLast('/'), files.getValue(uri).size.toLong(), "application/octet-stream")
    override fun open(uri: String): OpenedSource {
        val data = files[uri] ?: throw SecurityException("Permission denial")
        return object : OpenedSource {
            override val size = data.size.toLong()
            override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
            override fun close() = Unit
        }
    }
}

class UploadEngineTest {
    private lateinit var gokapiA: MockWebServer
    private lateinit var gokapiB: MockWebServer
    private val serverDao = FakeServerDao()
    private val uploadDao = FakeUploadDao()
    private val settings = MutableStateFlow(PanaruSettings(maxConcurrentUploads = 2))
    private lateinit var repo: ServerRepository
    private lateinit var engine: UploadEngine
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    private val completeHeaders = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var chunkDelayMs = 0L
    @Volatile private var remoteFiles = """[{"Id":"keep","DownloadsRemaining":1}]"""

    private fun gokapi(name: String) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("apikey") != "key-$name") return MockResponse().setResponseCode(401)
                return when (request.path) {
                    "/api/info/version" -> json("""{"Version":"2.2.4","VersionInt":20204}""")
                    "/api/info/config" -> json("""{"MaxFilesize":100,"MaxChunksize":5}""")
                    "/api/chunk/add" -> {
                        val now = inFlight.incrementAndGet()
                        maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        Thread.sleep(chunkDelayMs)
                        inFlight.decrementAndGet()
                        json("""{"result":"OK"}""")
                    }
                    "/api/chunk/complete" -> {
                        completeHeaders += request
                        val id = "f" + request.getHeader("uuid")!!.take(8).filter { it.isLetterOrDigit() }
                        json("""{"Result":"OK","FileInfo":{"Id":"$id","Name":"x","UrlDownload":"${url("/d?id=$id")}","ExpireAt":4000000000,"DownloadsRemaining":3}}""")
                    }
                    "/api/files/list" -> json(remoteFiles)
                    "/api/files/delete" -> MockResponse().setResponseCode(200)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    @Before
    fun setUp() {
        gokapiA = gokapi("A")
        gokapiB = gokapi("B")
        repo = ServerRepository(serverDao, FakeCipher(), GokapiClient.defaultHttpClient())
    }

    @After
    fun tearDown() {
        gokapiA.shutdown()
        gokapiB.shutdown()
    }

    private fun valid(s: MockWebServer) = ServerUrl.parse(s.url("/").toString()) as ServerUrl.Result.Valid

    private fun engineWith(files: Map<String, ByteArray>) =
        UploadEngine(uploadDao, repo, settings, MemorySources(files)).also { engine = it }

    private suspend fun runQueue() = withTimeout(20_000) { engine.runUntilIdle() }

    @Test
    fun `first server becomes default and keys are stored sealed`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", makeDefault = false)
        val b = repo.add("B", valid(gokapiB), "key-B", makeDefault = false)
        assertEquals(a, repo.defaultServer()!!.id)
        assertFalse(String(serverDao.byId(a)!!.keyCipher).contains("key-A"))
        repo.setDefault(b)
        assertEquals(b, repo.defaultServer()!!.id)
        repo.delete(b)
        assertEquals(a, repo.defaultServer()!!.id)
    }

    @Test
    fun `connection check reports success and bad key`() = runBlocking {
        val ok = repo.check(valid(gokapiA), "key-A")
        assertTrue(ok is ConnectionCheck.Ok)
        val bad = repo.check(valid(gokapiA), "wrong")
        assertTrue(bad is ConnectionCheck.Failed)
    }

    @Test
    fun `queue uploads to the right server with options and respects concurrency`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        val b = repo.add("B", valid(gokapiB), "key-B", false)
        val files = (1..5).associate { "content://f/$it" to ByteArray(1000 + it) }
        engineWith(files)
        chunkDelayMs = 150
        val idsA = engine.enqueue(a, files.keys.take(3).map { MemorySources(files).resolve(it) }, UploadOptions(3, 0, "secret"))
        val idsB = engine.enqueue(b, files.keys.drop(3).map { MemorySources(files).resolve(it) }, UploadOptions(0, 5))
        runQueue()

        (idsA + idsB).forEach { assertEquals(UploadState.COMPLETED, uploadDao.get(it).state) }
        assertTrue(uploadDao.get(idsA[0]).url!!.contains(gokapiA.hostName))
        assertEquals(3, completeHeaders.count { it.requestUrl!!.port == gokapiA.port })
        assertEquals(2, completeHeaders.count { it.requestUrl!!.port == gokapiB.port })
        assertTrue("max in flight ${maxInFlight.get()}", maxInFlight.get() <= 2)
        val withPw = completeHeaders.filter { it.getHeader("password") == "secret" }
        assertEquals(3, withPw.size)
        assertTrue(withPw.all { it.getHeader("expiryDays") == "3" && it.getHeader("allowedDownloads") == "0" })
        assertFalse("password never persisted", uploadDao.rows.value.toString().contains("secret"))
    }

    @Test
    fun `missing source fails with SOURCE and retry works after fix`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        engineWith(emptyMap())
        val id = engine.enqueue(a, listOf(SourceInfo("content://gone", "gone.txt", 3, null)), UploadOptions())[0]
        runQueue()
        assertEquals(UploadState.FAILED, uploadDao.get(id).state)
        assertEquals(ErrorKind.SOURCE, uploadDao.get(id).errorKind)

        engineWith(mapOf("content://gone" to ByteArray(3)))
        assertTrue(engine.retry(id))
        runQueue()
        assertEquals(UploadState.COMPLETED, uploadDao.get(id).state)
    }

    @Test
    fun `wrong key is reported as unauthorized`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "nope", false)
        engineWith(mapOf("content://x" to ByteArray(3)))
        val id = engine.enqueue(a, listOf(SourceInfo("content://x", "x", 3, null)), UploadOptions())[0]
        runQueue()
        assertEquals(ErrorKind.UNAUTHORIZED, uploadDao.get(id).errorKind)
    }

    @Test
    fun `cancel removes queued and running uploads`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        settings.value = PanaruSettings(maxConcurrentUploads = 1)
        val files = mapOf("content://1" to ByteArray(12 * 1024 * 1024), "content://2" to ByteArray(10))
        engineWith(files)
        chunkDelayMs = 400
        val ids = engine.enqueue(a, files.keys.map { MemorySources(files).resolve(it) }, UploadOptions())
        val run = async { runQueue() }
        delay(300)
        engine.cancel(ids[1]) // in coda
        engine.cancel(ids[0]) // in corso
        run.await()
        assertEquals(UploadState.CANCELLED, uploadDao.get(ids[0]).state)
        assertEquals(UploadState.CANCELLED, uploadDao.get(ids[1]).state)
        assertTrue(engine.live.value.isEmpty())
    }

    @Test
    fun `password protected upload cannot be retried after restart`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        engineWith(emptyMap())
        val id = engine.enqueue(a, listOf(SourceInfo("content://gone", "g", 1, null)), UploadOptions(password = "pw"))[0]
        runQueue()
        val restarted = engineWith(emptyMap()) // nuova istanza: password persa
        assertFalse(restarted.retry(id))
    }

    @Test
    fun `sync marks files no longer on server`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        engineWith(mapOf("content://x" to ByteArray(3)))
        val id = engine.enqueue(a, listOf(SourceInfo("content://x", "x", 3, null)), UploadOptions())[0]
        runQueue()
        assertEquals(UploadEngine.SyncResult.OK, engine.syncServer(a))
        assertTrue(uploadDao.get(id).removedFromServer)
        assertTrue(uploadDao.get(id).isExpired(System.currentTimeMillis() / 1000))
    }

    @Test
    fun `sync imports files uploaded from other devices without duplicates`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        engineWith(mapOf("content://x" to ByteArray(3)))
        val id = engine.enqueue(a, listOf(SourceInfo("content://x", "x", 3, null)), UploadOptions())[0]
        runQueue()
        val mine = uploadDao.get(id).fileId!!
        remoteFiles = """[{"Id":"$mine","DownloadsRemaining":7},
            {"Id":"fromMac1","Name":"dal-mac.pdf","SizeBytes":1234,"UrlDownload":"https://g/d?id=fromMac1",
             "UploadDate":1700000000,"ExpireAt":4000000000,"DownloadsRemaining":2,"IsPasswordProtected":true},
            {"Id":"gone1","Name":"in-cancellazione","IsPendingDeletion":true}]"""
        assertEquals(UploadEngine.SyncResult.OK, engine.syncServer(a))
        assertEquals(UploadEngine.SyncResult.OK, engine.syncServer(a)) // idempotente
        val rows = uploadDao.rows.value
        assertEquals(2, rows.size)
        assertEquals(7, uploadDao.get(id).downloadsRemaining)
        val imported = rows.single { it.fileId == "fromMac1" }
        assertTrue(imported.fromOtherDevice)
        assertEquals(UploadState.COMPLETED, imported.state)
        assertEquals("dal-mac.pdf", imported.fileName)
        assertEquals(1_700_000_000_000L, imported.createdAt)
        assertTrue(imported.hasPassword)
        assertEquals("A", imported.serverName)

        // Rimossa dalla cronologia: resta nascosta anche dopo una nuova sincronizzazione.
        uploadDao.delete(imported.id)
        engine.syncServer(a)
        assertEquals(1, uploadDao.rows.value.count { it.fileId == "fromMac1" })
        assertTrue(uploadDao.rows.value.single { it.fileId == "fromMac1" }.hidden)
    }

    @Test
    fun `sync without view permission is reported`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        engineWith(emptyMap())
        gokapiA.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        assertEquals(UploadEngine.SyncResult.NO_PERMISSION, engine.syncServer(a))
    }

    @Test
    fun `interrupted uploads are recovered as failed`() = runBlocking {
        uploadDao.insertAll(listOf(UploadEntity(serverId = 1, serverName = "A", sourceUri = "u", fileName = "f", sizeBytes = 1,
            mimeType = null, expiryDays = 1, allowedDownloads = 1, hasPassword = false, state = UploadState.UPLOADING, createdAt = 1)))
        engineWith(emptyMap())
        assertEquals(1, engine.recoverInterrupted())
        assertEquals(ErrorKind.INTERRUPTED, uploadDao.rows.value.single().errorKind)
    }

    @Test
    fun `delete from server sends delete and marks history`() = runBlocking {
        val a = repo.add("A", valid(gokapiA), "key-A", false)
        engineWith(mapOf("content://x" to ByteArray(3)))
        val id = engine.enqueue(a, listOf(SourceInfo("content://x", "x", 3, null)), UploadOptions())[0]
        runQueue()
        engine.deleteFromServer(id)
        assertTrue(uploadDao.get(id).removedFromServer)
        var deleteSeen = false
        while (gokapiA.requestCount > 0) {
            val r = gokapiA.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
            if (r.path == "/api/files/delete") deleteSeen = r.getHeader("id") == uploadDao.get(id).fileId
        }
        assertTrue(deleteSeen)
    }
}
