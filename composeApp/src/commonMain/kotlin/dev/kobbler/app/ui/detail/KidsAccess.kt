package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val KidsChoices = listOf<Boolean?>(null, true, false)

/**
 * Whether Kids profiles see this title. Auto leaves it to the rating; the server owns that
 * rule, so after a change it is asked what the title now resolves to rather than guessed.
 */
@Composable
private fun KidsAccess(
    titleId: String,
    override: Boolean?,
    allowed: Boolean,
    rating: String?,
    accent: Color,
    toast: ToastState,
    setOverride: suspend (Boolean?) -> Unit,
    resolvedAllowed: suspend () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    var current by remember(titleId) { mutableStateOf(override) }
    var shown by remember(titleId) { mutableStateOf(allowed) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FlowRow(
        modifier = modifier.focusKeyGroup("kids"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("KIDS", color = colors.textLo, style = KobappTheme.type.label)
        SegmentedFilter(
            options = listOf(if (rating != null) "Auto · $rating" else "Auto", "Allow", "Block"),
            selectedIndex = KidsChoices.indexOf(current),
            accent = accent,
            onSelected = { index ->
                val choice = KidsChoices[index]
                if (choice == current || saving) return@SegmentedFilter
                saving = true
                scope.launch {
                    try {
                        setOverride(choice)
                        current = choice
                        shown = resolvedAllowed()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        toast.show("Couldn't change Kids access")
                    } finally {
                        saving = false
                    }
                }
            },
        )
        Text(
            if (shown) "Shown on Kids profiles" else "Hidden from Kids profiles",
            color = if (shown) accent else colors.textLo,
            style = KobappTheme.type.caption,
        )
    }
}

@Composable
internal fun MovieKidsAccess(movie: Movie, api: KobblerApi, accent: Color, toast: ToastState, modifier: Modifier = Modifier) {
    if (!api.canManageKids) return
    KidsAccess(
        titleId = movie.id,
        override = movie.kidsOverride,
        allowed = movie.kidsAllowed,
        rating = movie.contentRating,
        accent = accent,
        toast = toast,
        setOverride = { api.setMovieKidsOverride(movie.id, it) },
        resolvedAllowed = { api.getMovie(movie.id).kidsAllowed },
        modifier = modifier,
    )
}

@Composable
internal fun ShowKidsAccess(show: TvShow, api: KobblerApi, accent: Color, toast: ToastState, modifier: Modifier = Modifier) {
    if (!api.canManageKids) return
    KidsAccess(
        titleId = show.id,
        override = show.kidsOverride,
        allowed = show.kidsAllowed,
        rating = show.contentRating,
        accent = accent,
        toast = toast,
        setOverride = { api.setShowKidsOverride(show.id, it) },
        resolvedAllowed = { api.getShow(show.id).kidsAllowed },
        modifier = modifier,
    )
}
