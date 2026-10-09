package dev.kobbler.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

fun parseVtt(text: String): List<SubtitleCue> {
    val cues = mutableListOf<SubtitleCue>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val arrow = line.indexOf("-->")
        if (arrow < 0) {
            i++
            continue
        }
        val startStr = line.substring(0, arrow).trim()
        val endPart = line.substring(arrow + 3).trim()
        val endStr = endPart.substringBefore(' ').substringBefore('\t')
        val startMs = parseVttTimestamp(startStr)
        val endMs = parseVttTimestamp(endStr)
        i++
        val buf = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank()) {
            buf += lines[i]
            i++
        }
        if (startMs >= 0 && endMs > startMs && buf.isNotEmpty()) {
            cues += SubtitleCue(startMs, endMs, stripVttTags(buf.joinToString("\n")))
        }
        i++
    }
    return cues
}

private fun parseVttTimestamp(raw: String): Long {
    if (raw.isEmpty()) return -1L
    val parts = raw.split(":")
    return when (parts.size) {
        3 -> {
            val h = parts[0].toLongOrNull() ?: return -1L
            val m = parts[1].toLongOrNull() ?: return -1L
            val (s, ms) = parseSecondsFragment(parts[2])
            if (s < 0) -1L else h * 3_600_000 + m * 60_000 + s * 1_000 + ms
        }
        2 -> {
            val m = parts[0].toLongOrNull() ?: return -1L
            val (s, ms) = parseSecondsFragment(parts[1])
            if (s < 0) -1L else m * 60_000 + s * 1_000 + ms
        }
        else -> -1L
    }
}

private fun parseSecondsFragment(raw: String): Pair<Long, Long> {
    val parts = raw.split('.', ',')
    val s = parts[0].toLongOrNull() ?: return -1L to 0L
    val ms = parts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
    return s to ms
}

private val vttTagRegex = Regex("<[^>]+>")
private fun stripVttTags(text: String): String =
    text.replace(vttTagRegex, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

@Composable
fun rememberSubtitleCues(
    url: String?,
    client: HttpClient,
    authToken: String,
): List<SubtitleCue> {
    var cues by remember(url) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            cues = emptyList()
            return@LaunchedEffect
        }
        cues = try {
            val text = client.get(url) {
                if (authToken.isNotEmpty()) header("Authorization", "Bearer $authToken")
            }.bodyAsText()
            parseVtt(text)
        } catch (_: Exception) {
            emptyList()
        }
    }
    return cues
}

@Composable
fun SubtitleOverlay(
    cues: List<SubtitleCue>,
    currentMs: () -> Long,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (cues.isEmpty()) return
    // Plain IntArray, not snapshot state: findCueAt writes the cursor back while it runs,
    // and a snapshot write during composition would invalidate this scope every advance.
    val cursor = remember(cues) { intArrayOf(0) }
    val currentMsFn by rememberUpdatedState(currentMs)
    val active by remember(cues) { derivedStateOf { findCueAt(cues, currentMsFn(), cursor) } }

    var displayed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active?.text) {
        active?.let { displayed = it.text }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (compact) Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                else Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 72.dp),
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = active != null,
            enter = fadeIn(animationSpec = tween(140)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            displayed?.let { text ->
                SubtitleBox(text = text, formFactor = formFactor, compact = compact)
            }
        }
    }
}

@Composable
private fun SubtitleBox(text: String, formFactor: FormFactor, compact: Boolean) {
    val colors = KobappTheme.colors
    val fontSize = when {
        compact -> 10.sp
        formFactor == FormFactor.TV -> 28.sp
        formFactor == FormFactor.Desktop -> 24.sp
        else -> 18.sp
    }
    val maxLineWidth = when (formFactor) {
        FormFactor.TV -> 800.dp
        FormFactor.Desktop -> 880.dp
        FormFactor.Phone -> 560.dp
    }
    val style = KobappTheme.type.body.copy(
        fontSize = fontSize,
        lineHeight = fontSize * 1.25f,
        fontWeight = FontWeight.Medium,
        color = colors.textHi,
        textAlign = TextAlign.Center,
    )
    val lines = text.split('\n')
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        lines.forEach { line ->
            Box(
                modifier = Modifier
                    .widthIn(max = maxLineWidth)
                    .clip(RoundedCornerShape(if (compact) 3.dp else 6.dp))
                    .background(colors.bg.copy(alpha = 0.7f))
                    .then(
                        if (compact) Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        else Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    ),
            ) {
                Text(text = line, style = style)
            }
        }
    }
}

private fun findCueAt(cues: List<SubtitleCue>, ms: Long, cursor: IntArray): SubtitleCue? {
    if (cues.isEmpty()) return null
    var i = cursor[0].coerceIn(0, cues.lastIndex)
    while (i > 0 && cues[i].startMs > ms) i--
    while (i < cues.lastIndex && cues[i].endMs < ms) i++
    cursor[0] = i
    val cue = cues[i]
    return if (ms in cue.startMs..cue.endMs) cue else null
}
