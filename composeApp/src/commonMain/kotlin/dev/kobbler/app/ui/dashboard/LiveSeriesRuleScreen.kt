package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import dev.kobbler.app.ui.components.ListSkeleton
import dev.kobbler.app.ui.safeHorizontalPadding
import dev.kobbler.app.ui.UnderStatusBar
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.LocalTopChromeOverlap
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveCluster
import dev.kobbler.app.data.LiveRulePreview
import dev.kobbler.app.data.LiveSeriesRule
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.dashboard.livetv.LiveBadge
import dev.kobbler.app.ui.dashboard.livetv.WeekdayLabels
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.localWeekdayIndex
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private val KeepCounts = listOf(null, 5, 10, 20)
private val KeepUntil = listOf("watched", "space", "deleted")
private val PaddingSteps = listOf(0, 60, 120, 300, 600)
private val OnClashOptions = listOf(
    "move" to "Move to a later airing",
    "drop" to "Drop",
    "ask" to "Ask",
)

internal data class LiveSeriesRuleData(
    val rules: List<LiveSeriesRule>,
    val channels: List<LiveChannel>,
)

internal fun blankSeriesRule() = LiveSeriesRule(
    id = "",
    seriesId = null,
    title = "",
    enabled = true,
    channelIds = emptyList(),
    weekdays = emptyList(),
    windowStart = null,
    windowEnd = null,
    newOnly = true,
    minSeason = null,
    keepCount = 10,
    keepUntil = "watched",
    paddingBeforeSecs = 120,
    paddingAfterSecs = 360,
    onClash = "move",
    priority = 0,
    queuedCount = 0,
)

/**
 * Rule list, editor, and the repeat-suppression preview.
 *
 * The preview is the feature: one episode airs up to nine times across three clusters,
 * so a rule that does not show which airing it will take is unreadable.
 */
@OptIn(FlowPreview::class)
@Composable
fun LiveSeriesRuleScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    initialRuleId: String? = null,
    seedGuideId: String? = null,
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focusScope = LocalFocusScope.current
    val (state, retry) = rememberCached("livetv-series-rules") {
        coroutineScope {
            val rules = async { api.getLiveSeriesRules() }
            val channels = async { api.getLiveChannels(limit = 200, sort = "lcn").channels }
            LiveSeriesRuleData(rules.await(), channels.await())
        }
    }

    AsyncContent(state, skeleton = { ListSkeleton() }, onRetry = retry, onBack = onBack, modifier = modifier) { data ->
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val accent = colors.accLive
        val compact = formFactor == FormFactor.Phone
        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }

        var selectedId by remember { mutableStateOf(initialRuleId) }
        var draft by remember {
            mutableStateOf(data.rules.firstOrNull { it.id == initialRuleId } ?: data.rules.firstOrNull() ?: blankSeriesRule())
        }
        var saving by remember { mutableStateOf(false) }
        LaunchedEffect(data) {
            draft = data.rules.firstOrNull { it.id == selectedId } ?: draft
        }

        LaunchedEffect(seedGuideId) {
            if (seedGuideId == null) return@LaunchedEffect
            val entry = runCatching { api.getLiveGuideEntry(seedGuideId) }.getOrNull() ?: return@LaunchedEffect
            val start = parseLiveInstant(entry.startTime)
            draft = blankSeriesRule().copy(
                title = entry.title,
                channelIds = listOfNotNull(entry.channelId.takeIf { it.isNotBlank() }),
                weekdays = listOfNotNull(start?.localWeekdayIndex()),
                windowStart = start?.let { shiftHhMm(it.localHhMm(), -60) },
                windowEnd = start?.let { shiftHhMm(it.localHhMm(), 120) },
            )
        }

        var preview by remember { mutableStateOf<LiveRulePreview?>(null) }
        var previewing by remember { mutableStateOf(false) }
        // One pipeline, so a slow preview of an older draft can never land on top of a
        // newer one — every keystroke on this form changes what would be recorded.
        LaunchedEffect(Unit) {
            snapshotFlow { draft }
                .debounce(350)
                .collectLatest { rule ->
                    if (rule.title.isBlank() && rule.seriesId == null) {
                        preview = null
                        return@collectLatest
                    }
                    previewing = true
                    try {
                        preview = api.previewLiveSeriesRule(rule)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        preview = null
                    }
                    previewing = false
                }
        }

        LaunchedEffect(data) {
            if (focusScope.save() == null) focusScope.focusFirst()
        }

        val save: () -> Unit = {
            scope.launch {
                saving = true
                runCatching { if (draft.id.isBlank()) api.createLiveSeriesRule(draft) else api.updateLiveSeriesRule(draft) }
                    .onSuccess { draft = it; selectedId = it.id }
                saving = false
                retry()
            }
        }

        UnderStatusBar {
            PinnedHeader(
                header = {
                    Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                        BrowseHeader(
                            title = "Series rules",
                            accent = accent,
                            countLine = listOf(
                                "${data.rules.size} rules",
                                "${data.rules.sumOf { it.queuedCount }} recordings queued",
                            ).joinToString(" · "),
                            compact = compact,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(dimens.s2), verticalAlignment = Alignment.CenterVertically) {
                                    if (draft.id.isNotBlank()) {
                                        KobButton(
                                            text = "Delete",
                                            onClick = {
                                                scope.launch {
                                                    runCatching { api.deleteLiveSeriesRule(draft.id) }
                                                    selectedId = null
                                                    draft = blankSeriesRule()
                                                    retry()
                                                }
                                            },
                                            style = KobButtonStyle.Ghost,
                                            leading = { Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(16.dp)) },
                                            modifier = Modifier.focusKey("rule.delete", entryEligible = false),
                                        )
                                    }
                                    KobButton(
                                        text = if (saving) "Saving…" else "Save rule",
                                        onClick = save,
                                        style = KobButtonStyle.Accent,
                                        accent = accent,
                                        enabled = !saving && draft.title.isNotBlank(),
                                        modifier = Modifier.focusKey("rule.save", entryEligible = false),
                                    )
                                }
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxSize().safeHorizontalPadding(),
            ) {
                if (compact) {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier.scrollsUnderTopChrome(scroll).fillMaxSize().verticalScroll(scroll)
                            .padding(horizontal = gutter).padding(top = LocalTopChromeOverlap.current),
                        verticalArrangement = Arrangement.spacedBy(dimens.s4),
                    ) {
                        RuleList(data.rules, draft, accent) { draft = it; selectedId = it.id.takeIf { id -> id.isNotBlank() } }
                        RuleEditor(draft, data.channels, accent) { draft = it }
                        RulePreviewPane(preview, previewing, accent, Modifier.height(420.dp))
                        Spacer(Modifier.height(dimens.s6))
                    }
                } else {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = gutter, vertical = dimens.s2),
                        horizontalArrangement = Arrangement.spacedBy(dimens.s4),
                    ) {
                        RuleList(data.rules, draft, accent, Modifier.width(210.dp).fillMaxHeight()) { draft = it; selectedId = it.id.takeIf { id -> id.isNotBlank() } }
                        Box(Modifier.width(392.dp).fillMaxHeight()) {
                            RuleEditor(draft, data.channels, accent, Modifier.verticalScroll(rememberScrollState())) { draft = it }
                        }
                        RulePreviewPane(preview, previewing, accent, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleList(
    rules: List<LiveSeriesRule>,
    selected: LiveSeriesRule,
    accent: Color,
    modifier: Modifier = Modifier,
    onSelect: (LiveSeriesRule) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(dimens.s2)
            .focusKeyGroup("rulelist"),
        verticalArrangement = Arrangement.spacedBy(dimens.s1),
    ) {
        rules.forEachIndexed { index, rule ->
            val active = rule.id == selected.id && rule.id.isNotBlank()
            val focusState = rememberTvFocusState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .focusKey("rule.${rule.id}", preferredEntry = index == 0)
                    .tvFocusGlow(focusState, cornerRadiusDp = 9f, scale = 1.02f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) lerp(colors.elev1, accent, 0.15f) else Color.Transparent)
                    .tvClickable(focusState) { onSelect(rule) }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                Text(
                    rule.title,
                    style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (active) colors.textHi else colors.textMd,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !rule.enabled -> "paused"
                        rule.queuedCount > 0 -> "${rule.queuedCount} queued"
                        else -> "no match in window"
                    },
                    style = KobappTheme.type.labelSm,
                    color = colors.textXlo,
                )
            }
        }
        val newFocus = rememberTvFocusState()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusKey("rule.new")
                .tvFocusGlow(newFocus, cornerRadiusDp = 9f, scale = 1.02f)
                .clip(RoundedCornerShape(9.dp))
                .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                .tvClickable(newFocus) { onSelect(blankSeriesRule()) }
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Icon(Icons.Outlined.Add, null, tint = colors.textMd, modifier = Modifier.size(14.dp))
            Text("New rule", style = KobappTheme.type.caption, color = colors.textMd)
        }
    }
}

@Composable
private fun RuleEditor(
    draft: LiveSeriesRule,
    channels: List<LiveChannel>,
    accent: Color,
    modifier: Modifier = Modifier,
    onChange: (LiveSeriesRule) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(dimens.s4)
            .focusKeyGroup("ruleeditor"),
        verticalArrangement = Arrangement.spacedBy(dimens.s4),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                draft.title.ifBlank { "New rule" },
                style = KobappTheme.type.h3,
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            draft.seriesId?.let { Text(it, style = KobappTheme.type.bodySm, color = colors.textLo) }
        }

        Field(
            "Channels",
            "Scoping to a channel list is what separates a first run from a repeats channel — the same series id covers both.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                channels.forEach { channel ->
                    val on = channel.id in draft.channelIds
                    CheckRow(
                        key = "chan.${channel.id}",
                        label = channel.name,
                        note = channel.lcn?.let { "ch$it" },
                        checked = on,
                        accent = accent,
                    ) {
                        onChange(
                            draft.copy(
                                channelIds = if (on) draft.channelIds - channel.id else draft.channelIds + channel.id,
                            ),
                        )
                    }
                }
            }
        }

        Field("Airs", null) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.s2)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    WeekdayLabels.forEachIndexed { index, label ->
                        val on = index in draft.weekdays
                        KobChip(
                            text = label,
                            style = if (on) KobChipStyle.AccentTint else KobChipStyle.Default,
                            accent = accent,
                            onClick = {
                                onChange(draft.copy(weekdays = if (on) draft.weekdays - index else draft.weekdays + index))
                            },
                            modifier = Modifier.focusKey("wd.$index"),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepField("window.start", draft.windowStart ?: "any") {
                        onChange(draft.copy(windowStart = shiftHhMm(draft.windowStart ?: "00:00", 30)))
                    }
                    Text("to", style = KobappTheme.type.caption, color = colors.textLo)
                    StepField("window.end", draft.windowEnd ?: "any") {
                        onChange(draft.copy(windowEnd = shiftHhMm(draft.windowEnd ?: "00:00", 30)))
                    }
                }
            }
        }

        Field(
            "Episodes",
            "New = no recording with this episode key. The guide's First flag is not used — it marks live sport and news, not first runs.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ChoiceChip("ep.new", "New only", draft.newOnly && draft.minSeason == null, accent) {
                    onChange(draft.copy(newOnly = true, minSeason = null))
                }
                ChoiceChip("ep.all", "All", !draft.newOnly, accent) {
                    onChange(draft.copy(newOnly = false, minSeason = null))
                }
                ChoiceChip("ep.season", "From season ${draft.minSeason ?: 1}", draft.minSeason != null, accent) {
                    onChange(draft.copy(newOnly = true, minSeason = ((draft.minSeason ?: 0) + 1).takeIf { it <= 20 } ?: 1))
                }
            }
        }

        Field(
            "On a clash",
            "All tuners busy? Take a repeat inside the guide window instead of losing the episode.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OnClashOptions.forEach { (value, label) ->
                    ChoiceChip("clash.$value", label, draft.onClash == value, accent) {
                        onChange(draft.copy(onClash = value))
                    }
                }
            }
        }

        Field("Keep", null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepField("keep.count", draft.keepCount?.let { "$it most recent" } ?: "everything") {
                    val next = KeepCounts[(KeepCounts.indexOf(draft.keepCount).takeIf { it >= 0 }?.plus(1) ?: 0) % KeepCounts.size]
                    onChange(draft.copy(keepCount = next))
                }
                StepField("keep.until", "until ${draft.keepUntil ?: "watched"}") {
                    val next = KeepUntil[(KeepUntil.indexOf(draft.keepUntil).takeIf { it >= 0 }?.plus(1) ?: 0) % KeepUntil.size]
                    onChange(draft.copy(keepUntil = next))
                }
            }
        }

        Field("Padding", null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepField("pad.before", "${draft.paddingBeforeSecs / 60} min before") {
                    onChange(draft.copy(paddingBeforeSecs = nextPadding(draft.paddingBeforeSecs)))
                }
                StepField("pad.after", "${draft.paddingAfterSecs / 60} min after") {
                    onChange(draft.copy(paddingAfterSecs = nextPadding(draft.paddingAfterSecs)))
                }
            }
        }

        Field("Rule", null) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ChoiceChip("rule.enabled", if (draft.enabled) "Enabled" else "Paused", draft.enabled, accent) {
                    onChange(draft.copy(enabled = !draft.enabled))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, hint: String?, content: @Composable () -> Unit) {
    val colors = KobappTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label.uppercase(), style = KobappTheme.type.labelSm, color = colors.textXlo)
        content()
        if (hint != null) Text(hint, style = KobappTheme.type.labelSm, color = colors.textXlo)
    }
}

@Composable
private fun CheckRow(
    key: String,
    label: String,
    note: String?,
    checked: Boolean,
    accent: Color,
    onToggle: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key)
            .tvFocusGlow(focusState, cornerRadiusDp = 6f, scale = 1.01f)
            .clip(RoundedCornerShape(6.dp))
            .tvClickable(focusState, onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) accent else Color.Transparent)
                .border(1.dp, if (checked) accent else colors.line, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Outlined.Check, null, tint = colors.bg, modifier = Modifier.size(10.dp))
        }
        Text(
            label,
            style = KobappTheme.type.caption,
            color = if (checked) colors.textHi else colors.textMd,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (note != null) Text(note, style = KobappTheme.type.labelSm, color = colors.textXlo)
    }
}

@Composable
private fun ChoiceChip(key: String, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    KobChip(
        text = label,
        style = if (active) KobChipStyle.AccentTint else KobChipStyle.Default,
        accent = accent,
        onClick = onClick,
        modifier = Modifier.focusKey(key),
    )
}

@Composable
private fun StepField(key: String, label: String, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Box(
        Modifier
            .focusKey(key)
            .tvFocusGlow(focusState, cornerRadiusDp = 8f, scale = 1.02f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.line, RoundedCornerShape(8.dp))
            .tvClickable(focusState, onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(label, style = KobappTheme.type.caption, color = colors.textHi)
    }
}

@Composable
private fun RulePreviewPane(
    preview: LiveRulePreview?,
    loading: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(dimens.s4), verticalArrangement = Arrangement.spacedBy(dimens.s1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What this records", style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold), color = colors.textHi)
                Text(
                    if (loading) "re-evaluating…" else "from the live guide, re-evaluated on every refresh",
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                )
            }
            if (preview != null) {
                val airings = preview.clusters.sumOf { it.members.size }
                Text(
                    "$airings airings in the window across ${preview.clusters.size} clusters — " +
                        "${preview.wouldRecord} recorded, ${preview.suppressed} suppressed.",
                    style = KobappTheme.type.caption,
                    color = colors.textMd,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))

        if (preview == null || preview.clusters.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Info,
                title = if (loading) "Working out the airings" else "Nothing matches yet",
                subtitle = "Pick a channel and a title; the preview lists every airing the rule can see.",
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(dimens.s4),
            verticalArrangement = Arrangement.spacedBy(dimens.s4),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(preview.clusters, key = { it.label + it.startTime }) { cluster ->
                ClusterBlock(cluster, accent)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(dimens.s4),
        ) {
            Icon(Icons.Outlined.Info, null, tint = accent, modifier = Modifier.size(14.dp))
            Text(
                "Clustering uses a ±5 min tolerance, not equal start times — simulcast feeds drift.",
                style = KobappTheme.type.caption,
                color = colors.textMd,
            )
        }
    }
}

@Composable
private fun ClusterBlock(cluster: LiveCluster, accent: Color) {
    val colors = KobappTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(cluster.label.uppercase(), style = KobappTheme.type.labelSm, color = accent)
            Text(
                parseLiveInstant(cluster.startTime)?.localHhMm() ?: cluster.startTime,
                style = KobappTheme.type.caption,
                color = colors.textMd,
            )
            Text("${cluster.members.size} airings", style = KobappTheme.type.caption, color = colors.textLo)
        }
        cluster.members.forEach { member ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (member.record) lerp(colors.elev1, colors.ok, 0.10f) else colors.elev2)
                    .border(1.dp, if (member.record) colors.ok else colors.lineSoft, RoundedCornerShape(9.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                LiveBadge(if (member.record) "RECORD" else "SKIP", if (member.record) colors.ok else colors.textLo)
                Text(
                    member.lcn ?: "",
                    style = KobappTheme.type.bodySm,
                    color = colors.textMd,
                    modifier = Modifier.width(30.dp),
                )
                Text(
                    member.channelName,
                    style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (member.record) colors.textHi else colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(140.dp),
                )
                LiveBadge(if (member.hd) "HD" else "SD", if (member.hd) colors.accSports else colors.textXlo)
                Text(
                    parseLiveInstant(member.startTime)?.localHhMm() ?: "",
                    style = KobappTheme.type.bodySm,
                    color = colors.textMd,
                )
                Text(
                    member.reason,
                    style = KobappTheme.type.labelSm,
                    color = colors.textXlo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun nextPadding(current: Int): Int {
    val idx = PaddingSteps.indexOf(current)
    return PaddingSteps[if (idx < 0) 0 else (idx + 1) % PaddingSteps.size]
}

internal fun shiftHhMm(hhmm: String, deltaMinutes: Int): String {
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    var total = (h * 60 + m + deltaMinutes) % (24 * 60)
    if (total < 0) total += 24 * 60
    val hh = total / 60
    val mm = total % 60
    return "${if (hh < 10) "0$hh" else "$hh"}:${if (mm < 10) "0$mm" else "$mm"}"
}
