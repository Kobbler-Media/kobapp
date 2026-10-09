package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.kobbler.app.ui.imePaddingUnlessTv

private const val SEARCH_DEBOUNCE_MS = 200L

@Composable
internal fun ReaderSearchPanel(
    index: EpubSearchIndex?,
    onResultSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(query, index) {
        if (query.length < EpubSearchIndex.MIN_QUERY_LENGTH) {
            results = emptyList()
            busy = false
            return@LaunchedEffect
        }
        busy = true
        if (index == null) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        results = withContext(Dispatchers.Default) { index.search(query) }
        busy = false
    }

    Box(
        modifier
            .fillMaxSize()
            .background(theme.backgroundColor.copy(alpha = 0.95f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .focusKeyGroup("search")
                .fillMaxWidth(0.9f)
                .fillMaxHeight()
                .align(Alignment.Center)
                .background(theme.backgroundColor)
                .clickable(enabled = false) {}
                .imePaddingUnlessTv()
                .padding(16.dp)
        ) {
            val keyboardController = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search in book...", color = theme.textColor.copy(alpha = 0.4f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onAny = { keyboardController?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = theme.textColor,
                    unfocusedTextColor = theme.textColor,
                    focusedBorderColor = theme.linkColor,
                    unfocusedBorderColor = theme.textColor.copy(alpha = 0.3f),
                    cursorColor = theme.linkColor,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.focusKey("clear").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                        ) {
                            Icon(Icons.Outlined.Close, "Clear", tint = theme.textColor.copy(alpha = 0.5f))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (query.length >= EpubSearchIndex.MIN_QUERY_LENGTH) {
                Text(
                    if (busy) "Searching..." else "${results.size} result${if (results.size != 1) "s" else ""}",
                    style = KobappTheme.type.caption.copy(color = theme.textColor.copy(alpha = 0.5f)),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.focusKeyGroup("results")) {
                items(results, key = { it.chapterIndex }) { hit ->
                    Column(
                        Modifier
                            .focusKey("result.${hit.chapterIndex}")
                            .fillMaxWidth()
                            .tvAutoGlow(cornerRadiusDp = 4f, scale = 1.01f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onResultSelect(hit.chapterIndex) }
                            .padding(vertical = 8.dp, horizontal = 8.dp)
                    ) {
                        Text(
                            hit.chapterTitle,
                            style = KobappTheme.type.label.copy(color = theme.linkColor),
                        )
                        Text(
                            hit.snippet,
                            style = KobappTheme.type.caption.copy(color = theme.textColor.copy(alpha = 0.7f)),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider(color = theme.textColor.copy(alpha = 0.1f))
                }
            }
        }
    }
}
