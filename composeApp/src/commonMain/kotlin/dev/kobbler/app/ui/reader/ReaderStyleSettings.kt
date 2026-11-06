package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.layout.BookStyle
import dev.kobbler.app.ui.reader.layout.StyleAspect

private const val LegacyStyleMode = "reader_style_mode"
private const val Book = "book"
private const val Mine = "mine"

private val StyleAspect.settingKey: String get() = "reader_style_$key"

/** Reads each aspect, falling back for one never saved to the old all-or-nothing choice it replaced. */
internal fun loadBookStyle(get: (String) -> String?): BookStyle {
    val legacyBook = get(LegacyStyleMode) != "Reader"
    return BookStyle(StyleAspect.entries.filterTo(mutableSetOf()) { aspect ->
        when (get(aspect.settingKey)) {
            Book -> true
            Mine -> false
            else -> legacyBook
        }
    })
}

internal fun saveBookStyle(style: BookStyle, put: (String, String) -> Unit) {
    for (aspect in StyleAspect.entries) put(aspect.settingKey, if (style.uses(aspect)) Book else Mine)
}
