package dev.kobbler.app.ui.player

import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.StreamSearchResult

internal fun found(options: List<StreamOption>) = StreamSearchResult(options, emptyList())

internal fun debridOption(hash: String) = StreamOption(
    id = "o-$hash", releaseTitle = "Release.$hash", size = 1, indexer = "idx", infoHash = hash,
    quality = StreamQuality("1080p", null, null, null), qualityJson = null, totalScore = 1.0,
    debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
    sourceType = "torrent", fileId = 1, publishDate = null,
)
