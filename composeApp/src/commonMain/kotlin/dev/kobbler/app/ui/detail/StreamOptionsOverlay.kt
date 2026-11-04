package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.KobBackHeader
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.app.ui.player.isInstant
import dev.kobbler.app.ui.safeBottomPadding
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.delay

@Composable
fun StreamOptionsOverlay(
    options: List<StreamOption>,
    errors: List<String>,
    loading: Boolean,
    onSelect: (StreamOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    val formFactor = LocalFormFactor.current
    val gutter = gutterFor(formFactor)
    val accent = colors.accMovies
    val firstStreamFocus = remember { FocusRequester() }
    InterceptBack(enabled = true, onBack = onDismiss)

    // A touch screen draws focus as a ring, which on a phone reads as a selection.
    LaunchedEffect(options, loading) {
        if (!loading && options.isNotEmpty() && formFactor != FormFactor.Phone) {
            delay(100)
            runCatching { firstStreamFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .focusKeyGroup("streamOptions")
            .fillMaxSize()
            .background(colors.bg)
            .safeTopPadding(),
    ) {
        KobBackHeader(onDismiss, formFactor, gutter) {
            BrowseHeader(
                title = "Stream options",
                accent = accent,
                countLine = countLine(options, loading, withProvider = formFactor != FormFactor.Phone),
                compact = formFactor == FormFactor.Phone,
            )
        }

        errors.forEach { err ->
            Text(
                err,
                style = KobappTheme.type.bodySm,
                color = colors.err,
                modifier = Modifier.padding(horizontal = gutter, vertical = 2.dp),
            )
        }

        when {
            loading -> LoadingState()
            options.isEmpty() -> EmptyState(icon = Icons.Outlined.CloudOff, title = "No streams found")
            else -> StreamList(options, accent, gutter, firstStreamFocus, onSelect)
        }
    }
}

private fun countLine(options: List<StreamOption>, loading: Boolean, withProvider: Boolean): String? {
    if (loading || options.isEmpty()) return null
    return listOfNotNull(
        if (options.size == 1) "1 stream" else "${options.size} streams",
        "${options.count { it.isInstant }} instant",
        options.first().debridProvider.takeIf { withProvider && it.isNotBlank() }?.let(::debridServiceName),
    ).joinToString(" · ")
}

@Composable
private fun LoadingState() {
    val colors = KobappTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.textHi, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("Searching for streams…", style = KobappTheme.type.bodySm, color = colors.textMd)
        }
    }
}

@Composable
private fun StreamList(
    options: List<StreamOption>,
    accent: Color,
    gutter: Dp,
    firstStreamFocus: FocusRequester,
    onSelect: (StreamOption) -> Unit,
) {
    val first = options.first().id
    LazyColumn(
        contentPadding = PaddingValues(start = gutter, end = gutter, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize().safeBottomPadding(),
    ) {
        streamSections(options).forEach { section ->
            section.title?.let { title ->
                item(key = "section.$title") { SectionLabel(title, section.options.size) }
            }
            items(section.options, key = { it.id }) { option ->
                StreamOptionRow(
                    option = option,
                    accent = accent,
                    onSelect = { onSelect(option) },
                    modifier = Modifier
                        .focusKey("option.${option.id}")
                        .then(if (option.id == first) Modifier.focusRequester(firstStreamFocus) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    val colors = KobappTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    ) {
        Text(title, style = KobappTheme.type.label, color = colors.textMd)
        Text("$count", style = KobappTheme.type.label, color = colors.textXlo)
    }
}
