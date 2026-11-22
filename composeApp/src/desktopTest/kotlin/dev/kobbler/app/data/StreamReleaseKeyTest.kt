package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.player.debridOption
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StreamReleaseKeyTest {

    private val torrent = """{"id":"t","release_title":"T","debrid_provider":"torbox","debrid_provider_id":"p1",
        "release_key":"aa11","files":[{"id":3,"path":"t.mkv"}]}"""
    private val importedNzb = """{"id":"n","release_title":"N","debrid_provider":"torbox","debrid_provider_id":"p1",
        "source_type":"usenet","release_key":"nzb-bb22","nzb_ref":{"indexer_id":"i1","guid":"g1"}}"""
    private val rawNzb = """{"id":"r","release_title":"R","debrid_provider":"torbox","debrid_provider_id":"p1",
        "source_type":"usenet","release_key":null,"nzb_ref":{"indexer_id":"i1","guid":"g2"}}"""

    private fun <T> withApi(routes: Map<String, (HttpExchange) -> String>, block: suspend KobblerApi.(StubServer) -> T): T =
        StubServer(routes).use { stub -> runBlocking { KobblerApi(HttpClient(), stub.url, "token").block(stub) } }

    private fun StubServer.sent(path: String): JsonObject = Json.parseToJsonElement(requestBodies.getValue(path)).jsonObject

    @Test
    fun `search reads release keys and nzb refs and leads with the preferred release key`() {
        val body = """{"options":[$torrent,$importedNzb,$rawNzb],"preferred_release_key":"nzb-bb22"}"""
        val options = withApi(mapOf("/api/v1/admin/stream/search" to { _ -> body })) { streamSearch(episodeId = "e1").options }
        assertEquals(listOf("n", "t", "r"), options.map { it.id })
        assertEquals(listOf("nzb-bb22", "aa11", null), options.map { it.releaseKey })
        assertEquals(listOf(NzbRef("i1", "g1"), null, NzbRef("i1", "g2")), options.map { it.nzbRef })
    }

    @Test
    fun `prewarm and local options carry the release key and nzb ref`() {
        val routes = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/stream/prewarm" to { _ -> """{"state":"ready","prewarm_id":"w1","option":$importedNzb}""" },
            "/api/v1/admin/stream/local" to { _ -> importedNzb },
        )
        val (warm, local) = withApi(routes) {
            prewarmStream(StreamTarget.movie("m1"))!!.option!! to localStreamOption("movie", "m1")!!
        }
        for (option in listOf(warm, local)) {
            assertEquals("nzb-bb22", option.releaseKey)
            assertEquals(NzbRef("i1", "g1"), option.nzbRef)
            assertEquals("usenet", option.sourceType)
        }
    }

    @Test
    fun `resolve names the release by key and an unimported nzb by its ref`() {
        val resolve = "/api/v1/admin/stream/resolve"
        val routes = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/stream/search" to { _ -> """{"options":[$torrent,$rawNzb]}""" },
            resolve to { _ -> """{"debrid_provider_id":"p1","torrent_id":"x"}""" },
        )
        val bodies = withApi(routes) { stub ->
            streamSearch(episodeId = "e1").options.map { option ->
                streamResolve(option, "episode", "e1")
                stub.sent(resolve)
            }
        }
        val (torrentBody, nzbBody) = bodies
        assertEquals("\"aa11\"", torrentBody["release_key"].toString())
        assertNull(torrentBody["nzb_ref"])
        assertNull(nzbBody["release_key"])
        assertEquals("""{"indexer_id":"i1","guid":"g2"}""", nzbBody["nzb_ref"].toString())
        for (body in bodies) assertFalse("info_hash" in body || "nzb_url" in body, "$body")
    }

    @Test
    fun `a bad copy is reported by release key`() {
        val bad = "/api/v1/admin/stream/bad"
        val sent = withApi(mapOf(bad to { _ -> "" })) { stub ->
            reportBadCopy(debridOption("aa11"), bad = true)
            stub.sent(bad)
        }
        assertEquals("""{"debrid_provider_id":"p1","release_key":"aa11","file_id":1,"bad":true}""", sent.toString())
    }
}
