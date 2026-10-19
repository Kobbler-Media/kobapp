package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import dev.kobbler.app.ui.components.ListSkeleton
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvFocusGlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.ActivityEventDto
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.ErrorScreen
import dev.kobbler.app.ui.components.LoadingScreen
import dev.kobbler.app.ui.components.SectionHeading
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Composable
fun ActivityScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accNotif
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }

    val events = remember { mutableStateListOf<ActivityEventDto>() }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var totalCount by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableStateOf(0) }

    LaunchedEffect(retryToken) {
        loading = true
        errorMessage = null
        events.clear()
        try {
            val page = api.getActivity(limit = 50)
            events.addAll(page.items)
            nextCursor = page.nextCursor
            totalCount = page.totalCount
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load activity"
        } finally {
            loading = false
        }
    }

    suspend fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        loadingMore = true
        try {
            val page = api.getActivity(cursor = cursor, limit = 50)
            events.addAll(page.items)
            nextCursor = page.nextCursor
        } catch (_: Exception) {
        } finally {
            loadingMore = false
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
            BrowseHeader(
                title = "Activity",
                accent = accent,
                countLine = when {
                    loading -> null
                    totalCount != null -> "${totalCount} events"
                    events.isNotEmpty() -> "${events.size}+ events"
                    else -> "no recent activity"
                },
                compact = compact,
            )
        }

        when {
            loading -> LoadingScreen({ ListSkeleton(title = false) })
            errorMessage != null && events.isEmpty() -> ErrorScreen(
                message = errorMessage!!,
                onRetry = { retryToken++ },
            )
            events.isEmpty() -> EmptyState(
                icon = Icons.Outlined.History,
                title = "No recent activity",
                subtitle = "Watching, reading, and listening events will show up here.",
            )
            else -> {
                val grouped = remember(events.toList()) { groupEventsByDay(events) }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = gutter, end = gutter,
                        top = dimens.s2, bottom = dimens.s6,
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimens.s1),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    grouped.forEach { (day, dayEvents) ->
                        item(key = "h-$day") {
                            Box(Modifier.padding(top = dimens.s3, bottom = dimens.s2)) {
                                SectionHeading(
                                    title = day,
                                    accent = accent,
                                )
                            }
                        }
                        items(dayEvents, key = { it.id }) { event ->
                            ActivityRow(event = event, accent = accent)
                        }
                    }

                    if (nextCursor != null) {
                        item(key = "load-more") {
                            LaunchedEffect(Unit) { loadMore() }
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = dimens.s3),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (loadingMore) "Loading more…" else "",
                                    color = colors.textLo,
                                    style = KobappTheme.type.bodySm,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(event: ActivityEventDto, accent: androidx.compose.ui.graphics.Color) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    // Rows aren't actionable, but a D-pad can only scroll a list by moving focus
    // through it — without a focus target the feed is unreachable on TV.
    val focusState = rememberTvFocusState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey("event.${event.id}")
            .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.01f)
            .onFocusChanged { focusState.isFocused = it.isFocused }
            .focusable()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = dimens.s3, vertical = dimens.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.elev2),
            contentAlignment = Alignment.Center,
        ) {
            val image = event.imageUrl
            if (!image.isNullOrBlank()) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    event.eventKind.firstOrNull()?.uppercase() ?: "·",
                    color = accent,
                    style = KobappTheme.type.label,
                )
            }
        }
        Spacer(Modifier.size(dimens.s3))
        Column(Modifier.weight(1f).padding(end = dimens.s2)) {
            Text(
                eventKindLabel(event.eventKind).uppercase(),
                color = accent,
                style = KobappTheme.type.label,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                event.title,
                color = colors.textHi,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = event.subtitle
            if (!sub.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    color = colors.textLo,
                    style = KobappTheme.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(dimens.s2))
        Text(
            relativeTime(event.createdAt),
            color = colors.textLo,
            style = KobappTheme.type.bodySm,
            maxLines = 1,
        )
    }
}

private fun eventKindLabel(kind: String): String = when (kind) {
    "movie.watched", "movie.progress" -> "Watched"
    "episode.watched", "episode.progress" -> "Watched"
    "book.read", "book.progress" -> "Read"
    "audiobook.listened", "audiobook.progress" -> "Listened"
    "book.added", "movie.added", "episode.added", "show.added" -> "Added"
    "book.wanted", "movie.wanted", "show.wanted" -> "Wanted"
    "book.completed" -> "Finished"
    else -> kind.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun groupEventsByDay(events: List<ActivityEventDto>): List<Pair<String, List<ActivityEventDto>>> {
    val now = Clock.System.now()
    val today = dayKey(now)
    val yesterday = dayKey(now - 1.days)
    return events
        .groupBy { dayKey(parseInstant(it.createdAt) ?: now) }
        .toList()
        .map { (key, evs) ->
            val label = when (key) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> key
            }
            label to evs
        }
}

private fun dayKey(instant: Instant): String {
    val iso = instant.toString()
    return iso.substringBefore('T')
}

private fun parseInstant(s: String): Instant? = try {
    Instant.parse(s)
} catch (_: Exception) {
    null
}

private fun relativeTime(s: String): String {
    val then = parseInstant(s) ?: return ""
    val now = Clock.System.now()
    val seconds = (now - then).inWholeSeconds
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        seconds < 86_400 * 7 -> "${seconds / 86_400}d"
        seconds < 86_400 * 30 -> "${seconds / (86_400 * 7)}w"
        else -> "${seconds / (86_400 * 30)}mo"
    }
}
