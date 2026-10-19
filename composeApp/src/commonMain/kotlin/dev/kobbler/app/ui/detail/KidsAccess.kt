package dev.kobbler.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ChoiceRow
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private data class KidsChoice(val override: Boolean?, val label: String, val key: String)

private val KidsChoices = listOf(
    KidsChoice(null, "By rating", "auto"),
    KidsChoice(true, "Always show", "allow"),
    KidsChoice(false, "Never show", "block"),
)

/**
 * Whether Kids profiles see this title. Rarely touched, so it lives in the page's overflow
 * menu rather than in the D-pad's way. Held outside that menu: menu content is disposed on
 * close, and the dialog it opens has to outlive it. The server owns the rating rule, so
 * after a change it is asked what the title now resolves to rather than guessed.
 */
@Stable
internal class KidsAccessState(
    override: Boolean?,
    allowed: Boolean,
    val rating: String?,
    private val toast: ToastState,
    private val scope: CoroutineScope,
    private val setOverride: suspend (Boolean?) -> Unit,
    private val resolvedAllowed: suspend () -> Boolean,
) {
    var current by mutableStateOf(override)
        private set
    var shown by mutableStateOf(allowed)
        private set
    var picking by mutableStateOf(false)
    private var saving = false

    val label: String get() = if (shown) "Kids: shown" else "Kids: hidden"

    fun pick(choice: Boolean?) {
        picking = false
        if (choice == current || saving) return
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
    }
}

@Composable
internal fun rememberMovieKidsAccess(movie: Movie, api: KobblerApi, toast: ToastState): KidsAccessState? {
    val scope = rememberCoroutineScope()
    if (!api.canManageKids) return null
    return remember(movie.id) {
        KidsAccessState(
            override = movie.kidsOverride,
            allowed = movie.kidsAllowed,
            rating = movie.contentRating,
            toast = toast,
            scope = scope,
            setOverride = { api.setMovieKidsOverride(movie.id, it) },
            resolvedAllowed = { api.getMovie(movie.id).kidsAllowed },
        )
    }
}

@Composable
internal fun rememberShowKidsAccess(show: TvShow, api: KobblerApi?, toast: ToastState): KidsAccessState? {
    val scope = rememberCoroutineScope()
    if (api == null || !api.canManageKids) return null
    return remember(show.id) {
        KidsAccessState(
            override = show.kidsOverride,
            allowed = show.kidsAllowed,
            rating = show.contentRating,
            toast = toast,
            scope = scope,
            setOverride = { api.setShowKidsOverride(show.id, it) },
            resolvedAllowed = { api.getShow(show.id).kidsAllowed },
        )
    }
}

@Composable
internal fun KidsAccessItem(state: KidsAccessState, accent: Color, close: () -> Unit) {
    OverflowItem(
        key = "kids",
        icon = Icons.Outlined.ChildCare,
        label = state.label,
        accent = accent,
        onClick = {
            close()
            state.picking = true
        },
    )
}

@Composable
internal fun KidsAccessDialog(state: KidsAccessState, accent: Color) {
    if (!state.picking) return
    val colors = KobappTheme.colors
    AlertDialog(
        onDismissRequest = { state.picking = false },
        title = { Text("Kids profiles", color = colors.textHi, style = KobappTheme.type.h3) },
        text = {
            Column {
                KidsChoices.forEach { choice ->
                    ChoiceRow(
                        label = choice.label,
                        detail = when (choice.override) {
                            null -> state.rating?.let { "Rated $it" } ?: "Unrated, so hidden"
                            else -> null
                        },
                        selected = choice.override == state.current,
                        accent = accent,
                        focusKeyName = "kids.${choice.key}",
                        preferredEntry = choice.override == state.current,
                        onClick = { state.pick(choice.override) },
                    )
                }
            }
        },
        confirmButton = {
            KobButton(onClick = { state.picking = false }, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("kids.cancel")) {
                Text("Cancel", color = colors.textHi, style = KobappTheme.type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}
