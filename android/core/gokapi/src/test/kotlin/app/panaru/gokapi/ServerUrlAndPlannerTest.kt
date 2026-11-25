package app.panaru.gokapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun valid(input: String) = ServerUrl.parse(input) as ServerUrl.Result.Valid
    private fun reason(input: String) = (ServerUrl.parse(input) as ServerUrl.Result.Invalid).reason

    @Test
    fun `adds https when scheme missing`() {
        assertEquals("https://files.example.com", valid("files.example.com").canonical)
    }

    @Test
    fun `strips admin and api suffixes, query and fragment`() {
        assertEquals("https://x.example/gokapi", valid("https://x.example/gokapi/admin?x=1#f").canonical)
        assertEquals("https://x.example", valid("https://x.example/api/").canonical)
        assertEquals("https://x.example", valid(" https://x.example/d?id=abc ").canonical)
    }

    @Test
    fun `http only for local hosts`() {
        assertTrue(valid("http://192.168.1.10:53842").isInsecureLocal)
        assertTrue(valid("http://nas.local").isInsecureLocal)
        assertTrue(valid("http://[fd00::1]:8080").isInsecureLocal)
        assertEquals(ServerUrl.Reason.INSECURE_PUBLIC_HOST, reason("http://files.example.com"))
        assertEquals(ServerUrl.Reason.INSECURE_PUBLIC_HOST, reason("http://8.8.8.8"))
        assertFalse(valid("https://files.example.com").isInsecureLocal)
    }

    @Test
    fun `rejects bad inputs`() {
        assertEquals(ServerUrl.Reason.EMPTY, reason("   "))
        assertEquals(ServerUrl.Reason.UNSUPPORTED_SCHEME, reason("ftp://x.example"))
        assertEquals(ServerUrl.Reason.CREDENTIALS_IN_URL, reason("https://user:pw@x.example"))
        assertEquals(ServerUrl.Reason.MALFORMED, reason("https://"))
    }

    @Test
    fun `private ranges`() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.255", "127.0.0.1", "100.100.1.1", "localhost")
            .forEach { assertTrue(it, ServerUrl.isLocalHost(it)) }
        listOf("172.32.0.1", "11.0.0.1", "example.com", "192.169.0.1")
            .forEach { assertFalse(it, ServerUrl.isLocalHost(it)) }
    }
}

class ChunkPlannerTest {
    private val mb = ChunkPlanner.MB

    @Test
    fun `uses server chunk size and never below 5 MB`() {
        assertEquals(45 * mb, ChunkPlanner.chunkSize(null))
        assertEquals(20 * mb, ChunkPlanner.chunkSize(ServerConfig(1000, 20)))
        assertEquals(5 * mb, ChunkPlanner.chunkSize(ServerConfig(1000, 1)))
    }

    @Test
    fun `chunk never exceeds the request size limit`() {
        val size = ChunkPlanner.chunkSize(ServerConfig(maxFileSizeMb = 30, maxChunkSizeMb = 45))
        assertTrue(size < 30 * mb)
    }

    @Test
    fun `plan covers the file exactly`() {
        val plan = ChunkPlanner.plan(11 * mb + 3, 5 * mb)
        assertEquals(listOf(0L, 5 * mb, 10 * mb), plan.map { it.offset })
        assertEquals(11 * mb + 3, plan.sumOf { it.length })
        assertEquals(listOf(Chunk(0, 0)), ChunkPlanner.plan(0, 5 * mb))
    }

    @Test
    fun `limit check`() {
        assertTrue(ChunkPlanner.exceedsLimit(10 * mb + 1, ServerConfig(10, 5)))
        assertFalse(ChunkPlanner.exceedsLimit(10 * mb, ServerConfig(10, 5)))
        assertFalse(ChunkPlanner.exceedsLimit(Long.MAX_VALUE, null))
    }

    @Test
    fun `file name encoding`() {
        assertEquals("report.pdf", GokapiClient.encodeFileName("report.pdf"))
        assertTrue(GokapiClient.encodeFileName("è.txt").startsWith("base64:"))
        assertTrue(GokapiClient.encodeFileName("base64:trick").startsWith("base64:"))
        assertEquals("a b", GokapiClient.encodeFileName("a\nb"))
    }

    @Test
    fun `password must be ascii`() {
        assertTrue(UploadOptions.isValidPassword("Abc 123!~"))
        assertFalse(UploadOptions.isValidPassword("pàss"))
    }
}
