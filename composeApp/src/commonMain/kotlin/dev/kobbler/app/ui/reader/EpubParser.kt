package dev.kobbler.app.ui.reader

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import dev.kobbler.app.platform.ZipArchive

/**
 * EPUB parser using ksoup for XML/HTML parsing.
 *
 * Reads:
 * 1. META-INF/container.xml → OPF path
 * 2. content.opf → manifest (id→href), spine (reading order), metadata
 * 3. Chapter XHTML from spine order
 */
data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
    val coverImagePath: String?,
    val zip: ZipArchive,
    val basePath: String,
)

data class EpubChapter(
    val title: String?,
    val href: String, // path within ZIP
    val xhtml: String,
)

data class TocEntry(
    val title: String,
    val chapterIndex: Int,
)

class EpubParseException(message: String) : Exception(message)

fun parseEpub(data: ByteArray): EpubBook = parseEpub(ZipArchive(data))

/** Takes ownership of [zip]: the returned [EpubBook] reads images from it on demand. */
fun parseEpub(zip: ZipArchive): EpubBook {
    val zipEntries = zip.entries()

    // 1. Find OPF path from container.xml
    val containerPath = findEntry(zipEntries, "META-INF/container.xml")
        ?: throw EpubParseException("Missing container.xml — not a valid EPUB file")
    val containerXml = zip.readText(containerPath)
        ?: throw EpubParseException("Cannot read container.xml")

    val containerDoc = Ksoup.parse(containerXml, parser = Parser.xmlParser())
    val opfPath = containerDoc.select("rootfile[full-path]").first()?.attr("full-path")
        ?: throw EpubParseException("Missing rootfile in container.xml")
    val basePath = opfPath.substringBeforeLast("/", "").let { if (it.isEmpty()) "" else "$it/" }

    // 2. Parse OPF
    val resolvedOpfPath = findEntry(zipEntries, opfPath)
        ?: throw EpubParseException("Missing OPF file: $opfPath")
    val opfXml = zip.readText(resolvedOpfPath)
        ?: throw EpubParseException("Cannot read OPF file: $opfPath")

    val opfDoc = Ksoup.parse(opfXml, parser = Parser.xmlParser())

    // Metadata
    val title = opfDoc.select("dc\\:title, title").first()?.text() ?: "Untitled"
    val author = opfDoc.select("dc\\:creator, creator").first()?.text() ?: ""

    // Manifest: id → href
    val manifest = mutableMapOf<String, String>()
    for (item in opfDoc.select("manifest > item")) {
        val id = item.attr("id")
        val href = item.attr("href")
        if (id.isNotBlank() && href.isNotBlank()) {
            manifest[id] = decodePercent(href)
        }
    }

    // Find cover image
    val coverMeta = opfDoc.select("meta[name=cover]").first()
    val coverImagePath = coverMeta?.attr("content")?.let { manifest[it] }?.let { "$basePath$it" }

    // Spine: ordered list of manifest IDs
    val spineIds = opfDoc.select("spine > itemref").map { it.attr("idref") }.filter { it.isNotBlank() }

    // 3. Load chapters in spine order
    val rawChapters = spineIds.mapNotNull { id ->
        val href = manifest[id] ?: return@mapNotNull null
        val fullPath = "$basePath$href"
        // Try exact path first, then case-insensitive fallback
        val resolvedPath = findEntry(zipEntries, fullPath) ?: return@mapNotNull null
        val xhtml = zip.readText(resolvedPath) ?: return@mapNotNull null
        val chapterTitle = try {
            Ksoup.parse(xhtml).title().ifBlank { null }
        } catch (_: Exception) { null }
        EpubChapter(title = chapterTitle, href = resolvedPath, xhtml = xhtml)
    }

    // 4. Overlay NCX titles. Calibre and similar tools often emit <title>BookTitle</title>
    //    in every spine XHTML, so the only source of real chapter labels is the NCX.
    val ncxTitles = readNcxTitles(opfDoc, zip, zipEntries, basePath)
    val chapters = applyChapterTitles(rawChapters, ncxTitles, title)

    return EpubBook(
        title = title,
        author = author,
        chapters = chapters,
        coverImagePath = coverImagePath,
        zip = zip,
        basePath = basePath,
    )
}

private fun readNcxTitles(
    opfDoc: com.fleeksoft.ksoup.nodes.Document,
    zip: ZipArchive,
    zipEntries: List<String>,
    basePath: String,
): Map<String, String> {
    val ncxItem = opfDoc.select("manifest > item[media-type=application/x-dtbncx+xml]").first()
        ?: opfDoc.select("manifest > item#ncx").first()
        ?: return emptyMap()
    val ncxHref = ncxItem.attr("href").ifBlank { return emptyMap() }
    val ncxPath = findEntry(zipEntries, "$basePath${decodePercent(ncxHref)}") ?: return emptyMap()
    val ncxXml = zip.readText(ncxPath) ?: return emptyMap()
    return parseNcxTitlesFromXml(ncxXml, basePath)
}

/** Parse NCX navMap into href→title. Last navPoint with the same href wins (Calibre emits
 *  duplicates per chapter, e.g. "PART 1" then "Watershed Moment"; the second is more useful). */
internal fun parseNcxTitlesFromXml(ncxXml: String, basePath: String): Map<String, String> {
    val ncxDoc = Ksoup.parse(ncxXml, parser = Parser.xmlParser())
    val titles = LinkedHashMap<String, String>()
    for (navPoint in ncxDoc.select("navPoint")) {
        val title = navPoint.select("navLabel > text").first()?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: continue
        val src = navPoint.select("content[src]").first()?.attr("src")?.takeIf { it.isNotBlank() }
            ?: continue
        val href = "$basePath${decodePercent(src).substringBefore("#")}"
        titles[href] = title
    }
    return titles
}

/** Overlay chapter titles using the NCX map, with sticky inheritance for split spine items
 *  that don't have their own NCX entry (e.g. continuation files). Pre-NCX spine items keep
 *  a distinct file <title>; if their <title> is just the book title it returns null so the
 *  reader's top bar can fall back to the book title rather than echoing it. */
internal fun applyChapterTitles(
    chapters: List<EpubChapter>,
    ncxTitles: Map<String, String>,
    bookTitle: String,
): List<EpubChapter> {
    var sticky: String? = null
    val normalizedNcx = ncxTitles.mapKeys { it.key.lowercase() }
    val trimmedBookTitle = bookTitle.trim()
    return chapters.map { ch ->
        val ncx = normalizedNcx[ch.href.lowercase()]
        val title = when {
            ncx != null -> { sticky = ncx; ncx }
            sticky != null -> sticky
            !ch.title.isNullOrBlank() && ch.title.trim() != trimmedBookTitle -> ch.title
            else -> null
        }
        if (title == ch.title) ch else ch.copy(title = title)
    }
}

/** Extract TOC entries from NCX document. */
fun extractToc(book: EpubBook): List<TocEntry> {
    // Find NCX file in the OPF directory
    val opfPath = findEntry(book.zip.entries(), "${book.basePath}content.opf")
        ?: findEntry(book.zip.entries(), "${book.basePath}package.opf")
    val opfXml = opfPath?.let { book.zip.readText(it) } ?: return emptyList()
    val opfDoc = Ksoup.parse(opfXml, parser = Parser.xmlParser())

    // Find NCX href from manifest
    val ncxItem = opfDoc.select("manifest > item[media-type=application/x-dtbncx+xml]").first()
        ?: opfDoc.select("manifest > item#ncx").first()
    val ncxHref = ncxItem?.attr("href") ?: return emptyList()
    val ncxPath = findEntry(book.zip.entries(), "${book.basePath}${decodePercent(ncxHref)}")
        ?: return emptyList()
    val ncxXml = book.zip.readText(ncxPath) ?: return emptyList()

    val ncxDoc = Ksoup.parse(ncxXml, parser = Parser.xmlParser())
    val entries = mutableListOf<TocEntry>()

    for (navPoint in ncxDoc.select("navPoint")) {
        val title = navPoint.select("navLabel > text").first()?.text()?.trim() ?: continue
        val src = decodePercent(navPoint.select("content[src]").first()?.attr("src") ?: continue)
            .substringBefore("#") // Strip fragment
        val fullPath = "${book.basePath}$src"
        val idx = book.chapters.indexOfFirst { it.href.equals(fullPath, ignoreCase = true) }
        if (idx >= 0) {
            entries.add(TocEntry(title, idx))
        }
    }
    return entries
}

// --- Utilities ---

/** Find a ZIP entry path case-insensitively. */
private fun findEntry(entries: List<String>, path: String): String? {
    // Try exact match first
    if (entries.contains(path)) return path
    // Case-insensitive fallback
    val lower = path.lowercase()
    return entries.firstOrNull { it.lowercase() == lower }
}

private fun decodePercent(encoded: String): String {
    if ('%' !in encoded) return encoded
    val sb = StringBuilder(encoded.length)
    var i = 0
    while (i < encoded.length) {
        if (encoded[i] == '%' && i + 2 < encoded.length) {
            val hex = encoded.substring(i + 1, i + 3)
            val code = hex.toIntOrNull(16)
            if (code != null) {
                sb.append(code.toChar())
                i += 3
                continue
            }
        }
        sb.append(encoded[i])
        i++
    }
    return sb.toString()
}
