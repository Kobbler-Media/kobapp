package dev.kobbler.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor

val LocalFormFactor = staticCompositionLocalOf { FormFactor.Desktop }

val KobappDimensDefault = KobappDimens()
val KobappShapesDefault = KobappShapes()

val cardShape = RoundedCornerShape(8.dp)

fun ratingColor(rating: Float, outOf: Int, colors: KobappColors = KobappColorsDefault): Color = when {
    rating >= outOf * 0.7f -> colors.ok
    rating >= outOf * 0.5f -> colors.warn
    else -> colors.err
}

private fun materialTypographyFrom(text: KobappTextStyles): Typography = Typography(
    displayLarge = text.display,
    displayMedium = text.display.copy(
        fontSize = text.display.fontSize * 0.8f,
        lineHeight = text.display.lineHeight * 0.8f,
    ),
    displaySmall = text.h1,
    headlineLarge = text.h1,
    headlineMedium = text.h2,
    headlineSmall = text.h3,
    titleLarge = text.h3,
    titleMedium = TextStyle(
        fontFamily = text.h3.fontFamily,
        fontWeight = text.h3.fontWeight,
        fontSize = text.body.fontSize,
        lineHeight = text.body.lineHeight,
    ),
    titleSmall = text.bodySm,
    bodyLarge = text.body,
    bodyMedium = text.bodySm,
    bodySmall = text.caption,
    labelLarge = text.label,
    labelMedium = text.label,
    labelSmall = text.caption,
)

private fun materialColorSchemeFrom(c: KobappColors) = darkColorScheme(
    background = c.bg,
    surface = c.elev1,
    surfaceVariant = c.elev2,
    onBackground = c.textHi,
    onSurface = c.textHi,
    onSurfaceVariant = c.textMd,
    outline = c.line,
    outlineVariant = c.lineSoft,
    primary = c.accHome,
    onPrimary = c.bg,
    secondary = c.accAudio,
    onSecondary = c.bg,
    error = c.err,
    onError = c.bg,
)

@Composable
fun KobappTheme(
    formFactor: FormFactor = FormFactor.Phone,
    section: Section = Section.Home,
    colors: KobappColors = KobappColorsDefault,
    content: @Composable () -> Unit,
) {
    val type = if (formFactor == FormFactor.TV) KobappTextStylesTv else KobappTextStylesDefault
    val dimens = KobappDimensDefault
    val shapes = KobappShapesDefault
    val colorScheme = remember(colors) { materialColorSchemeFrom(colors) }
    val typography = remember(type) { materialTypographyFrom(type) }

    CompositionLocalProvider(
        LocalFormFactor provides formFactor,
        LocalKobappColors provides colors,
        LocalKobappType provides type,
        LocalKobappDimens provides dimens,
        LocalKobappShapes provides shapes,
        LocalSection provides section,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
        ) {
            content()
        }
    }
}

object KobappTheme {
    val colors: KobappColors
        @Composable @ReadOnlyComposable get() = LocalKobappColors.current
    val type: KobappTextStyles
        @Composable @ReadOnlyComposable get() = LocalKobappType.current
    val dimens: KobappDimens
        @Composable @ReadOnlyComposable get() = LocalKobappDimens.current
    val shapes: KobappShapes
        @Composable @ReadOnlyComposable get() = LocalKobappShapes.current
    val section: Section
        @Composable @ReadOnlyComposable get() = LocalSection.current
    val accent: Color
        @Composable @ReadOnlyComposable get() = LocalKobappColors.current.accentFor(LocalSection.current)

    // Detail titles are set in the two largest tokens, which are sized for a desktop window;
    // at 57sp a 360dp phone fits about six characters per line, and a 960dp TV reads it as
    // zoomed in. Both step down a tier instead of rescaling the M3 baseline.
    val displayTitle: TextStyle
        @Composable @ReadOnlyComposable get() = LocalKobappType.current.let {
            if (LocalFormFactor.current == FormFactor.Desktop) it.display else it.h1
        }
    val heroTitle: TextStyle
        @Composable @ReadOnlyComposable get() = LocalKobappType.current.let {
            when (LocalFormFactor.current) {
                FormFactor.Desktop -> it.hero
                FormFactor.TV -> it.display.copy(
                    fontSize = it.display.fontSize * 0.8f,
                    lineHeight = it.display.lineHeight * 0.8f,
                )
                FormFactor.Phone -> it.h1
            }
        }
}
