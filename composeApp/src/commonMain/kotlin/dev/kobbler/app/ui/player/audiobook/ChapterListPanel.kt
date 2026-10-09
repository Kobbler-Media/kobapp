package dev.kobbler.app.ui.player.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Bookmark
import dev.kobbler.app.data.TranscriptSegmentDto
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.kobplayer.ui.formatTime

enum class PanelTab { Chapters, Transcript, Bookmarks }

@Composable
fun ChapterListPanel(
    chapters: List<UnifiedChapterDto>,
    transcriptSegments: List<TranscriptSegmentDto>,
    bookmarks: List<Bookmark>,
    currentChapter: UnifiedChapterDto?,
    globalPositionMs: Long,
    activeSegmentIndex: Int,
    activeTab: PanelTab,
    onTabChange: (PanelTab) -> Unit,
    onChapterClick: (UnifiedChapterDto) -> Unit,
    onSegmentClick: (TranscriptSegmentDto) -> Unit,
    onBookmarkClick: (Bookmark) -> Unit,
    onBookmarkDelete: (Bookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accAudio
    val available = buildList {
        if (chapters.isNotEmpty()) add(PanelTab.Chapters)
        if (transcriptSegments.isNotEmpty()) add(PanelTab.Transcript)
        if (bookmarks.isNotEmpty()) add(PanelTab.Bookmarks)
    }
    if (available.isEmpty()) return
    val tab = if (activeTab in available) activeTab else available.first()

    Column(modifier = modifier.focusKeyGroup("panel").fillMaxWidth()) {
        val countLine = when (tab) {
            PanelTab.Chapters -> {
                val idx = chapters.indexOf(currentChapter).coerceAtLeast(0)
                "${idx + 1} of ${chapters.size}"
            }
            PanelTab.Transcript -> "${transcriptSegments.size} lines"
            PanelTab.Bookmarks -> "${bookmarks.size} saved"
        }
        SectionHeading(
            title = when (tab) {
                PanelTab.Chapters -> "Chapters"
                PanelTab.Transcript -> "Transcript"
                PanelTab.Bookmarks -> "Bookmarks"
            },
            accent = accent,
            trailing = {
                Text(countLine, color = colors.textLo, style = type.bodySm)
            },
        )
        if (available.size > 1) {
            Spacer(Modifier.height(KobappTheme.dimens.s3))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                available.forEach { entry ->
                    val active = entry == tab
                    val label = when (entry) {
                        PanelTab.Chapters -> "Chapters"
                        PanelTab.Transcript -> "Transcript"
                        PanelTab.Bookmarks -> "Bookmarks"
                    }
                    Row(
                        modifier = Modifier
                            .focusKey("tab.${entry.name}")
                            .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.02f)
                            .clip(KobappTheme.shapes.pill)
                            .background(if (active) accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { onTabChange(entry) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            color = if (active) accent else colors.textMd,
                            style = type.bodySm,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(KobappTheme.dimens.s3))
        when (tab) {
            PanelTab.Chapters -> ChaptersList(
                chapters = chapters,
                currentChapter = currentChapter,
                globalPositionMs = globalPositionMs,
                onChapterClick = onChapterClick,
            )
            PanelTab.Transcript -> TranscriptList(
                segments = transcriptSegments,
                activeIndex = activeSegmentIndex,
                onSegmentClick = onSegmentClick,
            )
            PanelTab.Bookmarks -> BookmarksList(
                bookmarks = bookmarks,
                onBookmarkClick = onBookmarkClick,
                onBookmarkDelete = onBookmarkDelete,
            )
        }
    }
}

@Composable
private fun ChaptersList(
    chapters: List<UnifiedChapterDto>,
    currentChapter: UnifiedChapterDto?,
    globalPositionMs: Long,
    onChapterClick: (UnifiedChapterDto) -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accAudio
    val listState = rememberLazyListState()
    val activeIdx = chapters.indexOf(currentChapter).takeIf { it >= 0 }

    LaunchedEffect(activeIdx) {
        if (activeIdx != null && activeIdx >= 0) {
            listState.animateScrollToItem(activeIdx, scrollOffset = -100)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().focusKeyGroup("chapters"),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
            val active = chapter == currentChapter
            val completed = (chapter.globalEndSeconds * 1000).toLong() < globalPositionMs
            val dur = chapter.globalEndSeconds - chapter.globalStartSeconds
            Row(
                modifier = Modifier
                    .focusKey("chapter.${chapter.id}")
                    .fillMaxWidth()
                    .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.01f)
                    .clip(KobappTheme.shapes.small)
                    .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onChapterClick(chapter) }
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (completed && !active) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Completed",
                        tint = accent.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Text(
                        "${index + 1}".padStart(2, '0'),
                        style = type.bodySm,
                        color = if (active) accent else colors.textXlo,
                        modifier = Modifier.width(26.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        chapter.title ?: "Chapter ${index + 1}",
                        style = type.bodySm,
                        color = when {
                            active -> colors.textHi
                            completed -> colors.textLo.copy(alpha = 0.55f)
                            else -> colors.textMd
                        },
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime((dur * 1000).toLong()),
                    style = type.bodySm,
                    color = if (completed && !active) colors.textLo.copy(alpha = 0.5f) else colors.textLo,
                )
            }
        }
    }
}

@Composable
private fun TranscriptList(
    segments: List<TranscriptSegmentDto>,
    activeIndex: Int,
    onSegmentClick: (TranscriptSegmentDto) -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accAudio
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -120)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().focusKeyGroup("transcript"),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(segments, key = { _, segment -> segment.id }) { index, segment ->
            val active = index == activeIndex
            val past = index < activeIndex
            Text(
                text = segment.text,
                style = type.body,
                color = when {
                    active -> colors.textHi
                    past -> colors.textLo.copy(alpha = 0.5f)
                    else -> colors.textMd
                },
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .focusKey("segment.${segment.id}")
                    .fillMaxWidth()
                    .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.01f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSegmentClick(segment) }
                    .padding(vertical = 6.dp, horizontal = 10.dp),
            )
        }
    }
}

@Composable
private fun BookmarksList(
    bookmarks: List<Bookmark>,
    onBookmarkClick: (Bookmark) -> Unit,
    onBookmarkDelete: (Bookmark) -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accAudio
    LazyColumn(
        modifier = Modifier.fillMaxWidth().focusKeyGroup("bookmarks"),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(bookmarks, key = { _, bookmark -> bookmark.id }) { _, bookmark ->
            Row(
                modifier = Modifier
                    .focusKey("bookmark.${bookmark.id}")
                    .fillMaxWidth()
                    .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.01f)
                    .clip(KobappTheme.shapes.small)
                    .clickable { onBookmarkClick(bookmark) }
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Bookmark,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    bookmark.label,
                    style = type.bodySm,
                    color = colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onBookmarkDelete(bookmark) },
                    modifier = Modifier
                        .focusKey("delete")
                        .size(32.dp)
                        .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        "Delete",
                        tint = colors.textLo,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
