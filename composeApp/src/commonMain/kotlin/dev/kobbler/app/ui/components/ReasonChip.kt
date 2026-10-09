package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import dev.kobbler.app.data.UpNextReason
import dev.kobbler.app.theme.KobappTheme

@Composable
fun ReasonChip(
    reason: UpNextReason,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val (accent, hasDot) = when (reason) {
        UpNextReason.Resume -> colors.accTv to false
        UpNextReason.NewEpisode -> colors.accNotif to true
        UpNextReason.NewSeason -> colors.accWanted to true
        UpNextReason.NextInSeries -> colors.accBooks to false
        UpNextReason.Schedule -> colors.accLive to true
        UpNextReason.Live -> colors.accLive to true
        UpNextReason.Companion -> colors.accAudio to false
        UpNextReason.Recommended -> colors.accWanted to false
        UpNextReason.FollowUp -> colors.accWanted to false
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(KobappTheme.shapes.pill)
            .background(lerp(Color.Transparent, accent, 0.14f))
            .border(1.dp, lerp(Color.Transparent, accent, 0.25f), KobappTheme.shapes.pill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (hasDot) Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Text(
            text = label.uppercase(),
            style = KobappTheme.type.label.copy(
                letterSpacing = TextUnit(0.02f, TextUnitType.Em),
            ),
            color = accent,
        )
    }
}
