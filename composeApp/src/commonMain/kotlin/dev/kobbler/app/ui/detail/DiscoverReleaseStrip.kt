package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.localToday
import dev.kobbler.app.platform.deviceRegion
import dev.kobbler.app.theme.KobappTheme

@Composable
internal fun ReleaseAvailabilityStrip(
    availability: ReleaseAvailability,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KobappTheme.shapes.card)
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "RELEASE AVAILABILITY",
            style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        availability.rows.forEach { row ->
            AvailabilityLine(row.label, row.value, if (row.emphasised) accent else colors.textMd)
        }
        availability.regionNote?.let { Text(it, style = KobappTheme.type.bodySm, color = colors.textLo) }
    }
}

internal fun DiscoverDetail.availability(streaming: StreamingAvailability): ReleaseAvailability =
    releaseAvailability(releaseDate, year, releaseDates, deviceRegion, localToday(), streaming, libraryStatus)

@Composable
private fun AvailabilityLine(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KobappTheme.type.bodySm, color = KobappTheme.colors.textMd)
        Text(value, style = KobappTheme.type.bodySm, color = valueColor)
    }
}
