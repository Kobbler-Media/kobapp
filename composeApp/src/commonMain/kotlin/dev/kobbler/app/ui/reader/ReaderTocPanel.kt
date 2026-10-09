package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Bookmark as BookmarkData
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

@Composable
internal fun ReaderTocPanel(
    entries: List<TocEntry>,
    currentChapter: Int,
    bookmarks: List<BookmarkData>,
    onChapterSelect: (Int) -> Unit,
    onBookmarkSelect: (BookmarkData) -> Unit,
    onBookmarkDelete: (BookmarkData) -> Unit,
    onDismiss: () -> Unit,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(theme.backgroundColor.copy(alpha = 0.95f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .focusKeyGroup("toc")
                .fillMaxWidth(0.85f)
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .background(theme.backgroundColor)
                .clickable(enabled = false) {}
                .padding(16.dp)
        ) {
            var tocTab by remember { mutableIntStateOf(0) }
            if (bookmarks.isNotEmpty()) {
                SecondaryTabRow(
                    selectedTabIndex = tocTab,
                    containerColor = Color.Transparent,
                    contentColor = theme.textColor,
                    indicator = { TabRowDefaults.SecondaryIndicator(color = theme.linkColor) },
                ) {
                    Tab(
                        selected = tocTab == 0,
                        onClick = { tocTab = 0 },
                        modifier = Modifier.focusKey("tab.chapters").tvAutoGlow(cornerRadiusDp = 6f, scale = 1.01f),
                        text = { Text("Chapters") },
                    )
                    Tab(
                        selected = tocTab == 1,
                        onClick = { tocTab = 1 },
                        modifier = Modifier.focusKey("tab.bookmarks").tvAutoGlow(cornerRadiusDp = 6f, scale = 1.01f),
                        text = { Text("Bookmarks") },
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    "Table of Contents",
                    style = KobappTheme.type.h3.copy(color = theme.textColor),
                )
                Spacer(Modifier.height(12.dp))
            }

            if (tocTab == 0) {
                LazyColumn(Modifier.focusKeyGroup("chapters")) {
                    itemsIndexed(entries) { index, entry ->
                        val isActive = entry.chapterIndex == currentChapter
                        Text(
                            text = entry.title,
                            style = KobappTheme.type.body.copy(
                                color = if (isActive) theme.linkColor else theme.textColor.copy(alpha = 0.7f),
                            ),
                            modifier = Modifier
                                .focusKey("entry.$index")
                                .fillMaxWidth()
                                .tvAutoGlow(cornerRadiusDp = 4f, scale = 1.01f)
                                .clip(RoundedCornerShape(4.dp))
                                .then(
                                    if (isActive) Modifier.background(theme.linkColor.copy(alpha = 0.1f))
                                    else Modifier
                                )
                                .clickable { onChapterSelect(entry.chapterIndex) }
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.focusKeyGroup("bookmarks")) {
                    itemsIndexed(bookmarks, key = { _, bm -> bm.id }) { _, bm ->
                        Row(
                            modifier = Modifier
                                .focusKey("bookmark.${bm.id}")
                                .fillMaxWidth()
                                .tvAutoGlow(cornerRadiusDp = 4f, scale = 1.01f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onBookmarkSelect(bm) }
                                .padding(vertical = 8.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Bookmark, null, tint = theme.linkColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                bm.label,
                                style = KobappTheme.type.body.copy(color = theme.textColor),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { onBookmarkDelete(bm) },
                                modifier = Modifier
                                    .focusKey("delete")
                                    .size(28.dp)
                                    .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                            ) {
                                Icon(
                                    Icons.Outlined.Close, "Delete",
                                    tint = theme.textColor.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
