package dev.kobbler.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.FadeInImage
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.kobfocus.focusInert

internal const val START_BACKDROP_TAG = "start-backdrop"

private const val STARTING_NOTE = "Starting…"
private val LiveTypes = setOf("livetv", "iptv")

/** The title's artwork, so nothing before the first frame is a blank surface. */
@Composable
internal fun StartBackdrop(state: GlobalPlayerState) {
    val art = state.backdropUrl ?: state.posterUrl
    val showing = art != null && state.isStreamStart && state.mediaType !in LiveTypes &&
        (state.isPreparing || state.isStarting)
    AnimatedVisibility(showing, enter = fadeIn(tween(200)), exit = fadeOut(tween(400))) {
        Box(Modifier.fillMaxSize().testTag(START_BACKDROP_TAG)) {
            FadeInImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(KobappTheme.colors.bg.copy(alpha = 0.45f)))
        }
    }
}

/** The quiet line under an opening stream: what it is doing, and once it is slow, how the feed is. */
@Composable
internal fun StartingNote(state: GlobalPlayerState, modifier: Modifier = Modifier) {
    if (!state.isStreamStart || !state.isStarting || state.isPreparing || state.mediaType in LiveTypes) return
    Text(
        text = state.startProgress ?: STARTING_NOTE,
        style = KobappTheme.type.bodySm,
        color = KobappTheme.colors.textMd,
        modifier = modifier,
    )
}

/** A passing word over playback that does not interrupt it. */
@Composable
internal fun PlayerNote(state: GlobalPlayerState, modifier: Modifier = Modifier) {
    val note = state.playerNote ?: return
    if (state.isStarting || state.isPreparing) return
    Text(
        text = note,
        style = KobappTheme.type.bodySm,
        color = KobappTheme.colors.textMd,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(KobappTheme.colors.bg.copy(alpha = 0.7f))
            .padding(horizontal = KobappTheme.dimens.s4, vertical = KobappTheme.dimens.s2),
    )
}

/** The whole player while a stream is still being found and nothing has been loaded. */
@Composable
internal fun PendingStart(state: GlobalPlayerState, accent: Color) {
    Box(Modifier.fillMaxSize().background(KobappTheme.colors.bg)) {
        StartBackdrop(state)
        PreparingShell(state, accent, inert = false, onCancel = { state.onPreparingCancel?.invoke() ?: state.cancelPreparing() })
    }
}

@Composable
internal fun PreparingShell(
    state: GlobalPlayerState,
    accent: Color,
    inert: Boolean,
    onCancel: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val hasArt = state.backdropUrl != null || state.posterUrl != null
    Box(
        Modifier
            .fillMaxSize()
            .focusKeyGroup("prepare")
            .focusInert(inert)
            .background(colors.bg.copy(alpha = if (hasArt) 0.4f else 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = dimens.s5),
        ) {
            val progress = state.cachingProgress
            if (progress != null) CachingStatus(state, progress, accent) else FindingStatus(state, accent)
            Spacer(Modifier.height(dimens.s5))
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.s3)) {
                KobButton(
                    text = "Cancel",
                    onClick = onCancel,
                    style = KobButtonStyle.Ghost,
                    modifier = Modifier.focusKey("cancel"),
                )
                val choose = state.onChooseRelease
                if (progress != null && choose != null) {
                    KobButton(
                        text = "Choose another release",
                        onClick = choose,
                        style = KobButtonStyle.Ghost,
                        modifier = Modifier.focusKey("chooseRelease"),
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingStatus(state: GlobalPlayerState, accent: Color) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val dimens = KobappTheme.dimens
    CircularProgressIndicator(color = accent)
    Spacer(Modifier.height(dimens.s5))
    Text(
        text = state.title.takeIf { it.isNotBlank() } ?: "Connecting…",
        style = type.h3,
        color = colors.textHi,
    )
    state.subtitle?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(dimens.s1))
        Text(it, style = type.bodySm, color = colors.textMd)
    }
    state.preparingNote?.let {
        Spacer(Modifier.height(dimens.s2))
        Text(it, style = type.bodySm, color = colors.textMd)
    }
}

@Composable
private fun CachingStatus(state: GlobalPlayerState, progress: Float, accent: Color) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val dimens = KobappTheme.dimens
    Text("Preparing stream", style = type.h3, color = colors.textHi)
    (state.cachingTitle ?: state.title.takeIf { it.isNotBlank() })?.let {
        Spacer(Modifier.height(dimens.s1))
        Text(it, style = type.bodySm, color = colors.textMd)
    }
    Spacer(Modifier.height(dimens.s5))
    val bar = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
    // An indeterminate bar while nothing has moved: a determinate one stuck at 0% reads as frozen.
    if (progress > 0f) {
        LinearProgressIndicator(progress = { progress }, color = accent, trackColor = colors.elev2, modifier = bar)
    } else {
        LinearProgressIndicator(color = accent, trackColor = colors.elev2, modifier = bar)
    }
    Spacer(Modifier.height(dimens.s2))
    val note = state.cachingNote
    Text(
        text = listOfNotNull("${(progress * 100).toInt()}%".takeIf { progress > 0f }, note).joinToString(" · "),
        style = type.caption,
        color = colors.textLo,
    )
}
