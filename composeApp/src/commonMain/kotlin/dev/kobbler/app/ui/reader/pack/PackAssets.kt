package dev.kobbler.app.ui.reader.pack

fun Block.assetSources(): List<String> = buildList {
    image?.src?.let(::add)
    fixed?.image?.src?.let(::add)
    table?.rows?.forEach { row -> row.cells.forEach { cell -> cell.blocks.forEach { addAll(it.assetSources()) } } }
}
