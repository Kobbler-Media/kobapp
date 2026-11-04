package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HearingDisabled
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PlaybackSubtitleTrackDto
import dev.kobbler.app.data.SubtitleSearchResultDto
import dev.kobbler.app.theme.KobappColors
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.KobBackButton
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.kobfocus.rememberFocusScopeStore
import kotlinx.coroutines.launch

private val COMMON_LANGUAGES = listOf(
    "eng" to "English",
    "spa" to "Spanish",
    "fre" to "French",
    "ger" to "German",
    "ita" to "Italian",
    "por" to "Portuguese",
    "jpn" to "Japanese",
    "kor" to "Korean",
    "chi" to "Chinese",
    "ara" to "Arabic",
    "dut" to "Dutch",
    "pol" to "Polish",
    "tur" to "Turkish",
    "swe" to "Swedish",
    "nor" to "Norwegian",
    "dan" to "Danish",
    "fin" to "Finnish",
    "rus" to "Russian",
)

@Composable
fun SubtitleSearchOverlay(
    api: KobblerApi,
    mediaType: String,
    mediaId: String,
    sessionId: String,
    onDismiss: () -> Unit,
    onSubtitleAdded: (PlaybackSubtitleTrackDto) -> Unit,
) {
    val colors = KobappTheme.colors
    val scope = rememberCoroutineScope()
    var selectedLanguages by remember { mutableStateOf(listOf("eng")) }
    var results by remember { mutableStateOf<List<SubtitleSearchResultDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun search() {
        if (selectedLanguages.isEmpty()) return
        scope.launch {
            loading = true
            error = null
            try {
                results = when (mediaType) {
                    "movie" -> api.searchMovieSubtitles(mediaId, selectedLanguages)
                    "episode" -> api.searchEpisodeSubtitles(mediaId, selectedLanguages)
                    else -> emptyList()
                }
                if (results.isEmpty()) error = "No subtitles found"
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { search() }
    InterceptBack(enabled = true, onBack = onDismiss)

    // The overlay sits outside the navigation focus host, so it hosts its own scope:
    // without one nothing claims entry focus and the D-pad has nowhere to land.
    FocusScopeHost(id = "subtitle-search", store = rememberFocusScopeStore()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = 0.78f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Box(
                modifier = Modifier
                    .focusKeyGroup("subsearch")
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .widthIn(min = 360.dp, max = 460.dp)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.elev1)
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Header(onDismiss = onDismiss, loading = loading)

                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        SectionHeading(
                            title = "Languages",
                            accent = colors.accTv,
                            trailing = {
                                Text(
                                    "${selectedLanguages.size} selected",
                                    style = KobappTheme.type.bodySm,
                                    color = colors.textLo,
                                )
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        LanguageChipGrid(
                            selected = selectedLanguages,
                            onToggle = { code ->
                                selectedLanguages = if (code in selectedLanguages) {
                                    selectedLanguages - code
                                } else {
                                    selectedLanguages + code
                                }
                            },
                            accent = colors.accTv,
                        )
                        Spacer(Modifier.height(14.dp))
                        KobButton(
                            onClick = { search() },
                            style = KobButtonStyle.Accent,
                            accent = colors.accTv,
                            enabled = !loading && selectedLanguages.isNotEmpty(),
                            modifier = Modifier.focusKey("search").fillMaxWidth(),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = colors.bg,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (loading) "Searching" else "Search",
                                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.bg,
                            )
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))

                    ResultsSection(
                        results = results,
                        loading = loading,
                        error = error,
                        downloadingId = downloadingId,
                        downloadedIds = downloadedIds,
                        accent = colors.accTv,
                        colors = colors,
                        onUse = { sub ->
                            scope.launch {
                                downloadingId = sub.id
                                try {
                                    val dto = api.downloadSubtitleToSession(
                                        sessionId = sessionId,
                                        provider = sub.provider,
                                        subtitleId = sub.id,
                                        language = sub.language,
                                    )
                                    downloadedIds = downloadedIds + sub.id
                                    onSubtitleAdded(dto)
                                } catch (_: Exception) {
                                }
                                downloadingId = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onDismiss: () -> Unit, loading: Boolean) {
    val colors = KobappTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (LocalFormFactor.current != FormFactor.TV) {
                KobBackButton(onDismiss, modifier = Modifier.focusKey("back", entryEligible = false))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "SUBTITLE SEARCH",
                    style = KobappTheme.type.bodySm.copy(letterSpacing = 1.6.sp),
                    color = colors.textLo,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Find subtitles",
                    style = KobappTheme.type.h2.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textHi,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (loading) 2.dp else 1.dp)
                .background(if (loading) colors.accTv.copy(alpha = 0.55f) else colors.lineSoft),
        )
    }
}

@Composable
private fun LanguageChipGrid(
    selected: List<String>,
    onToggle: (String) -> Unit,
    accent: Color,
) {
    val colors = KobappTheme.colors
    val rows = COMMON_LANGUAGES.chunked(3)
    Column(Modifier.focusKeyGroup("langs"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (code, label) ->
                    val isOn = code in selected
                    Box(Modifier.weight(1f)) {
                        KobChip(
                            text = label,
                            style = if (isOn) KobChipStyle.AccentTint else KobChipStyle.Default,
                            accent = accent,
                            leading = {
                                Text(
                                    code.uppercase(),
                                    style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isOn) accent else colors.textLo,
                                )
                            },
                            onClick = { onToggle(code) },
                            modifier = Modifier.focusKey("lang.$code").fillMaxWidth(),
                        )
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) {} }
            }
        }
    }
}

@Composable
private fun ResultsSection(
    results: List<SubtitleSearchResultDto>,
    loading: Boolean,
    error: String?,
    downloadingId: String?,
    downloadedIds: Set<String>,
    accent: Color,
    colors: KobappColors,
    onUse: (SubtitleSearchResultDto) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading(
                title = "Results",
                accent = accent,
                trailing = {
                    Text(
                        if (loading) "searching" else "${results.size} matches",
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                    )
                },
            )
        }

        if (error != null && results.isEmpty() && !loading) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(error, style = KobappTheme.type.caption, color = colors.textLo)
            }
        } else if (results.isEmpty() && loading) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.textLo,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().focusKeyGroup("results"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                items(results, key = { "${it.provider}-${it.id}" }) { sub ->
                    SubtitleResultRow(
                        result = sub,
                        isDownloading = downloadingId == sub.id,
                        isDownloaded = sub.id in downloadedIds,
                        accent = accent,
                        colors = colors,
                        onUse = { onUse(sub) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleResultRow(
    result: SubtitleSearchResultDto,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    accent: Color,
    colors: KobappColors,
    onUse: () -> Unit,
) {
    val focus = rememberTvFocusState()
    val enabled = !isDownloading && !isDownloaded
    val ratingPct = result.rating?.let { (it.coerceIn(0.0, 10.0) * 10).toInt() }

    Row(
        modifier = Modifier
            .focusKey("result.${result.provider}-${result.id}")
            .fillMaxWidth()
            .tvFocusGlow(focus, cornerRadiusDp = 10f, scale = 1.01f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elev2)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.tvClickable(focus, onUse) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                result.language.uppercase(),
                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Subtitles, null, tint = colors.textMd, modifier = Modifier.size(14.dp))
                Text(
                    result.provider,
                    style = KobappTheme.type.bodySm,
                    color = colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result.isForced) {
                    Text(
                        "FORCED",
                        style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                }
                if (result.isHearingImpaired) {
                    Icon(
                        Icons.Outlined.HearingDisabled,
                        contentDescription = "SDH",
                        tint = colors.textLo,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ratingPct != null) {
                    Text(
                        "$ratingPct%",
                        style = KobappTheme.type.bodySm,
                        color = colors.textMd,
                    )
                }
                if (result.format != null) {
                    Text(
                        result.format.uppercase(),
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                    )
                }
                if (result.downloadCount != null && result.downloadCount > 0) {
                    Text(
                        "${result.downloadCount} dl",
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                    )
                }
            }
        }

        when {
            isDownloaded -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Check, null, tint = colors.ok, modifier = Modifier.size(16.dp))
                Text("Added", style = KobappTheme.type.caption, color = colors.ok)
            }
            isDownloading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = accent,
            )
            else -> KobButton(
                onClick = onUse,
                style = KobButtonStyle.Ghost,
                accent = accent,
                modifier = Modifier.focusKey("use"),
            ) {
                Text(
                    "Use",
                    style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                )
            }
        }
    }
}
