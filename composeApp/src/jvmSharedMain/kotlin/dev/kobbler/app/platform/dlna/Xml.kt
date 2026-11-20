package dev.kobbler.app.platform.dlna

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

// A renderer is a device on the LAN answering whatever it likes: no DTDs, no entities.
internal fun parseXml(xml: String): Element? = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }
    factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim()))).documentElement
}.getOrNull()

internal val Node.name: String get() = localName ?: nodeName.substringAfter(':')

internal fun Element.children(name: String? = null): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .filter { name == null || it.name == name }

internal fun Element.child(name: String): Element? = children(name).firstOrNull()

internal fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

internal fun Element.descendants(name: String): List<Element> =
    children().flatMap { (if (it.name == name) listOf(it) else emptyList()) + it.descendants(name) }

internal fun String.xmlEscaped(): String = buildString(length) {
    for (c in this@xmlEscaped) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}
