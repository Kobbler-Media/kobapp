package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryEventFrame
import dev.kobbler.app.data.SessionChapter
import dev.kobbler.app.data.ThumbnailsProbe
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

private val chapterJson = Json { ignoreUnknownKeys = true }

/** One frame of a debrid session's status stream, applied to the player playing it. */
internal suspend fun applyDebridSessionFrame(
    state: GlobalPlayerState,
    api: KobblerApi,
    sessionId: String,
    frame: LibraryEventFrame,
) {
    when (frame.kind) {
        "skip_segments_ready" -> {
            val segments = frame.data["segments"] ?: return
            val parsed = api.decodeSkipSegments(segments)
            if (parsed.isNotEmpty()) state.skipSegments = parsed
        }
        // Named by the server from the tracks' own text a few minutes into the
        // download; nothing renders these, they only name the player's own tracks.
        "subtitles" -> {
            val subtitles = frame.data["subtitles"] ?: return
            if (state.serverSubtitleTracks.none { !it.url.isNullOrBlank() }) {
                state.serverSubtitleTracks = api.decodeSubtitleTracks(subtitles)
            }
        }
        // The event carries the URL, but it is the same one the poll builds — so use
        // the event only as the trigger and let the helper confirm the file is servable.
        "thumbnails_ready" -> (api.checkThumbnailsReady(sessionId) as? ThumbnailsProbe.Ready)?.let {
            state.thumbnailsUrl = it.url
        }
        "chapters" -> {
            val chapters = frame.data["chapters"] ?: return
            state.chapters = chapterJson.decodeFromJsonElement(ListSerializer(SessionChapter.serializer()), chapters)
        }
        "loudness" -> frame.data["lufs"]?.jsonPrimitive?.doubleOrNull?.let { state.lufs = it }
    }
}
