package dev.kobbler.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Profile
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.SpotlightBackdrop
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.components.spotlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val PickerCycleMs = 8_000L

/**
 * Whose view of the library the picker shows. Everyone in the household sees this screen,
 * so a Kids profile's view wins when there is one; otherwise whoever was here last.
 */
internal fun pickerSourceProfile(profiles: List<Profile>, currentId: String?): Profile? {
    val usable = profiles.filterNot { it.needsRelink }
    return usable.firstOrNull { it.isKids } ?: usable.firstOrNull { it.id == currentId } ?: usable.firstOrNull()
}

/** The library's newest arrivals behind the picker, one at a time; stripes until there are any. */
@Composable
internal fun PickerBackdrop(auth: AuthState, accent: Color, content: @Composable () -> Unit) {
    val source = pickerSourceProfile(auth.profiles, auth.currentProfileId)
    var arrivals by remember { mutableStateOf<List<RecentlyAddedItem>>(emptyList()) }
    LaunchedEffect(source?.apiUrl, source?.token) {
        val profile = source ?: return@LaunchedEffect
        arrivals = try {
            KobblerApi(auth.httpClient, profile.apiUrl, profile.token)
                .getDiscover()
                .recentlyAdded
                .filter { !it.backdropUrl.isNullOrBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    var index by remember(arrivals) { mutableIntStateOf(0) }
    LaunchedEffect(arrivals) {
        if (arrivals.size < 2) return@LaunchedEffect
        while (true) {
            delay(PickerCycleMs)
            index = (index + 1) % arrivals.size
        }
    }

    val current = arrivals.getOrNull(index)
    // One tree whatever is behind it: swapping the content's parent when the arrivals land
    // would rebuild the profile tiles and drop the focus the first one was just given.
    Box(Modifier.fillMaxSize()) {
        if (current == null) {
            StripedBackdrop(accent = accent, cached = true, modifier = Modifier.fillMaxSize())
        } else {
            SpotlightBackdrop(
                spotlight = { current.spotlight() },
                accent = accent,
                dim = { 0f },
                modifier = Modifier.fillMaxSize(),
                // Nothing sits over the picture but the profile strip and the caption, and
                // each carries its own backing; the page scrims would only shade the art.
                scrims = false,
            )
        }
        content()
        current?.let { ArrivalCaption(it, Modifier.align(Alignment.BottomEnd).padding(28.dp)) }
    }
}

@Composable
private fun ArrivalCaption(item: RecentlyAddedItem, modifier: Modifier) {
    val colors = KobappTheme.colors
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bg.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("RECENTLY ADDED", color = colors.textLo, style = KobappTheme.type.label)
        Text(
            item.title,
            color = colors.textHi,
            style = KobappTheme.type.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
        if (!item.subtitle.isNullOrBlank()) {
            Text(
                item.subtitle,
                color = colors.textMd,
                style = KobappTheme.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}
