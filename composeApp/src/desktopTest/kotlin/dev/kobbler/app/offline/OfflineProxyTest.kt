package dev.kobbler.app.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineProxyTest {
    private val data = Random(7).nextBytes(3 * 1024 * 1024 + 123)
    private val upstream = RangeServer(mapOf("/direct" to data))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = Files.createTempDirectory("offline").toFile()
    private val store = OfflineStore(JvmOfflineFs(root), scope)
    private val key = OfflineItem.keyOf(OfflineKind.Movie, "m1")
    private var refreshes = 0
    private val media = OfflineMedia(store, key, MediaFilePath) { refresh ->
        if (refresh) refreshes++
        Upstream(upstream.url("/direct"))
    }
    private val port = startOfflineProxy(OfflineRoutes(store) { k, p -> media.takeIf { k == key && p == MediaFilePath } })!!

    init {
        store.update { it.copy(items = mapOf(key to OfflineItem(OfflineKind.Movie, "m1", "Film", files = listOf(OfflineFile(MediaFilePath))))) }
    }

    @AfterTest
    fun tearDown() {
        upstream.close()
        scope.cancel()
        root.deleteRecursively()
    }

    private fun get(range: String?): Pair<Int, ByteArray> {
        val conn = URI(offlineUrl(port, key, MediaFilePath)).toURL().openConnection() as HttpURLConnection
        range?.let { conn.setRequestProperty("Range", it) }
        return conn.responseCode to conn.inputStream.use { it.readBytes() }
    }

    private fun filled() = store.item(key)!!.file(MediaFilePath)!!.ranges

    @Test
    fun aRangeReadThroughTheProxyIsServedAndKept() {
        val (code, body) = get("bytes=1000-299999")
        assertEquals(206, code)
        assertContentEquals(data.copyOfRange(1000, 300000), body)
        assertEquals(1000L, filled().spans.first().first)
        assertEquals(300000L, filled().spans.first().last + 1)
    }

    @Test
    fun aRangeAlreadyOnDiskNeverReachesTheServer() {
        get("bytes=0-499999")
        val served = upstream.bytesServed.get()
        upstream.down = true
        val (_, again) = get("bytes=100-400000")
        assertContentEquals(data.copyOfRange(100, 400001), again)
        assertEquals(served, upstream.bytesServed.get())
    }

    @Test
    fun aReadStraddlingDiskAndHoleFetchesOnlyTheHole() {
        get("bytes=0-99999")
        val before = upstream.bytesServed.get()
        val (_, body) = get("bytes=50000-199999")
        assertContentEquals(data.copyOfRange(50000, 200000), body)
        assertEquals(100000L, upstream.bytesServed.get() - before)
    }

    @Test
    fun aDeadSessionIsReplacedOnceAndTheReadCarriesOn() {
        upstream.goneOnce = setOf("/direct")
        val (_, body) = get("bytes=0-9999")
        assertContentEquals(data.copyOfRange(0, 10000), body)
        assertEquals(1, refreshes)
    }

    @Test
    fun fillingTheWholeFileMakesItComplete() = runBlocking {
        media.stream(0, media.size(), fromPlayer = false) { _, _ -> }
        assertTrue(store.item(key)!!.file(MediaFilePath)!!.complete)
        upstream.down = true
        val (code, body) = get(null)
        assertEquals(200, code)
        assertContentEquals(data, body)
    }

    @Test
    fun bytesFromAReplacedFileAreNeverMixedIntoAPartialDownload() {
        get("bytes=0-99999")
        val replaced = Random(8).nextBytes(data.size + 4096)
        upstream.files["/direct"] = replaced
        runCatching { get("bytes=200000-299999") }
        val file = store.item(key)!!.file(MediaFilePath)!!
        assertTrue(file.size == null || file.size == replaced.size.toLong())
        val kept = root.resolve("items/$key/$MediaFilePath").readBytes()
        for (span in file.ranges.spans) {
            assertContentEquals(replaced.copyOfRange(span.first.toInt(), span.last.toInt() + 1), kept.copyOfRange(span.first.toInt(), span.last.toInt() + 1))
        }
    }

    @Test
    fun onlyAUrlThisProcessHandedOutReadsAndOnlyFilesTheDownloadLists() {
        store.writeWhole(key, "secret.txt", "token".encodeToByteArray())
        val base = offlineUrl(port, key, "")
        listOf(
            base + "secret.txt",
            base + "../../index.json",
            base.replace(Regex("/o/[0-9a-f]+/"), "/o/guess/") + MediaFilePath,
        ).forEach { url ->
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            assertEquals(404, conn.responseCode, url)
        }
    }

    @Test
    fun aRangeStartingPastTheEndIsUnsatisfiable() {
        get("bytes=0-0")
        val conn = URI(offlineUrl(port, key, MediaFilePath)).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=${data.size}-")
        assertEquals(416, conn.responseCode)
        assertEquals("bytes */${data.size}", conn.getHeaderField("Content-Range"))
    }
}
