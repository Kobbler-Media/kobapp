package dev.kobbler.app.offline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val QueueablePath = Regex("""/(watch-state(/position)?|audio-position|reader-progress)$""")

private val WatchStatePath = Regex("""^/api/v1/admin/(movies|tv/episodes)/([^/]+)/watch-state(/position)?$""")
private val BookProgressPath = Regex("""^/api/v1/admin/books/([^/]+)/(audio-position|reader-progress)$""")

fun isQueueableWrite(path: String) = QueueablePath.containsMatchIn(path)

/** Keeps one pending write per resource: only the latest position is worth sending. */
fun List<PendingWrite>.enqueue(write: PendingWrite): List<PendingWrite> =
    filterNot { it.owner == write.owner && it.method == write.method && it.path == write.path } + write

fun List<PendingWrite>.resumeSecondsFor(owner: String, watchStatePath: String): Int? {
    val latest = lastOrNull { it.owner == owner && (it.path == watchStatePath || it.path == "$watchStatePath/position") } ?: return null
    val body = latest.json() ?: return null
    if (body.boolean("watched") == true) return 0
    return body.double("position_seconds")?.toInt() ?: 0
}

private const val WatchedFraction = 0.9
private const val FinishedPercent = 99.0

/** The download a write says was finished, or null when it says nothing of the kind. */
fun finishedBy(path: String, body: String, durationSecondsOf: (String) -> Int?): String? {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    WatchStatePath.find(path)?.let { match ->
        val kind = if (match.groupValues[1] == "movies") OfflineKind.Movie else OfflineKind.Episode
        val key = OfflineItem.keyOf(kind, match.groupValues[2])
        val finished = if (match.groupValues[3].isEmpty()) {
            json.boolean("watched") == true
        } else {
            val duration = durationSecondsOf(key) ?: return null
            val position = json.double("position_seconds") ?: return null
            duration > 0 && position >= duration * WatchedFraction
        }
        return key.takeIf { finished }
    }
    BookProgressPath.find(path)?.let { match ->
        val kind = if (match.groupValues[2] == "audio-position") OfflineKind.Audiobook else OfflineKind.Book
        return OfflineItem.keyOf(kind, match.groupValues[1]).takeIf { (json.double("progress_percent") ?: 0.0) >= FinishedPercent }
    }
    return null
}

private fun PendingWrite.json(): JsonObject? = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

private fun JsonObject.boolean(name: String) = this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.double(name: String) = this[name]?.jsonPrimitive?.doubleOrNull
