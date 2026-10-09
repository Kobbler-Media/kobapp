package dev.kobbler.app.ui.reader

internal fun resolveImagePath(src: String, chapterHref: String, epub: EpubBook): ByteArray? {
    val resolved = resolveEpubPath(src, chapterHref)
    return epub.zip.readBytes(resolved) ?: run {
        val lower = resolved.lowercase()
        epub.zip.entries().firstOrNull { it.lowercase() == lower }?.let { epub.zip.readBytes(it) }
    }
}

internal fun resolveCssPath(href: String, chapterHref: String, epub: EpubBook): String? {
    val resolved = resolveEpubPath(href, chapterHref)
    return epub.zip.readText(resolved) ?: run {
        val lower = resolved.lowercase()
        epub.zip.entries().firstOrNull { it.lowercase() == lower }?.let { epub.zip.readText(it) }
    }
}

private fun resolveEpubPath(path: String, chapterHref: String): String {
    val chapterDir = chapterHref.substringBeforeLast("/", "")
    val resolved = when {
        path.startsWith("/") -> path.removePrefix("/")
        chapterDir.isNotEmpty() -> "$chapterDir/$path"
        else -> path
    }
    return normalizePath(resolved)
}

private fun normalizePath(path: String): String {
    val result = mutableListOf<String>()
    for (part in path.split("/")) {
        when (part) {
            ".", "" -> {}
            ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
            else -> result.add(part)
        }
    }
    return result.joinToString("/")
}
