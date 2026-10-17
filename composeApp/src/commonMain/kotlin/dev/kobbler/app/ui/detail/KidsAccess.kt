package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ChoiceRow
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class KidsChoice(val override: Boolean?, val label: String, val key: String)

private val KidsChoices = listOf(
    KidsChoice(null, "By rating", "auto"),
    KidsChoice(true, "Always show", "allow"),
    KidsChoice(false, "Never show", "block"),
)

/**
 * Whether Kids profiles see this title: a quiet button at the foot of the page, since it is
 * rarely touched and should not sit in the D-pad's way between the actions and the episodes.
 * The server owns the rating rule, so after a change it is asked what the title now resolves
 * to rather than guessed.
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
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    KobButton(
        text = if (shown) "Kids: shown" else "Kids: hidden",
        onClick = { picking = true },
        style = KobButtonStyle.Ghost,
        accent = accent,
        modifier = modifier.focusKey("kids"),
        leading = { Icon(Icons.Outlined.ChildCare, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )

    if (!picking) return
    AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("Kids profiles", color = colors.textHi, style = KobappTheme.type.h3) },
        text = {
            Column {
                KidsChoices.forEach { choice ->
                    ChoiceRow(
                        label = choice.label,
                        detail = when (choice.override) {
                            null -> rating?.let { "Rated $it" } ?: "Unrated, so hidden"
                            else -> null
                        },
                        selected = choice.override == current,
                        accent = accent,
                        focusKeyName = "kids.${choice.key}",
                        preferredEntry = choice.override == current,
                        onClick = {
                            picking = false
                            if (choice.override == current || saving) return@ChoiceRow
                            saving = true
                            scope.launch {
                                try {
                                    setOverride(choice.override)
                                    current = choice.override
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
                }
            }
        },
        confirmButton = {
            KobButton(onClick = { picking = false }, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("kids.cancel")) {
                Text("Cancel", color = colors.textHi, style = KobappTheme.type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
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
