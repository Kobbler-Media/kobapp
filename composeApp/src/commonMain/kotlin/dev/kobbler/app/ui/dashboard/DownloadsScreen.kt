package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.LocalTopChromeOverlap
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.components.FocusEdgeSlack
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Media a download belongs to. [mediaType] uses the same vocabulary as [dev.kobbler.app.data.WantedItem]. */
data class DownloadTarget(val mediaType: String, val id: String)

// kobload matches `status` exactly against a comma-separated list, so each bucket has
// to spell out every status the server actually stores.
private enum class DownloadFilter(val label: String, val statuses: String?) {
    All("All", null),
    Active("Active", "pending,queued,searching,downloading,processing"),
    Done("Done", "completed,imported,needs_review"),
    Failed("Failed", "failed,error"),
}

private val DownloadFilterLabels = DownloadFilter.entries.map { it.label }

private val ActiveStatuses = setOf("pending", "queued", "searching", "downloading", "processing")

@Composable
internal fun DownloadsScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    sectionSwitch: @Composable () -> Unit,
    onItemClick: (DownloadTarget) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accDown
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }

    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filterIdx by rememberSaveable { mutableIntStateOf(0) }
    val filter = DownloadFilter.entries[filterIdx]

    suspend fun fetch(): List<DownloadRow> =
        api.getDownloads(status = filter.statuses).items.map { it.toDownloadRow(api.apiUrl) }

    LaunchedEffect(filterIdx) {
        loading = true
        error = null
        try {
            rows = fetch()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load downloads"
        }
        loading = false
    }
    // Live, not polled. The events carry kobload's raw download JSON, which — unlike
    // the REST list — has not been through the server's media enrichment, so it has no
    // title, cover or target. Applying one wholesale would strip those from a row on
    // every progress tick; only the volatile fields are taken from the event and the
    // enriched ones are kept.
    //
    // A refetch is only needed when membership changes: an id nobody has seen, or a row
    // whose new status leaves the current filter. It deliberately does not touch
    // `loading`, so the list updates under the user rather than collapsing to a spinner.
    var refetchRequests by remember(filterIdx) { mutableIntStateOf(0) }
    LaunchedEffect(filterIdx) {
        api.libraryEvents().collect { frame ->
            runCatching {
                val payloads = when (frame.kind) {
                    "kobload_download_created", "kobload_download_updated" ->
                        listOfNotNull(frame.data["download"] as? JsonObject)
                    "kobload_download_snapshot" ->
                        (frame.data["downloads"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                    else -> return@runCatching
                }
                if (payloads.isEmpty()) return@runCatching
                val allowed = filter.statuses?.split(',')?.toSet()
                var membershipChanged = false
                val indexById = rows.withIndex().associate { it.value.id to it.index }
                val merged = payloads.mapNotNull { it.toDownloadRow(api.apiUrl) }
                val updated = rows.toMutableList()
                val removed = HashSet<String>()
                for (row in merged) {
                    val existingIdx = indexById[row.id]
                    if (existingIdx == null) {
                        // Never seen, so there is nothing to merge into and no enrichment
                        // to preserve — let the refetch bring it in properly.
                        if (allowed == null || row.status in allowed) membershipChanged = true
                        continue
                    }
                    if (allowed != null && row.status !in allowed) {
                        removed += row.id
                        membershipChanged = true
                        continue
                    }
                    updated[existingIdx] = rows[existingIdx].mergeVolatile(row)
                }
                rows = if (removed.isEmpty()) updated else updated.filterNot { it.id in removed }
                if (membershipChanged) refetchRequests++
            }
        }
    }

    LaunchedEffect(filterIdx, refetchRequests) {
        if (refetchRequests == 0) return@LaunchedEffect
        // Coalesce a burst of arrivals into one request, and never show a spinner: the
        // rows on screen stay put and are replaced when the enriched list lands.
        delay(1_000)
        runCatching { rows = fetch() }
    }

    val activeCount = rows.count { it.status in ActiveStatuses }

    PinnedHeader(
        header = {
            Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                BrowseHeader(
                    title = "Downloads",
                    accent = accent,
                    countLine = if (activeCount > 0) "$activeCount active" else "${rows.size} total",
                    compact = compact,
                    trailing = sectionSwitch,
                    secondaryRow = {
                        SegmentedFilter(
                            options = DownloadFilterLabels,
                            selectedIndex = filterIdx,
                            accent = accent,
                            onSelected = { filterIdx = it },
                        )
                    },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        val listState = rememberLazyListState()
        when {
            loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
            error != null && rows.isEmpty() -> EmptyState(
                icon = Icons.Outlined.CloudDownload,
                title = "Unable to load downloads",
                subtitle = error ?: "",
            )
            rows.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Download,
                title = "No downloads",
                subtitle = "Downloads from Kobload will appear here",
            )
            else -> FocusEdgeSlack {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = gutter, end = gutter, top = dimens.s2 + LocalTopChromeOverlap.current, bottom = dimens.s6),
                    verticalArrangement = Arrangement.spacedBy(dimens.s2),
                    modifier = Modifier.scrollsUnderTopChrome(listState).fillMaxSize(),
                ) {
                    items(rows, key = { it.id }) { row ->
                        DownloadListItem(row, accent, onItemClick)
                    }
                }
            }
        }
    }
}

private data class DownloadRow(
    val id: String,
    val title: String,
    val subtitle: String?,
    val status: String,
    val progress: Float?,
    val sizeBytes: Long,
    val rateBytesPerSec: Long?,
    val imageUrl: String?,
    val target: DownloadTarget?,
)

@Composable
private fun DownloadListItem(
    item: DownloadRow,
    accent: Color,
    onItemClick: (DownloadTarget) -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    val target = item.target
    Row(
        modifier = Modifier.fillMaxWidth()
            .focusKey("download.${item.id}")
            .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = 1.02f)
            .let {
                if (target != null) it.tvClickable(focusState) { onItemClick(target) }
                else it.onFocusChanged { s -> focusState.isFocused = s.isFocused }.focusable()
            }
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(colors.elev2),
            contentAlignment = Alignment.Center,
        ) {
            if (item.imageUrl != null) {
                AsyncImage(model = item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.Download, null, tint = colors.textLo, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.subtitle,
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(item.status, accent)
                if (item.sizeBytes > 0) {
                    Text(formatSize(item.sizeBytes), style = KobappTheme.type.bodySm, color = colors.textLo)
                }
                if (item.rateBytesPerSec != null && item.rateBytesPerSec > 0 && item.status in ActiveStatuses) {
                    Text("${formatSize(item.rateBytesPerSec)}/s", style = KobappTheme.type.bodySm, color = colors.textLo)
                }
            }
            if (item.progress != null && item.status in ActiveStatuses) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = accent,
                    trackColor = colors.elev2,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String, accent: Color) {
    val colors = KobappTheme.colors
    val (color, label) = when (status.lowercase()) {
        "completed", "imported" -> colors.ok to "Done"
        "needs_review" -> colors.warn to "Review"
        "downloading" -> accent to "Active"
        "processing" -> accent to "Importing"
        "searching" -> accent to "Searching"
        "failed", "error" -> colors.err to "Failed"
        "queued", "pending" -> colors.warn to "Queued"
        else -> colors.textLo to status
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = KobappTheme.type.bodySm, color = color)
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        val rounded = (gb * 10).toLong() / 10.0
        "$rounded GB"
    } else {
        "${bytes / (1024 * 1024)} MB"
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

// Callers put their ids either at the top of `metadata` or inside `original_metadata`.
private fun JsonObject.metadata(field: String): JsonElement? {
    val meta = this["metadata"] as? JsonObject ?: return null
    return meta[field] ?: (meta["original_metadata"] as? JsonObject)?.get(field)
}

private fun JsonObject.metadataString(field: String): String? =
    (metadata(field) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * `media.id` is a book / movie / show id depending on which metadata id the server matched;
 * nothing in the payload says which, so the type is re-derived here using the same
 * precedence the enrichment uses (book, movie, episode, season, show).
 */
private fun JsonObject.resolveTarget(mediaId: String?): DownloadTarget? {
    if (mediaId == null) return null
    val type = when {
        metadataString("book_id") != null -> "book"
        metadataString("movie_id") != null -> "movie"
        metadataString("episode_id") != null || metadataString("season_id") != null ||
            metadataString("show_id") != null || metadata("episode_map") is JsonObject -> "tv_show"
        else -> when (metadataString("source_type")) {
            "book" -> "book"
            "movie" -> "movie"
            "tv_episode", "tv_season", "tv_show" -> "tv_show"
            else -> return null
        }
    }
    return DownloadTarget(type, mediaId)
}

/**
 * Keep everything the server's enrichment supplied and take only what actually moves.
 * An event payload has no `media`, so its title/subtitle/image/target are placeholders.
 */
private fun DownloadRow.mergeVolatile(from: DownloadRow) = copy(
    status = from.status,
    progress = from.progress,
    sizeBytes = from.sizeBytes,
    rateBytesPerSec = from.rateBytesPerSec,
)

private fun JsonObject.toDownloadRow(apiUrl: String): DownloadRow {
    val media = this["media"] as? JsonObject
    val releaseName = str("name") ?: "Unknown"
    val clientProgress = this["client_progress"] as? JsonObject
    val cover = media?.str("cover_url")
    return DownloadRow(
        id = str("id") ?: releaseName,
        title = media?.str("title") ?: releaseName,
        subtitle = media?.str("subtitle") ?: media?.str("author"),
        status = str("status") ?: "unknown",
        progress = this["progress"]?.jsonPrimitive?.doubleOrNull?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
        sizeBytes = clientProgress?.get("total_size")?.jsonPrimitive?.longOrNull ?: 0L,
        rateBytesPerSec = clientProgress?.get("download_rate")?.jsonPrimitive?.longOrNull,
        imageUrl = when {
            cover.isNullOrEmpty() -> null
            cover.startsWith("http://") || cover.startsWith("https://") -> cover
            else -> "$apiUrl$cover"
        },
        target = resolveTarget(media?.str("id")),
    )
}
