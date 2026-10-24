package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveFacet
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.CenterFocusedItem
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.bleedHorizontally
import dev.kobbler.app.ui.components.rowEdgeBleed
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The focused guide row, held off the screen's composition — every reader takes the
 * holder and reads inside its own scope, so a D-pad press does not recompose the grid.
 */
@Stable
internal class GuideFocus {
    var lazyIndex by mutableIntStateOf(-1)
}

/** Reads [GuideFocus.lazyIndex] in its own scope, so a D-pad press never recomposes the screen. */
@Composable
internal fun CenterFocusedRow(state: LazyListState, focus: GuideFocus, topRow: Int = -1) {
    CenterFocusedItem(state, focus.lazyIndex, topRow)
}

/**
 * The guide's clock, ticking for as long as it is on screen.
 *
 * Held rather than passed down so a tick recomposes the cells that read it and not the
 * screen around them — the same reason [GuideFocus] and [LiveGuideSelectionHolder] exist.
 */
@Stable
class GuideClock(now: Instant = nowInstant()) {
    var now by mutableStateOf(now)
}

// Half a minute of a programme being marked the wrong way is not worth a finer tick, and
// the guide is a page that can sit open for an evening.
private const val GuideClockTickMs = 30_000L

@Composable
internal fun rememberGuideClock(active: Boolean): GuideClock {
    val clock = remember { GuideClock() }
    LaunchedEffect(active) {
        while (active) {
            clock.now = nowInstant()
            delay(GuideClockTickMs)
        }
    }
    return clock
}

/**
 * Whether [now] falls inside this programme — the one on-air test there is.
 *
 * The feed's own `is_now` is true of the moment the window was fetched, which is not the
 * moment anybody looks at it: a guide left open half an hour puts the accent on something
 * that has finished, and answers OK on what is actually airing by doing nothing.
 */
internal fun LiveGuideEntry.isAiringAt(now: Instant): Boolean {
    val start = parseLiveInstant(startTime) ?: return false
    val end = parseLiveInstant(endTime) ?: return false
    return now >= start && now < end
}

/** Per-profile, so two people on one box do not inherit each other's last channel. */
internal const val LastLiveChannelKey = "livetv.last_channel"

/**
 * What the guide opens on: the channel last watched, and whatever it is airing.
 *
 * A TV comes back up on the channel it was left on. Opening on whichever channel the
 * lineup sorted first meant the preview pane described one nobody had asked for.
 */
internal fun initialGuideSelection(
    guide: List<LiveGuideChannel>,
    lastChannelId: String?,
    now: Instant = nowInstant(),
): LiveGuideSelection? {
    // Only a row with something in it: a channel the feed has no listings for this window
    // is not somewhere to open — the pane would describe nothing and the page would have
    // no Watch button to hand the remote.
    val listed = guide.filter { it.entries.isNotEmpty() }
    val row = listed.firstOrNull { it.channel.id == lastChannelId } ?: listed.firstOrNull() ?: return null
    val entry = row.entries.firstOrNull { it.isAiringAt(now) } ?: row.entries.first()
    return LiveGuideSelection(row.channel, entry)
}

internal fun parseLiveInstant(iso: String?): Instant? =
    if (iso.isNullOrBlank()) null else try { Instant.parse(iso) } catch (_: Exception) { null }

internal fun Instant.localHhMm(): String {
    val dt = toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.hour.pad2()}:${dt.minute.pad2()}"
}

internal fun Instant.localDayLabel(): String {
    val dt = toLocalDateTime(TimeZone.currentSystemDefault())
    return "${DayNames[dt.dayOfWeek.ordinal]} ${dt.day} ${MonthNames[dt.month.ordinal]}"
}

internal fun Instant.isLocalToday(): Boolean {
    val tz = TimeZone.currentSystemDefault()
    return toLocalDateTime(tz).date == Clock.System.now().toLocalDateTime(tz).date
}

internal fun Instant.localWeekdayIndex(): Int {
    // Server weekdays are Postgres DOW: 0 = Sunday. DayOfWeek.ordinal is 0 = Monday.
    val dt = toLocalDateTime(TimeZone.currentSystemDefault())
    return (dt.dayOfWeek.ordinal + 1) % 7
}

internal fun hhmmRange(startIso: String?, endIso: String?): String {
    val s = parseLiveInstant(startIso) ?: return ""
    val e = parseLiveInstant(endIso) ?: return s.localHhMm()
    return "${s.localHhMm()} – ${e.localHhMm()}"
}

internal fun minutesLabel(seconds: Long): String {
    val mins = (seconds / 60).toInt()
    if (mins <= 0) return ""
    if (mins < 60) return "$mins min"
    val h = mins / 60
    val m = mins % 60
    return if (m == 0) "$h h" else "$h h $m"
}

internal fun sizeLabel(bytes: Long): String {
    if (bytes <= 0) return ""
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "${((gb * 10).toLong() / 10.0)} GB"
    val mb = bytes / 1_000_000.0
    return "${mb.toLong()} MB"
}

internal fun nowInstant(): Instant = Clock.System.now()

/**
 * The picture tier, without the codec.
 *
 * The server's type label spells out what an SD channel is encoded with ("SD MPEG-2"),
 * which is a tuner fact. Radio and Dead stay as they are — those say something a viewer
 * acts on.
 */
internal fun LiveChannel.tierLabel(): String? =
    typeLabel?.let { if (it.startsWith("SD")) "SD" else it }

/**
 * How far ahead the stored guide reaches: the HDHomeRun guide service is paged until it
 * stops advancing, which is its own ~48 h horizon. Picking a time past it is an empty guide.
 */
internal const val GuideFutureHours = 48

/** The feed answers a window with programmes overlapping it, including ones already over. */
internal fun List<LiveGuideEntry>.stillRunning(now: Instant): List<LiveGuideEntry> = filter {
    val end = parseLiveInstant(it.endTime) ?: return@filter true
    end > now
}

internal val DayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
internal val MonthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Sun-first, matching the server's Postgres DOW numbering. */
internal val WeekdayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

internal fun Int.pad2(): String = if (this < 10) "0$this" else toString()

@Composable
internal fun TunerMeter(inUse: Int, total: Int, accent: Color, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    if (total <= 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, colors.line, RoundedCornerShape(9.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .size(width = 9.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < inUse) accent else colors.line),
                )
            }
        }
        Text(
            "$inUse/$total",
            style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMd,
        )
    }
}

@Composable
internal fun LiveBadge(text: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = KobappTheme.type.labelSm, color = KobappTheme.colors.bg)
    }
}

@Composable
internal fun ChannelLogo(channel: LiveChannel, accent: Color, size: Dp, numbered: Boolean = false) {
    val number = channel.lcn?.takeIf { numbered && it.isNotBlank() }
    Box(Modifier.size(size)) {
        val initials = channel.name.take(2).uppercase()
        LogoTile(channel, accent, placeholder = if (number != null) initials else channel.lcn ?: initials)
        if (number != null) ChannelNumberChip(number, Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
    }
}

@Composable
private fun LogoTile(channel: LiveChannel, accent: Color, placeholder: String) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(lerp(colors.elev2, accent, 0.14f))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val logo = channel.logoUrl
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(4.dp),
            )
        } else {
            Text(
                placeholder,
                style = KobappTheme.type.caption.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChannelNumberChip(number: String, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Text(
        number,
        style = KobappTheme.type.labelSm.copy(fontWeight = FontWeight.Bold),
        color = colors.textMd,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.elev3)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
    )
}

/** A fixed tile for a programme still, so rows keep one baseline whether or not the feed had art. */
@Composable
internal fun LiveThumb(url: String?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Box(
        modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(7.dp))
            .background(colors.elev2),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Tv,
                contentDescription = null,
                tint = colors.textXlo,
                modifier = Modifier.size(height * 0.42f),
            )
        }
    }
}

/**
 * The guide feed ships a still for nearly every programme, so a card's backdrop is
 * artwork with a scrim over it and the flat tint is only the fallback.
 */
@Composable
internal fun GuideArtwork(
    entry: LiveGuideEntry?,
    tint: Color,
    modifier: Modifier = Modifier,
    scrim: Float = 0.78f,
) {
    val colors = KobappTheme.colors
    Box(modifier.background(tint)) {
        val art = entry?.imageUrl ?: entry?.posterUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(
                            colors.bg.copy(alpha = scrim * 0.72f),
                            colors.bg.copy(alpha = scrim),
                        ),
                    ),
                ),
            )
        }
    }
}

/**
 * Page chrome, never an entry target — [entryEligible] is false on every chip so a
 * screen hands focus to its content instead of its filter strip.
 */
@Composable
internal fun LiveFacetChips(
    groupKey: String,
    facets: List<LiveFacet>,
    selected: String?,
    accent: Color,
    leading: List<Pair<String, String?>> = emptyList(),
    prefix: (@Composable () -> Unit)? = null,
    onSelect: (String?) -> Unit,
) {
    if (facets.isEmpty() && leading.isEmpty() && prefix == null) return
    val bleed = rowEdgeBleed()
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = bleed),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.bleedHorizontally(bleed).focusKeyGroup(groupKey),
    ) {
        if (prefix != null) item(key = "prefix") { prefix() }
        items(leading.size) { i ->
            val (label, value) = leading[i]
            KobChip(
                text = label,
                style = if (selected == value) KobChipStyle.AccentTint else KobChipStyle.Default,
                accent = accent,
                onClick = { onSelect(value) },
                modifier = Modifier.focusKey("lead.$i", entryEligible = false),
            )
        }
        items(facets.size) { i ->
            val facet = facets[i]
            val active = selected == facet.value
            KobChip(
                text = if (facet.count > 0) "${facet.label} ${facet.count}" else facet.label,
                style = if (active) KobChipStyle.AccentTint else KobChipStyle.Default,
                accent = accent,
                onClick = { onSelect(if (active) null else facet.value) },
                modifier = Modifier.focusKey("f.${facet.value}", entryEligible = false),
            )
        }
    }
}

/**
 * When a programme airs, in one narrow lane.
 *
 * A single `13:00 – 14:00` line wraps at any width a phone can spare for it and then
 * takes two rows plus a date underneath, leaving the title itself almost nothing. Split
 * across lines it fits in [GuideWhenWidth], and the date only appears when the airing is
 * not today — which is most of what the line was spending its space on.
 */
@Composable
internal fun GuideWhen(entry: LiveGuideEntry, now: Instant, accent: Color, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    val start = parseLiveInstant(entry.startTime)
    val end = parseLiveInstant(entry.endTime)
    Column(modifier.width(GuideWhenWidth)) {
        Text(
            start?.localHhMm() ?: "",
            style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = if (entry.isAiringAt(now)) accent else colors.textHi,
            maxLines = 1,
        )
        if (end != null) {
            Text(
                "–${end.localHhMm()}",
                style = KobappTheme.type.labelSm,
                color = colors.textLo,
                maxLines = 1,
            )
        }
        if (start != null && !start.isLocalToday()) {
            Text(
                start.localDayLabel(),
                style = KobappTheme.type.labelSm,
                color = colors.textXlo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal val GuideWhenWidth = 56.dp

@Composable
internal fun RecordingDot(colors: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(999.dp)).background(colors))
}

@androidx.compose.runtime.Immutable
data class LiveGuideSelection(
    val channel: dev.kobbler.app.data.LiveChannel,
    val entry: dev.kobbler.app.data.LiveGuideEntry,
)
