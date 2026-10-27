package dev.kobbler.app.ui.reader

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun epubZip(opf: String, files: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        fun put(path: String, data: ByteArray) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(data)
            zip.closeEntry()
        }
        put(
            "META-INF/container.xml",
            """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".encodeToByteArray(),
        )
        put("OEBPS/content.opf", opf.encodeToByteArray())
        for ((path, data) in files) put("OEBPS/$path", data)
    }
    return out.toByteArray()
}

internal fun opf(ids: List<String>, metadata: String = "", spineAttrs: String = ""): String = buildString {
    append("<package><metadata><dc:title>Test Book</dc:title>$metadata</metadata><manifest>")
    ids.forEach { append("""<item id="$it" href="$it.xhtml" media-type="application/xhtml+xml"/>""") }
    append("</manifest><spine $spineAttrs>")
    ids.forEach { append("""<itemref idref="$it"/>""") }
    append("</spine></package>")
}
