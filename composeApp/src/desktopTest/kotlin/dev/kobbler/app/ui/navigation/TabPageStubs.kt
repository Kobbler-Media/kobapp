package dev.kobbler.app.ui.navigation

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** A server with a screenful or more of everything each tab page lists. */
internal object TabPageStubs {
    fun list(count: Int, wrap: String = "", key: String = "items", item: (Int) -> String) =
        (1..count).joinToString(",", prefix = """{"$key":[""", postfix = "]$wrap}", transform = item)

    private val now = Clock.System.now()

    private fun channel(i: Int) = """{"id":"c$i","lcn":$i,"name":"Channel $i"}"""
    private fun entry(i: Int, channel: Int = i) =
        """{"id":"g$i","channel_id":"c$channel","start_time":"${now - 10.minutes + (i * 60).minutes}","end_time":"${now + 50.minutes + (i * 60).minutes}","title":"Programme $i"}"""

    private val discover = buildString {
        append("""{"watch_next":[{"media_type":"movie","media_id":"m1","title":"Queued 1","link":"/movies/m1","progress_percent":40}],"recently_added":[],"sections":[""")
        append((1..4).joinToString(",") { s ->
            val items = (1..6).joinToString(",") { """{"provider":"tmdb","provider_id":"$s-$it","media_type":"movie","title":"Pick $s.$it","year":2024}""" }
            """{"title":"Shelf $s","section_key":"shelf_$s","sort_order":$s,"items":[$items]}"""
        })
        append("]}")
    }
    private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

    val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/discover" to { _ -> discover },
        "/api/v1/admin/movies" to { _ -> list(30, ""","total_count":30""") { """{"id":"f$it","title":"Film $it","year":2020}""" } },
        "/api/v1/admin/tv/shows" to { _ -> list(30, ""","total_count":30""") { """{"id":"s$it","title":"Show $it","year":2020}""" } },
        "/api/v1/admin/dashboard" to { _ -> "{}" },
        "/api/v1/library/books" to { _ -> list(30, ""","total_count":30""") { """{"id":"b$it","title":"Book $it","authors":[]}""" } },
        "/api/v1/admin/watch-history" to { _ -> list(30, ""","total":30""") { """{"media_type":"movie","tmdb_id":$it,"title":"Seen $it","sources":["trakt"]}""" } },
        "/api/v1/admin/wanted" to { _ -> list(30) { """{"id":"w$it","title":"Want $it","want_status":"wanted"}""" } },
        "/api/v1/admin/downloads" to { _ -> list(30) { """{"id":"d$it","name":"Download $it","status":"downloading"}""" } },
        "/api/v1/library/activity" to { _ -> list(40) { """{"id":"a$it","event_kind":"watched","title":"Watched $it","created_at":"2026-10-22T10:00:00Z"}""" } },
        "/api/v1/admin/notifications/inbox" to { _ -> list(40, ""","total":40""", key = "notifications") { """{"id":"n$it","title":"Note $it","message":"m","read":true}""" } },
        "/api/v1/admin/calendar" to { ex ->
            val range = Regex("from=([^&]+)&to=([^&]+)").find(ex.requestURI.rawQuery.orEmpty())!!.groupValues
            val dates = (1..40).associateWith { today.plus(DatePeriod(days = it / 4)).toString() }.filterValues { it in range[1]..range[2] }
            list(dates.size) { i -> dates.entries.elementAt(i - 1).let { (n, d) -> """{"id":"r$n","media_type":"movie","date":"$d","title":"Release $n","link":"/movies/r$n"}""" } }
        },
        "/api/v1/admin/iptv/groups" to { _ -> "[]" },
        "/api/v1/admin/iptv/channels" to { _ -> list(60, ""","total":60""") { """{"id":"i$it","name":"Iptv $it"}""" } },
        "/api/v1/admin/livetv/tuners" to { _ -> """{"items":[]}""" },
        "/api/v1/admin/livetv/channels" to { _ -> """{"items":[],"total":30}""" },
        "/api/v1/admin/livetv/guide" to { ex ->
            if ("channel_ids" in (ex.requestURI.rawQuery ?: "")) """{"channels":[{"channel":${channel(1)},"entries":[${(1..30).joinToString(",") { entry(it, 1) }}]}]}"""
            else list(30, key = "channels") { """{"channel":${channel(it)},"entries":[${entry(it)}]}""" }
        },
        // Nothing to suggest, so the page opens on the guide whose pinned header this checks.
        "/api/v1/admin/livetv/on-now" to { _ -> """{"generated_at":"","groups":[]}""" },
        "/api/v1/admin/livetv/series-rules" to { _ -> list(12) { """{"id":"r$it","title":"Rule $it"}""" } },
        "/api/v1/admin/livetv/recordings/r1" to { _ ->
            """{"id":"r1","title":"Recorded show","status":"completed","synopsis":"${"A long synopsis that runs on. ".repeat(80)}"}"""
        },
    )
}
