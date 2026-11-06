package dev.kobbler.app.ui.reader.layout

import androidx.compose.runtime.Immutable

enum class StyleAspect(val label: String, val key: String, val movesText: Boolean = true) {
    Typeface("Typeface", "typeface"),
    Sizes("Text sizes", "sizes"),
    Colours("Colours", "colours", movesText = false),
    Spacing("Spacing & indents", "spacing"),
    Decorations("Borders & decorations", "decorations"),
    Alignment("Alignment", "alignment"),
}

/** Which parts of a book's own styling the reader keeps; the rest follow the reader's settings. */
@Immutable
data class BookStyle(val aspects: Set<StyleAspect> = StyleAspect.entries.toSet()) {
    fun uses(aspect: StyleAspect): Boolean = aspect in aspects

    fun with(aspect: StyleAspect, book: Boolean): BookStyle = copy(aspects = if (book) aspects + aspect else aspects - aspect)

    val typeface: Boolean get() = uses(StyleAspect.Typeface)
    val sizes: Boolean get() = uses(StyleAspect.Sizes)
    val colours: Boolean get() = uses(StyleAspect.Colours)
    val spacing: Boolean get() = uses(StyleAspect.Spacing)
    val decorations: Boolean get() = uses(StyleAspect.Decorations)
    val alignment: Boolean get() = uses(StyleAspect.Alignment)

    val preset: StylePreset
        get() = when (aspects.size) {
            StyleAspect.entries.size -> StylePreset.Book
            0 -> StylePreset.Mine
            else -> StylePreset.Custom
        }

    companion object {
        val Book = BookStyle()
        val Mine = BookStyle(emptySet())
    }
}

enum class StylePreset(val label: String) { Book("Book's style"), Mine("My style"), Custom("Custom") }
