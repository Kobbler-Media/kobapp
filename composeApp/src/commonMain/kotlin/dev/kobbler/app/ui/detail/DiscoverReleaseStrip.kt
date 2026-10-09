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
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme

@Composable
internal fun ReleaseAvailabilityStrip(
    detail: DiscoverDetail,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val theatrical = detail.year?.toString() ?: "—"
    val streaming = "—"
    val library = when (detail.libraryStatus) {
        LibraryStatus.Wanted -> "Wanted"
        LibraryStatus.InLibrary -> "In library"
        LibraryStatus.NotInLibrary -> "—"
    }
    val libraryColor = when (detail.libraryStatus) {
        LibraryStatus.Wanted, LibraryStatus.InLibrary -> accent
        LibraryStatus.NotInLibrary -> colors.textLo
    }
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
        AvailabilityRow("Theatrical", theatrical, colors.textMd)
        AvailabilityRow("Streaming", streaming, colors.textLo)
        AvailabilityRow("Library", library, libraryColor)
    }
}

@Composable
private fun AvailabilityRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KobappTheme.type.bodySm, color = KobappTheme.colors.textMd)
        Text(value, style = KobappTheme.type.bodySm, color = valueColor)
    }
}

@Composable
internal fun DiscoverDetailsPanel(detail: DiscoverDetail, formFactor: FormFactor) {
    val colors = KobappTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KobappTheme.shapes.card)
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Details", style = KobappTheme.type.h3, color = colors.textHi)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(
                detail.provider.lowercase(),
                style = KobappTheme.type.bodySm,
                color = colors.textLo,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        InfoLine("Title", detail.title, formFactor)
        if (detail.year != null) InfoLine("Year", detail.year.toString(), formFactor)
        if (detail.runtime != null) {
            val h = detail.runtime / 60; val m = detail.runtime % 60
            InfoLine("Runtime", if (h > 0) "${h}h ${m}m" else "${m}m", formFactor)
        }
        if (detail.rating != null) InfoLine("Rating", "${formatRating(detail.rating)} / 10", formFactor)
        if (detail.network != null) InfoLine("Network", detail.network, formFactor)
        if (detail.authors.isNotEmpty()) InfoLine("Author", detail.authors.joinToString(", "), formFactor)
        if (detail.genres.isNotEmpty()) InfoLine("Genres", detail.genres.take(4).joinToString(" · "), formFactor)
        if (detail.cast.isNotEmpty()) InfoLine("Cast", "${detail.cast.size} credited", formFactor)
    }
}
