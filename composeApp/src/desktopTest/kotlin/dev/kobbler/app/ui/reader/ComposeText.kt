package dev.kobbler.app.ui.reader

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver

/** Compose 1.13 measures text only once Skiko is registered, which a window does for the app but nothing does for a test. */
@OptIn(InternalComposeUiApi::class)
internal object ComposeText {
    init {
        registerSkikoComposeImplementation()
    }

    fun resolver(): FontFamily.Resolver = createFontFamilyResolver()
}
