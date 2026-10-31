package dev.kobbler.app.ui.detail.tvshow

import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.ui.offline.EpisodeDownloadButton
import dev.kobbler.app.ui.offline.OfflineBadge
import dev.kobbler.app.ui.offline.rememberOfflineItem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.localToday
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.detail.isWantedNow
import dev.kobbler.app.ui.detail.isWatchedNow
import dev.kobbler.app.ui.components.StripedArt
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import kotlinx.coroutines.launch

fun LazyListScope.episodeList(
    season: Season?,
    seasonIdx: Int,
    accent: Color,
    artTone: Color,
    currentEpisodeId: String?,
    api: KobblerApi?,
    watchedOverrides: SnapshotStateMap<String, Boolean>,
    onPlayEpisode: (Int, Int, Boolean) -> Unit,
    onStreamEpisode: ((Int, Int) -> Unit)?,
    onStreamEpisodePick: ((Int, Int) -> Unit)?,
    wantOverrides: SnapshotStateMap<String, Boolean>,
    seasonEntry: FocusRequester? = null,
    isUpcoming: ((Episode) -> Boolean)? = null,
    onDownload: ((Episode) -> Unit)? = null,
) {
    if (season == null) return
    // Above the first row is the tab strip, and a geometric search up out of the list
    // lands on whichever tab sits nearest the row's centre — the last season, never the
    // one being listed. Redirected on leaving the first row rather than on its body, so
    // it holds from the row's action icons as well.
    val exitUp = if (seasonEntry != null) {
        Modifier.focusProperties {
            onExit = { if (requestedFocusDirection == FocusDirection.Up) seasonEntry.requestFocus() }
        }.focusGroup()
    } else {
        Modifier
    }
    itemsIndexed(
        season.episodes,
        key = { idx, ep -> ep.id ?: "${season.number}:$idx" },
        contentType = { _, _ -> "episode" },
    ) { idx, ep ->
        val canPlayLocal = ep.filePath != null && supportsVideoPlayback
        val canStream = onStreamEpisode != null && supportsVideoPlayback
        EpisodeRow(
            // Toggling watched clears the saved position, which `ep` cannot know.
            episode = if (ep.id != null && ep.id in watchedOverrides) ep.copy(watchProgress = null) else ep,
            accent = accent,
            artTone = artTone,
            isCurrent = currentEpisodeId != null && ep.id == currentEpisodeId,
            watched = ep.isWatchedNow(watchedOverrides),
            canPlayLocal = canPlayLocal,
            canStream = canStream,
            airsSoon = isUpcoming?.invoke(ep) ?: (!canPlayLocal && ep.filePath == null && !canStream),
            onClick = {
                if (ep.filePath != null) onPlayEpisode(seasonIdx, idx, false)
                else if (onStreamEpisode != null) onStreamEpisode(seasonIdx, idx)
            },
            onStreamPick = if (onStreamEpisodePick != null && supportsVideoPlayback) {
                { onStreamEpisodePick(seasonIdx, idx) }
            } else null,
            wanted = ep.isWantedNow(wantOverrides),
            offline = rememberOfflineItem(OfflineKind.Episode, ep.id),
            onDownload = if (onDownload != null && canPlayLocal && ep.id != null) { { onDownload(ep) } } else null,
            onWant = if (api != null && ep.id != null && ep.canBeWanted(api.canWant) && !ep.isWantedNow(wantOverrides)) {
                {
                    // Optimistic like the watched toggle: the round-trip enqueues a
                    // search, so the row is the only thing that can answer the tap.
                    wantOverrides[ep.id] = true
                    runCatching { api.wantEpisode(ep.id) }
                        .onFailure { wantOverrides.remove(ep.id) }
                }
            } else null,
            onToggleWatched = if (api != null && ep.id != null) {
                { newState ->
                    // Optimistic: the row is the only feedback a tap gets, and the
                    // round-trip is long enough to read as a dead control.
                    watchedOverrides[ep.id] = newState
                    runCatching { api.setEpisodeWatched(ep.id, newState) }
                        .onFailure { watchedOverrides[ep.id] = !newState }
                }
            } else null,
            // Scoped per season: without it every season registers `episode.1`, and a restore
            // after recomposition hands focus to whichever season claimed the key last.
            modifier = Modifier.focusKeyGroup("season.${season.number}").then(if (idx == 0) exitUp else Modifier),
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    accent: Color,
    artTone: Color,
    isCurrent: Boolean,
    watched: Boolean,
    canPlayLocal: Boolean,
    canStream: Boolean,
    airsSoon: Boolean,
    wanted: Boolean,
    offline: OfflineItem?,
    onDownload: (() -> Unit)?,
    onClick: () -> Unit,
    onStreamPick: (() -> Unit)?,
    onWant: (suspend () -> Unit)?,
    onToggleWatched: (suspend (Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val hasPlayback = canPlayLocal || canStream
    val scope = rememberCoroutineScope()
    val rowBg = if (isCurrent) accent.copy(alpha = 0.08f) else Color.Transparent
    val focusState = rememberTvFocusState()
    val compact = LocalFormFactor.current == FormFactor.Phone

    val container = modifier
        .fillMaxWidth()
        .focusKeyGroup("episode.${episode.number}")
        .clip(RoundedCornerShape(8.dp))
        .background(rowBg)

    // The body is its own focus target rather than the whole row. A focusable ancestor
    // swallows a D-pad press aimed at its own descendants, so while the action icons
    // were `clickable` — and so nominally focusable — a remote could never land on
    // them. Three sibling targets under a non-focusable group is what makes RIGHT step
    // body -> stream options -> watched. See EpisodeRowFocusTest.
    val body = Modifier
        .then(if (hasPlayback) Modifier.focusKey("play") else Modifier)
        .tvFocusGlow(focusState, cornerRadiusDp = 8f, scale = 1.01f)
        .clip(RoundedCornerShape(8.dp))
        .let { if (hasPlayback) it.pointerHoverIcon(PointerIcon.Hand) else it }
        .then(if (hasPlayback) Modifier.tvClickable(focusState, onClick) else Modifier)

    val actions: @Composable () -> Unit = {
        // Asking for the file comes before choosing a stream to watch instead of it, and
        // both come before the watched flag — acquire, then play, then record.
        if (onWant != null) {
            IconBtn(
                icon = Icons.Outlined.BookmarkAdd,
                description = "Want episode",
                focusKey = "want",
                tint = colors.textLo,
                onClick = { scope.launch { runCatching { onWant() } } },
            )
        } else if (wanted) {
            // A marker, not a control — pressing it would do nothing, so it must not
            // take a D-pad stop between the row and its real actions.
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Bookmark,
                    contentDescription = "Wanted",
                    tint = colors.warn,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (onDownload != null) EpisodeDownloadButton(offline, accent, onDownload)

        if (onStreamPick != null) {
            IconBtn(
                icon = Icons.Outlined.Tune,
                description = "Stream options",
                focusKey = "streamopts",
                tint = colors.textLo,
                onClick = onStreamPick,
            )
        }

        if (onToggleWatched != null) {
            IconBtn(
                icon = if (watched) Icons.Outlined.Check else Icons.Outlined.Visibility,
                description = if (watched) "Mark unwatched" else "Mark watched",
                focusKey = "watched",
                tint = if (watched) colors.ok else colors.textLo,
                onClick = { scope.launch { runCatching { onToggleWatched(!watched) } } },
            )
        }
    }

    val synopsis = episode.synopsis?.takeIf { !airsSoon }
    val today = remember { localToday() }
    val airLabel = episode.airDate?.let { airDateLabel(it, today) }

    // A phone cannot afford the number column, a 150dp still, a duration and two icons
    // beside the text: the fixed widths came to more than the row had, and the weighted
    // title column measured to nothing. Number and duration join up under the title, and
    // the synopsis drops to its own full-width line.
    if (compact) {
        // Touch, not a D-pad: the icons are hit directly, so the tile stays one
        // clickable surface and the synopsis under it stays part of that surface.
        Column(container.then(body).padding(horizontal = 10.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Thumbnail(episode = episode, accent = accent, artTone = artTone, watched = watched, airsSoon = airsSoon, offline = offline?.state == OfflineState.Complete, width = 104.dp)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        episode.title,
                        style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = if (airsSoon) colors.textLo else colors.textHi,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            append("E${episode.number.pad2()}")
                            if (airLabel != null) append(" · $airLabel")
                            if (episode.duration != null && !airsSoon) append(" · ${episode.duration}")
                        },
                        style = KobappTheme.type.bodySm,
                        color = if (isCurrent) accent else colors.textXlo,
                    )
                }

                actions()
            }

            if (synopsis != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    synopsis,
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    Row(
        modifier = container.padding(horizontal = 10.dp, vertical = 4.dp).focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            // Inside the glow, not outside it: the ring is drawn on `body`, so without
            // this the title and a wrapped synopsis run right up against the stroke.
            modifier = body.padding(horizontal = 12.dp, vertical = 8.dp).weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                episode.number.pad2(),
                style = KobappTheme.type.bodySm,
                color = if (isCurrent) accent else colors.textXlo,
                modifier = Modifier.width(36.dp),
            )

            Thumbnail(episode = episode, accent = accent, artTone = artTone, watched = watched, airsSoon = airsSoon, offline = offline?.state == OfflineState.Complete)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (airsSoon) colors.textLo else colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (synopsis != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        synopsis,
                        style = KobappTheme.type.caption,
                        color = colors.textLo,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val duration = episode.duration?.takeIf { !airsSoon }
            if (airLabel != null || duration != null) {
                Column(horizontalAlignment = Alignment.End) {
                    if (airLabel != null) {
                        Text(airLabel, style = KobappTheme.type.bodySm, color = colors.textLo)
                    }
                    if (duration != null) {
                        Text(duration, style = KobappTheme.type.bodySm, color = colors.textXlo)
                    }
                }
            }
        }

        actions()
    }
}

@Composable
private fun Thumbnail(
    episode: Episode,
    accent: Color,
    artTone: Color,
    watched: Boolean,
    airsSoon: Boolean,
    offline: Boolean,
    width: Dp = 150.dp,
) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .clip(KobappTheme.shapes.small)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.small),
    ) {
        StripedArt(episode.thumbnailUrl, accent, Modifier.fillMaxSize(), tone = artTone)
        if (offline) OfflineBadge(Modifier.align(Alignment.TopStart).padding(4.dp), label = null)
        val progress = episode.watchProgress
        if (progress != null && progress > 0f && progress < 1f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(accent),
                )
            }
        }
        if (airsSoon) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                val airs = episode.airDate?.takeIf { it > localToday() }?.let(::monthDay)
                    ?: episode.duration
                    ?: "soon"
                Text(
                    "AIRS · ${airs.uppercase()}",
                    style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                    color = colors.textLo,
                )
            }
        }
        if (watched && !airsSoon) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colors.ok),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, null, tint = colors.bg, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
internal fun IconBtn(
    icon: ImageVector,
    description: String,
    focusKey: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .focusKey(focusKey)
            .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

private fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()
