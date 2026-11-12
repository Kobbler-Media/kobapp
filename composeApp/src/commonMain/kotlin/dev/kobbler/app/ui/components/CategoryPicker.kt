package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kobbler.app.data.GenreCount
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.navigation.backInput
import dev.kobbler.kobfocus.focusKey

private const val AllCategories = "All categories"

/** Shows the category in force, so the page says what it is narrowed to without opening anything. */
// A phone's title, count and three segments came to a few dp more than its width, which
// sent the selector to a line of its own; the shorter label keeps them on one.
fun allLabel(compact: Boolean): String = if (compact) "All" else "All library"

/**
 * A browse screen's scopes with the category as their last segment: "Categories" until one
 * is picked, then its name. Choosing a scope clears the category, and the category segment
 * always opens the picker, so one control says what the grid is showing.
 */
@Composable
fun BrowseScopeFilter(
    scopes: List<String>,
    selectedScope: Int,
    category: String?,
    accent: Color,
    onScope: (Int) -> Unit,
    onCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedFilter(
        options = scopes + (category ?: "Categories"),
        selectedIndex = if (category != null) scopes.size else selectedScope,
        accent = accent,
        modifier = modifier,
        onSelected = { if (it < scopes.size) onScope(it) else onCategories() },
    )
}

/**
 * Every category as a wide tile over art from one of its own titles. A page of its own
 * window rather than a list in a dialog: the window keeps the remote's keys and BACK to
 * itself, so focus cannot wander into the browse grid underneath.
 */
@Composable
fun CategoryPage(
    genres: List<GenreCount>,
    selected: String?,
    accent: Color,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val colors = KobappTheme.colors
    val compact = LocalFormFactor.current == FormFactor.Phone
    val allArt = remember(genres) { genres.mapNotNull { it.backdropUrl }.randomOrNull() }
    val entry = remember { FocusRequester() }
    val entryName = selected?.takeIf { s -> genres.any { it.name == s } }
    LaunchedEffect(genres.isEmpty()) {
        repeat(10) {
            if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focusManager = LocalFocusManager.current
        // A window of its own is outside the scaffold's key handling, which is what turns
        // arrows into focus moves on desktop and routes BACK; Android TV does both natively.
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .backInput { onDismiss(); true }
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        else -> null
                    }
                    direction != null && event.type == KeyEventType.KeyDown && focusManager.moveFocus(direction)
                },
        ) {
            Text(
                "Categories",
                style = KobappTheme.type.h1,
                color = colors.textHi,
                modifier = Modifier.padding(start = if (compact) 16.dp else 48.dp, top = if (compact) 20.dp else 36.dp, bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (compact) 160.dp else 260.dp),
                contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 48.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "all") {
                    CategoryTile(
                        name = AllCategories,
                        detail = null,
                        art = allArt,
                        selected = selected == null,
                        accent = accent,
                        modifier = Modifier.focusKey("category.all").then(
                            if (entryName == null) Modifier.focusRequester(entry) else Modifier,
                        ),
                        onClick = { onPick(null) },
                    )
                }
                items(genres, key = { it.name }) { genre ->
                    CategoryTile(
                        name = genre.name,
                        detail = if (genre.count == 1) "1 title" else "${genre.count} titles",
                        art = genre.backdropUrl,
                        selected = genre.name == selected,
                        accent = accent,
                        modifier = Modifier.focusKey("category.${genre.name}").then(
                            if (genre.name == entryName) Modifier.focusRequester(entry) else Modifier,
                        ),
                        onClick = { onPick(genre.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    name: String,
    detail: String?,
    art: String?,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val focus = rememberTvFocusState()
    val compact = LocalFormFactor.current == FormFactor.Phone
    val placeholder = remember(accent, colors.elev2) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), colors.elev2))
    }
    var artShown by remember(art) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .aspectRatio(if (compact) 16f / 10f else 16f / 7f)
            .tvFocusGlow(focus, cornerRadiusDp = 12f)
            .clip(shape)
            .drawBehind { if (!artShown) drawRect(placeholder) }
            .then(if (selected) Modifier.border(2.dp, accent, shape) else Modifier)
            .tvClickable(focus, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            FadeInImage(
                model = art,
                contentDescription = null,
                onShown = { artShown = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val lit = focus.isFocused && focusDecorated()
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Color.Black.copy(alpha = if (lit) 0.35f else 0.6f))
        })
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                name,
                style = (if (compact) KobappTheme.type.h3 else KobappTheme.type.h2).copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(detail, style = KobappTheme.type.bodySm, color = Color.White.copy(alpha = 0.75f))
            }
        }
        if (selected) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Selected",
                tint = accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(22.dp),
            )
        }
    }
}
