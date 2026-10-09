package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveOnNowGroup
import dev.kobbler.app.data.LiveOnNowItem
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.dashboard.livetv.CenterFocusedRow
import dev.kobbler.app.ui.dashboard.livetv.ConfidenceDots
import dev.kobbler.app.ui.dashboard.livetv.GuideFocus
import dev.kobbler.app.ui.dashboard.livetv.OnNowCard
import dev.kobbler.app.ui.dashboard.livetv.OnNowNotice
import dev.kobbler.app.ui.dashboard.livetv.OnNowPhoneRow
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.safeTopPadding
import kotlinx.coroutines.launch

private const val SeriesGroupKey = "series"

private fun cardKey(group: LiveOnNowGroup, item: LiveOnNowItem) = "on.${group.key}.${item.guide.id}"

/**
 * Ranked from watch history the tuner writes locally, which is why the page says so:
 * the whole surface is a claim about the viewer, and an unsourced claim reads as tracking.
 */
@Composable
fun LiveOnNowScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onBack: () -> Unit = {},
    onWatch: (LiveChannel) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val (state, retry) = rememberCached("livetv-on-now") { api.getLiveOnNow() }

    AsyncContent(state, onRetry = retry, onBack = onBack, modifier = modifier) { data ->
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val accent = colors.accLive
        val compact = formFactor == FormFactor.Phone
        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }

        var noticeDismissed by remember(data.tunerNotice?.guide?.id) { mutableStateOf(false) }
        var noticeBusy by remember { mutableStateOf(false) }

        val groups = remember(data) { data.groups.filter { it.items.isNotEmpty() } }
        val listState = rememberLazyListState()
        val focus = remember { GuideFocus() }
        CenterFocusedRow(listState, focus, topRow = 0)

        val entryKey = groups.firstOrNull()?.let { g -> g.items.firstOrNull()?.let { cardKey(g, it) } }

        Column(Modifier.fillMaxSize().background(colors.bg).safeTopPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s4),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter)
                    .padding(top = dimens.s4)
                    .focusKeyGroup("onnowhead"),
            ) {
                KobButton(
                    onClick = onBack,
                    style = KobButtonStyle.Ghost,
                    modifier = Modifier.focusKey("onnow.back", entryEligible = false),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.size(20.dp))
                }
                Box(Modifier.weight(1f)) {
                    BrowseHeader(
                        title = "On now",
                        accent = accent,
                        countLine = generatedLine(data.generatedAt, groups.sumOf { it.items.size }),
                        compact = compact,
                    )
                }
            }

            val notice = data.tunerNotice
            if (notice != null && !noticeDismissed) {
                OnNowNotice(
                    notice = notice,
                    busy = noticeBusy,
                    accent = accent,
                    compact = compact,
                    modifier = Modifier.padding(horizontal = gutter).padding(top = dimens.s3),
                    onIgnore = { noticeDismissed = true },
                    onRecordAlternative = { guideId ->
                        scope.launch {
                            noticeBusy = true
                            runCatching { api.recordLiveGuideEntry(guideId) }
                            noticeBusy = false
                            noticeDismissed = true
                            retry()
                        }
                    },
                )
            }

            if (groups.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Tv,
                    title = "Nothing to suggest yet",
                    subtitle = "Ranking needs watch history — how many airings you sat through, how much " +
                        "of each you finished, and how regular the slot is. Watch a few things and this fills in.",
                )
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = dimens.s4, bottom = dimens.s6),
                verticalArrangement = Arrangement.spacedBy(dimens.s5),
            ) {
                itemsIndexed(groups, key = { _, g -> g.key }) { index, group ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(dimens.s2),
                        modifier = Modifier
                            .focusKeyGroup("onnow.${group.key}")
                            .onFocusChanged { if (it.hasFocus) focus.lazyIndex = index },
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            modifier = Modifier.padding(horizontal = gutter),
                        ) {
                            Text(
                                group.title,
                                style = KobappTheme.type.h3,
                                color = colors.textHi,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (group.subtitle.isNotBlank() && !compact) {
                                Text(
                                    group.subtitle,
                                    style = KobappTheme.type.caption,
                                    color = colors.textLo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (compact) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(dimens.s2),
                                modifier = Modifier.padding(horizontal = gutter),
                            ) {
                                group.items.forEach { item ->
                                    val key = cardKey(group, item)
                                    OnNowPhoneRow(
                                        item = item,
                                        accent = accent,
                                        focusKey = key,
                                        preferredEntry = key == entryKey,
                                        onWatch = { onWatch(item.channel) },
                                    )
                                }
                            }
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = gutter),
                                horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                            ) {
                                items(group.items, key = { it.guide.id }) { item ->
                                    val key = cardKey(group, item)
                                    OnNowCard(
                                        item = item,
                                        groupKey = group.key,
                                        wide = group.key == SeriesGroupKey,
                                        accent = accent,
                                        focusKey = key,
                                        preferredEntry = key == entryKey,
                                        onWatch = { onWatch(item.channel) },
                                    )
                                }
                            }
                        }
                    }
                }
                item("onnow.explainer") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimens.s2),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
                    ) {
                        ConfidenceDots(0.5f, accent)
                        Text(
                            "Confidence comes from watch count, how much of each programme you finished, " +
                                "and how regular the slot is — a show you start and bail on scores lower " +
                                "than one you never miss.",
                            style = KobappTheme.type.caption,
                            color = colors.textLo,
                        )
                    }
                }
            }
        }
    }
}

private fun generatedLine(generatedAt: String, total: Int): String = listOfNotNull(
    parseLiveInstant(generatedAt)?.localHhMm()?.let { "as of $it" },
    "$total on now",
    "ranked from your own watch history, nothing leaves the house",
).joinToString(" · ")
