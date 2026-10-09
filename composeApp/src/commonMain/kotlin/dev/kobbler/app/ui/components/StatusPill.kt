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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

enum class PillStatus { InLibrary, Wanted, NotInLibrary, Live }

@Composable
fun StatusPill(
    status: PillStatus,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    // "Not in library" is the default state of everything in Discover — saying so on
    // every tile is noise, so it renders nothing at all.
    if (status == PillStatus.NotInLibrary) return
    val (label, accent) = when (status) {
        PillStatus.InLibrary -> null to colors.ok
        PillStatus.Wanted -> "WANTED" to colors.accWanted
        PillStatus.Live -> "LIVE" to colors.accLive
        PillStatus.NotInLibrary -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(KobappTheme.shapes.pill)
            .background(lerp(colors.elev1, accent, 0.14f))
            .border(1.dp, lerp(Color.Transparent, accent, 0.3f), KobappTheme.shapes.pill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).background(accent),
        )
        if (label != null) {
            Text(
                text = label,
                style = KobappTheme.type.labelSm.copy(
                    letterSpacing = TextUnit(0.1f, TextUnitType.Em),
                ),
                color = accent,
            )
        }
    }
}
