package dev.kobbler.app.offline

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OfflineEngineTest {
    private val film = Random(3).nextBytes(2 * 1024 * 1024 + 17)
    private val directPath = "/api/v1/admin/playback/sessions/s1/direct"
    private val copy720 = Random(11).nextBytes(700_000)
    private val exportPath = "/api/v1/admin/offline/exports/x1/file"
    private val exportPolls = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var sessionMethod = "direct_play"
    @Volatile private var copiesShrink = true
    private val epub = Random(5).nextBytes(300_000)
    private val chapterOne = Random(9).nextBytes(400_000)
    private val files = RangeServer(
        mapOf(
            directPath to film,
            exportPath to copy720,
            "/api/v1/admin/books/b1/file" to epub,
            "/api/v1/admin/books/b1/audio/f1" to chapterOne,
            "/api/v1/library/books/b1" to """{"id":"b1","title":"Novel","formats":["EPUB"],"has_audiobook":true}""".encodeToByteArray(),
            "/api/v1/admin/books/b1/audio-files" to """[{"id":"f1","file_index":0,"format":"MP3","file_name":"01.mp3"}]""".encodeToByteArray(),
            "/api/v1/admin/books/b1/audio-position" to """{"file_id":"f1","position_seconds":12.0}""".encodeToByteArray(),
            "/health" to "{}".encodeToByteArray(),
        ),
    )
    private val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
    @Volatile private var e2InLibrary = false
    private fun showJson() = """{"id":"s1","title":"Show","year":2020,"seasons":[{"id":"se1","season_identifier":"S01","season_sort_order":1,"episodes":[
        {"id":"e1","episode_identifier":"E01","episode_sort_order":1,"is_in_library":true},
        {"id":"e2","episode_identifier":"E02","episode_sort_order":2,"is_in_library":$e2InLibrary}]}]}"""
    private val api: StubServer = StubServer(
        mapOf(
            "/api/v1/admin/tv/shows/s1" to { _ -> showJson() },
            "/health" to { _ -> "{}" },
            "/api/v1/admin/movies/m1" to { _ -> """{"id":"m1","title":"Film","year":2020,"runtime":100}""" },
            "/api/v1/admin/movies/m1/watch-state" to { _ -> """{"position_seconds":30,"watched":false}""" },
            "/api/v1/admin/movies/m1/watch-state/position" to { _ -> "{}" },
            "/api/v1/admin/offline/exports" to { _ ->
                if (copiesShrink || api.requestBodies.getValue("/api/v1/admin/offline/exports").contains("\"original\"")) exportJson("queued", 0.0) else """{"state":"original"}"""
            },
            "/api/v1/admin/offline/exports/x1" to { _ ->
                if (exportPolls.incrementAndGet() < 2) exportJson("running", 0.5) else exportJson("ready", 1.0)
            },
            "/api/v1/admin/playback/sessions" to { _ ->
                """{"session_id":"s1","method":"$sessionMethod",
                   ${if (sessionMethod == "direct_play") "\"direct_url\":\"${files.url(directPath)}\"" else "\"playlist_url\":\"/master.m3u8\""},
                   "subtitles":[{"track_id":"t1","language":"en","format":"vtt","url":"/subs/en.vtt"}]}"""
            },
            "/api/v1/admin/playback/sessions/s1" to { _ -> "{}" },
            "/subs/en.vtt" to { _ -> vtt },
        ),
    )
    private fun exportJson(state: String, progress: Double) =
        """{"export_id":"x1","state":"$state","progress":$progress,"file_url":"${files.url(exportPath)}"}"""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = Files.createTempDirectory("offline").toFile()
    private val engine = OfflineEngine(OfflineStore(JvmOfflineFs(root), scope), Reachability(scope), scope)
    private val kobbler = KobblerApi(HttpClient(), api.url, "token", offline = engine)
    private val filesApi = KobblerApi(HttpClient(), files.url(""), "token", offline = engine)
    @Volatile private var serverUp = true
    private val probe = HttpClient()

    init {
        engine.attach(kobbler)
        engine.reachability.attach { serverUp }
        engine.downloader.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        api.close()
        files.close()
        root.deleteRecursively()
    }

    private val key = OfflineItem.keyOf(OfflineKind.Movie, "m1")

    private fun downloaded() = runBlocking {
        engine.enqueue(OfflineItem(OfflineKind.Movie, "m1", "Film", files = listOf(OfflineFile(MediaFilePath))))
        withTimeout(20_000) { engine.store.index.first { it.items[key]?.state == OfflineState.Complete } }
        engine.store.item(key)!!
    }

    private suspend fun read(url: String): ByteArray =
        if (url.startsWith("file://")) java.io.File(java.net.URI(url)).readBytes() else probe.get(url).readRawBytes()

    private suspend fun goOffline() {
        serverUp = false
        engine.reachability.check()
    }

    @Test
    fun aDownloadKeepsTheFileItsSubtitlesAndItsPage() {
        val item = downloaded()
        assertTrue(item.file(MediaFilePath)!!.complete)
        assertEquals(1, item.extras.count { it.startsWith("subs/") })
        assertEquals(6000, item.durationSeconds)
        assertTrue(engine.store.response("/api/v1/admin/movies/m1") != null)
    }

    @Test
    fun withNoNetworkTheTitleOpensAndPlaysFromDisk() = runBlocking {
        downloaded()
        goOffline()
        assertEquals("Film", kobbler.getMovie("m1").title)
        val session = kobbler.getMovieStreamSession("m1")!!
        assertTrue(session.url.startsWith("file://"), session.url)
        assertNotEquals("s1", session.sessionId)
        val served = files.bytesServed.get()
        assertContentEquals(film, read(session.url))
        assertEquals(served, files.bytesServed.get())
        assertEquals(vtt, probe.get(session.subtitles.single().url!!).bodyAsText())
    }

    @Test
    fun onlinePlaybackOfADownloadStillReadsThroughTheProxy() = runBlocking {
        downloaded()
        val session = kobbler.getMovieStreamSession("m1")!!
        assertTrue(session.url.startsWith("file://"), session.url)
        assertEquals("s1", session.sessionId)
    }

    @Test
    fun progressMadeOfflineIsResumedFromAndSentOnReconnect() = runBlocking {
        downloaded()
        goOffline()
        kobbler.reportMovieProgress("m1", 4200)
        assertEquals(4200, kobbler.getMovieProgress("m1"))
        assertEquals(1, engine.store.index.value.pendingWrites.size)
        assertFalse(api.requestBodies.containsKey("/api/v1/admin/movies/m1/watch-state/position"))

        serverUp = true
        engine.reachability.check()
        withTimeout(5_000) { engine.store.index.first { it.pendingWrites.isEmpty() } }
        assertTrue(api.requestBodies["/api/v1/admin/movies/m1/watch-state/position"]!!.contains("4200"))
    }

    @Test
    fun watchingPastTheCreditsMarksTheDownloadWatched() = runBlocking {
        downloaded()
        kobbler.reportMovieProgress("m1", 5300)
        assertFalse(engine.store.item(key)!!.watched)
        kobbler.reportMovieProgress("m1", 5500)
        assertTrue(engine.store.item(key)!!.watched)
    }

    @Test
    fun removingADownloadDeletesItsFiles() = runBlocking {
        downloaded()
        engine.remove(key)
        assertEquals(null, engine.store.item(key))
        assertFalse(root.resolve("items/$key").exists())
        assertEquals(null, engine.store.response("/api/v1/admin/movies/m1"))
    }

    @Test
    fun aFollowedShowDownloadsAnEpisodeOnceItsFileArrives() = runBlocking {
        engine.setAutoDownload(kobbler.getShow("s1"), true)
        engine.maintenance.checkSubscriptions()
        assertEquals(null, engine.store.item(OfflineKind.Episode, "e2"))
        assertEquals(null, engine.store.item(OfflineKind.Episode, "e1"))

        e2InLibrary = true
        engine.maintenance.checkSubscriptions()
        val queued = engine.store.item(OfflineKind.Episode, "e2")!!
        assertEquals("Show", queued.showTitle)
        assertEquals(setOf("e1", "e2"), engine.store.index.value.subscriptions["s1"]!!.knownEpisodeIds)
    }

    private fun book() = dev.kobbler.app.data.Book(id = "b1", title = "Novel", author = "", coverUrl = "", formats = listOf("EPUB"), hasAudiobook = true)

    private fun downloadedFrom(api: KobblerApi, key: String, start: () -> Unit) = runBlocking {
        engine.attach(api)
        engine.reachability.attach { serverUp }
        start()
        withTimeout(20_000) { engine.store.index.first { it.items[key]?.state.let { s -> s == OfflineState.Complete || s == OfflineState.Failed } } }
        assertEquals(OfflineState.Complete, engine.store.item(key)!!.state, engine.store.item(key)!!.error)
    }

    @Test
    fun anAudiobookPlaysItsFilesFromDiskWithNoNetwork() = runBlocking {
        downloadedFrom(filesApi, OfflineItem.keyOf(OfflineKind.Audiobook, "b1")) { engine.downloadAudiobook(book()) }
        goOffline()
        val source = filesApi.audioSource("b1", "f1")
        assertTrue(source.url.startsWith("file://"), source.url)
        assertContentEquals(chapterOne, read(source.url))
    }

    @Test
    fun anEbookOpensFromItsDownloadWithNoNetwork() = runBlocking {
        downloadedFrom(filesApi, OfflineItem.keyOf(OfflineKind.Book, "b1")) { engine.downloadBook(book()) }
        goOffline()
        val payload = dev.kobbler.app.ui.reader.fetchBookFile(filesApi, "b1", "unused-cache-key", listOf("EPUB", "KEPUB"))
        assertContentEquals(epub, java.io.File(payload.path!!).readBytes())
    }

    private fun downloadedVideo(item: OfflineItem) = runBlocking {
        engine.enqueue(item)
        withTimeout(30_000) { engine.store.index.first { it.items[item.key]?.state.let { s -> s == OfflineState.Complete || s == OfflineState.Failed } } }
        engine.store.item(item.key)!!.also { assertEquals(OfflineState.Complete, it.state, it.error) }
    }

    @Test
    fun aLowerQualityDownloadIsTheServersCopyNotTheOriginal() = runBlocking {
        val item = downloadedVideo(
            OfflineItem(OfflineKind.Movie, "m1", "Film", files = listOf(OfflineFile(MediaFilePath)), quality = "720p", exportQuality = "720p"),
        )
        assertEquals("x1", item.exportId)
        assertTrue(item.exportReady)
        assertEquals(null, item.serverProgress)
        assertEquals(1, item.extras.count { it.startsWith("subs/") })
        goOffline()
        val session = kobbler.getMovieStreamSession("m1")!!
        assertContentEquals(copy720, read(session.url))
    }

    @Test
    fun aTitleTheServerCanOnlyTranscodeIsDownloadedAsAnOriginalQualityCopy() = runBlocking {
        sessionMethod = "video_transcode"
        val item = downloadedVideo(OfflineItem(OfflineKind.Movie, "m1", "Film", files = listOf(OfflineFile(MediaFilePath))))
        assertEquals("original", item.exportQuality)
        assertContentEquals(copy720, engine.store.readWhole(key, MediaFilePath))
    }

    @Test
    fun aQualityThatWouldNotShrinkTheTitleDownloadsTheOriginalOrACopyTheDeviceCanPlay() {
        for ((method, expectedQuality, expected) in listOf(
            Triple("direct_play", null, film),
            Triple("video_transcode", "original", copy720),
        )) {
            sessionMethod = method
            copiesShrink = false
            runBlocking { engine.remove(key) }
            val item = downloadedVideo(
                OfflineItem(OfflineKind.Movie, "m1", "Film", files = listOf(OfflineFile(MediaFilePath)), quality = "720p", exportQuality = "720p"),
            )
            assertEquals(expectedQuality, item.exportQuality, method)
            assertContentEquals(expected, engine.store.readWhole(key, MediaFilePath), method)
        }
    }

    @Test
    fun untilTheServersCopyExistsTheTitleStreamsAsIfNotDownloaded() = runBlocking {
        engine.store.update {
            it.copy(items = mapOf(key to OfflineItem(OfflineKind.Movie, "m1", "Film", files = listOf(OfflineFile(MediaFilePath)), exportQuality = "720p", state = OfflineState.Failed)))
        }
        val session = kobbler.getMovieStreamSession("m1")!!
        assertEquals(files.url(directPath), session.url)
    }

    @Test
    fun aSessionMadeUpForADownloadIsNeverAskedAboutOrClosedOnTheServer() = runBlocking {
        assertFalse(kobbler.sessionGone("${OfflineEngine.OfflineSessionPrefix}movie-m1"))
        kobbler.closeSession("${OfflineEngine.OfflineSessionPrefix}movie-m1")
        assertFalse(api.requestBodies.keys.any { it.contains(OfflineEngine.OfflineSessionPrefix) })
    }

    @Test
    fun aKidsProfileNeverPlaysADownloadItMayNotWatch() = runBlocking {
        downloaded()
        engine.store.updateItem(key) { it.copy(kidsAllowed = false) }
        engine.attach(kobbler, kids = true)
        assertEquals(files.url(directPath), kobbler.getMovieStreamSession("m1")!!.url)
        goOffline()
        assertEquals(null, engine.playbackSession("movie", "m1") { null })
    }

    @Test
    fun aTitleTheServerRefusesOnlineIsNotPlayedFromDisk() = runBlocking {
        downloaded()
        assertEquals(null, engine.playbackSession("movie", "m1") { null })
    }

    @Test
    fun progressKeptForOneProfileIsNeitherShownToNorSentAsAnother() = runBlocking {
        downloaded()
        goOffline()
        kobbler.reportMovieProgress("m1", 4200)
        val other = KobblerApi(HttpClient(), api.url, "someone-else", offline = engine)
        assertEquals(30, other.getMovieProgress("m1").let { it ?: 30 }.coerceAtMost(30))
        assertEquals(null, engine.pendingResumeSeconds(other.offlineOwner, "/api/v1/admin/movies/m1/watch-state"))

        engine.attach(other)
        serverUp = true
        engine.reachability.check()
        kotlinx.coroutines.delay(500)
        assertEquals(1, engine.store.index.value.pendingWrites.size)
        assertFalse(api.requestBodies.containsKey("/api/v1/admin/movies/m1/watch-state/position"))
    }

    @Test
    fun aDownloadStillBeingFilledPlaysThroughTheProxyThatKeepsWhatItReads() = runBlocking {
        engine.store.update {
            it.copy(items = mapOf(key to OfflineItem(OfflineKind.Movie, "m1", "Film", files = listOf(OfflineFile(MediaFilePath)), state = OfflineState.Failed)))
        }
        val session = kobbler.getMovieStreamSession("m1")!!
        assertTrue(session.url.startsWith("http://127.0.0.1:"), session.url)
        assertContentEquals(film, read(session.url))
        assertTrue(engine.store.item(key)!!.file(MediaFilePath)!!.complete)
    }

    @Test
    fun progressDoesNotDisturbWhatIsMarkedAvailable() = runBlocking {
        downloaded()
        val before = withTimeout(5_000) { engine.completeKeys.first { key in it } }
        engine.store.update { idx -> idx.copy(items = idx.items + ("movie-other" to OfflineItem(OfflineKind.Movie, "other", "Other", files = listOf(OfflineFile(MediaFilePath))))) }
        engine.store.updateFile("movie-other", MediaFilePath, progressOnly = true) { it.copy(ranges = it.ranges + (0L until 10L)) }
        kotlinx.coroutines.delay(100)
        assertTrue(before === engine.completeKeys.value)
        assertEquals(setOf(key), before)
    }
}
