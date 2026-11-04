package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.StreamTarget
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.CardAction
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.WarmStream
import dev.kobbler.app.ui.player.isInstant
import dev.kobbler.app.ui.player.rememberedAudioTitle
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DiscoverDetailScreen(
    detail: DiscoverDetail,
    api: KobblerApi,
    formFactor: FormFactor,
    player: Player?,
    gps: GlobalPlayerState,
    onBack: () -> Unit,
    onViewInLibrary: (mediaType: String, libraryId: String) -> Unit,
    modifier: Modifier = Modifier,
    refusals: List<CardAction> = emptyList(),
) {
    val colors = KobappTheme.colors
    val accent = when (detail.mediaType) {
        "tv" -> colors.accTv
        "movie" -> colors.accMovies
        "book" -> colors.accBooks
        else -> colors.accWanted
    }
    var currentDetail by remember(detail) { mutableStateOf(detail) }
    var wantLoading by remember { mutableStateOf(false) }
    var showWantPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Route on libraryId, not isInLibrary: a wanted title with nothing downloaded has a
    // library row but no files, and the nav layer already sends it there. Keyed on the
    // current one rather than the fetched one so a want lands on the same page — the
    // button that did it is replaced by a status pill nothing can focus, which on a
    // remote leaves the page dead.
    LaunchedEffect(currentDetail.libraryId) {
        currentDetail.libraryId?.let { onViewInLibrary(currentDetail.mediaType, it) }
    }
    if (detail.libraryId != null) return

    val canStreamMedia = api.canStream && supportsVideoPlayback &&
        currentDetail.mediaType in listOf("movie", "tv")
    val streams = rememberDiscoverStreams(currentDetail, api, gps, player, onViewInLibrary)

    LaunchedEffect(currentDetail.providerId) {
        if (canStreamMedia && currentDetail.mediaType == "movie") streams.prefetch(null)
    }
    if (currentDetail.mediaType == "movie") {
        WarmStream(gps, api, StreamTarget.discover(currentDetail, null, currentDetail.rememberedAudioTitle(null)).takeIf { canStreamMedia && player != null })
    }

    // A show is asked for the same way as one already in the library: how much of it to
    // want is the question, and "monitor" — which collects nothing that has already aired
    // — was the only answer this page could give.
    val onWant: (String) -> Unit = { autoWantMode ->
        wantLoading = true
        showWantPicker = false
        scope.launch {
            try {
                val newId = when (currentDetail.mediaType) {
                    "movie" -> api.wantMovie(currentDetail)
                    "tv" -> api.wantTvShow(currentDetail, autoWantMode)
                    "book" -> api.wantBook(currentDetail)
                    else -> null
                }
                if (newId != null) {
                    currentDetail = currentDetail.copy(libraryId = newId, wantStatus = "wanted")
                }
            } catch (_: Exception) {
            } finally {
                wantLoading = false
            }
        }
    }

    val onWantSeason: (Int) -> Unit = { season ->
        wantLoading = true
        scope.launch {
            try {
                val showId = api.wantDiscoverSeason(currentDetail, season)
                currentDetail = currentDetail.copy(libraryId = showId, wantStatus = "wanted")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                wantLoading = false
            }
        }
    }

    // A show is a question — how much of it — and everything else is a single answer.
    val onWantPressed: () -> Unit = {
        if (currentDetail.mediaType == "tv") showWantPicker = true else onWant("new_only")
    }

    val actions: @Composable (Boolean, Modifier, FocusRequester?, DiscoverStreamAction?) -> Unit = { wide, anchor, exitDown, stream ->
        DiscoverActions(
            detail = currentDetail,
            accent = accent,
            stream = stream,
            wantLoading = wantLoading,
            showWant = api.canWant,
            onWant = onWantPressed,
            wide = wide,
            exitDown = exitDown,
            modifier = anchor,
            refusals = refusals,
        )
    }

    Box(modifier.fillMaxSize()) {
        when (currentDetail.mediaType) {
            "tv" -> DiscoverShowPage(
                detail = currentDetail,
                api = api,
                formFactor = formFactor,
                accent = accent,
                onBack = onBack,
                onStreamEpisode = if (canStreamMedia) { ref -> streams.play(ref) } else null,
                onStreamEpisodePick = if (canStreamMedia) { ref -> streams.pick(ref) } else null,
                onWantSeason = onWantSeason.takeIf { api.canWant },
                actions = { wide, anchor, exitDown, target ->
                    WarmStream(gps, api, target?.takeIf { canStreamMedia && player != null }?.let { StreamTarget.discover(currentDetail, it, currentDetail.rememberedAudioTitle(it)) })
                    val stream = target?.takeIf { canStreamMedia }?.let {
                        DiscoverStreamAction("Stream", episodeTag(it.season, it.episode), { streams.play(it) }, { streams.pick(it) })
                    }
                    actions(wide, anchor, exitDown, stream)
                },
                modifier = Modifier.focusInert(streams.showOptions),
            )
            "movie" -> MovieDetailLayout(
                movie = currentDetail.asMovie(),
                formFactor = formFactor,
                accent = accent,
                onBack = onBack,
                onPersonClick = null,
                actions = { wide, anchor ->
                    val stream = if (canStreamMedia) DiscoverStreamAction("Play", null, { streams.play(null) }, { streams.pick(null) }) else null
                    actions(wide, anchor, null, stream)
                },
                topBarStatus = { DiscoverStatus(currentDetail.libraryStatus) },
                aside = {
                    val streaming = streamingAvailability(
                        canStream = canStreamMedia,
                        searching = streams.loading,
                        found = streams.result?.options?.map { it.isInstant },
                    )
                    ReleaseAvailabilityStrip(currentDetail.availability(streaming), accent)
                },
                modifier = Modifier.focusInert(streams.showOptions),
            )
            else -> BookDetailLayout(
                book = currentDetail.asBook(),
                formFactor = formFactor,
                accent = accent,
                onBack = onBack,
                crumbRoot = "Discover",
                actions = { anchor -> actions(false, anchor, null, null) },
                topBarStatus = { DiscoverStatus(currentDetail.libraryStatus) },
                aside = { ReleaseAvailabilityStrip(currentDetail.availability(StreamingAvailability.Hidden), accent) },
            )
        }

        if (showWantPicker) {
            WantShowDialog(
                currentMode = "none",
                monitored = false,
                accent = accent,
                onDismiss = { showWantPicker = false },
                onPickMode = onWant,
                // A show that is not here yet cannot have gaps, and its episodes do not
                // exist at the moment of asking — the want that survives that wait is a
                // mode on the show, which enrichment applies when they land.
                onWantMissingOnce = null,
            )
        }

        if (streams.showOptions) {
            StreamOptionsOverlay(
                options = streams.result?.options ?: emptyList(),
                errors = streams.result?.errors ?: emptyList(),
                loading = streams.loading,
                onSelect = streams::playPicked,
                onDismiss = { streams.showOptions = false },
            )
        }
    }
}
