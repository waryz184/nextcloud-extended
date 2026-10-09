package xyz.luna.nextcloudextended.data.network

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * Namespace-aware WebDAV `multistatus` reader.
 *
 * The previous regex based parsing only understood the literal `d:` prefix, broke on CDATA and
 * attribute order changes, and decoded hrefs with `URLDecoder` (which turns a literal `+` into a
 * space). This reader is prefix agnostic, decodes entities exactly once, and only keeps properties
 * from `propstat` blocks whose status is 2xx.
 */
object DavNs {
    const val DAV = "DAV:"
    const val OC = "http://owncloud.org/ns"
    const val NC = "http://nextcloud.org/ns"
    const val CALDAV = "urn:ietf:params:xml:ns:caldav"
    const val CARDDAV = "urn:ietf:params:xml:ns:carddav"
    const val APPLE = "http://apple.com/ns/ical/"
    const val SABRE = "http://sabredav.org/ns"

    fun key(ns: String, local: String) = "{$ns}$local"
}

class DavProp(
    /** All descendant character data, outer whitespace trimmed. */
    val text: String,
    /** Qualified names (`{ns}local`) of direct child elements, e.g. `{DAV:}collection`. */
    val children: List<String>,
    /** Attribute `name` of direct children grouped by child local name (e.g. `comp` → VEVENT, VTODO). */
    val childNames: Map<String, List<String>>
)

class DavResponse(
    /** Canonical, percent-decoded, sub-path-stripped href as used all over the app. */
    val href: String,
    /** Response level status (`<d:status>` directly under `<d:response>`), when present. */
    val status: Int?,
    val props: Map<String, DavProp>
) {
    fun prop(ns: String, local: String): DavProp? = props[DavNs.key(ns, local)]
    fun text(ns: String, local: String): String? = prop(ns, local)?.text
    fun hasChild(ns: String, local: String, childNs: String, childLocal: String): Boolean =
        prop(ns, local)?.children?.contains(DavNs.key(childNs, childLocal)) == true

    val isCollection: Boolean
        get() = hasChild(DavNs.DAV, "resourcetype", DavNs.DAV, "collection") || href.endsWith("/")
}

object DavMultistatus {
    /** Refuse absurd documents instead of exhausting memory on a hostile or broken proxy. */
    const val MAX_BYTES = 128L * 1024 * 1024

    fun parse(xml: String, basePath: String = ""): List<DavResponse> =
        parse(InputSource(StringReader(xml)), basePath)

    fun parse(stream: InputStream, basePath: String = ""): List<DavResponse> =
        parse(InputSource(LimitedInputStream(stream, MAX_BYTES)), basePath)

    private fun parse(source: InputSource, basePath: String): List<DavResponse> {
        val handler = Handler(basePath)
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            // Never fetch a DTD / external entity referenced by the (untrusted) document.
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
            reader.parse(source)
        } catch (e: SAXException) {
            throw UnexpectedResponseException("Malformed WebDAV response: ${e.message}", e)
        }
        return handler.responses
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        private fun account(n: Int): Int {
            if (n > 0) {
                count += n
                if (count > limit) throw IOException("Response is larger than $limit bytes")
            }
            return n
        }
        override fun read(): Int { val b = super.read(); if (b >= 0) account(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int = account(super.read(b, off, len))
    }

    private class Handler(private val basePath: String) : DefaultHandler() {
        val responses = mutableListOf<DavResponse>()

        private var depth = 0
        private var inResponse = false
        private var rawHref: String? = null
        private var responseStatus: Int? = null
        private var responseProps = LinkedHashMap<String, DavProp>()

        private var inPropstat = false
        private var propstatStatus: Int? = null
        private var propstatProps = LinkedHashMap<String, DavProp>()

        private var propDepth = -1          // depth of the <prop> element
        private var currentKey: String? = null
        private var currentText = StringBuilder()
        private var currentChildren = mutableListOf<String>()
        private var currentChildNames = LinkedHashMap<String, MutableList<String>>()

        private var captureHref = false
        private var captureStatus = false
        private val buffer = StringBuilder()

        override fun startElement(uri: String?, localName: String, qName: String?, attrs: Attributes) {
            depth++
            val ns = uri.orEmpty()
            when {
                ns == DavNs.DAV && localName == "response" && !inResponse -> {
                    inResponse = true
                    rawHref = null; responseStatus = null; responseProps = LinkedHashMap()
                }
                inResponse && ns == DavNs.DAV && localName == "href" && !inPropstat && propDepth < 0 -> {
                    captureHref = true; buffer.setLength(0)
                }
                inResponse && ns == DavNs.DAV && localName == "propstat" && !inPropstat -> {
                    inPropstat = true; propstatStatus = null; propstatProps = LinkedHashMap()
                }
                inPropstat && ns == DavNs.DAV && localName == "status" && propDepth < 0 -> {
                    captureStatus = true; buffer.setLength(0)
                }
                inPropstat && ns == DavNs.DAV && localName == "prop" && propDepth < 0 -> {
                    propDepth = depth
                }
                inResponse && !inPropstat && ns == DavNs.DAV && localName == "status" -> {
                    captureStatus = true; buffer.setLength(0)
                }
                propDepth >= 0 && depth == propDepth + 1 -> {
                    currentKey = DavNs.key(ns, localName)
                    currentText = StringBuilder()
                    currentChildren = mutableListOf()
                    currentChildNames = LinkedHashMap()
                }
                propDepth >= 0 && depth == propDepth + 2 && currentKey != null -> {
                    currentChildren.add(DavNs.key(ns, localName))
                    attrs.getValue("name")?.let { currentChildNames.getOrPut(localName) { mutableListOf() }.add(it) }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (captureHref || captureStatus) buffer.append(ch, start, length)
            else if (currentKey != null) currentText.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String, qName: String?) {
            val ns = uri.orEmpty()
            when {
                captureHref && ns == DavNs.DAV && localName == "href" -> {
                    rawHref = buffer.toString().trim(); captureHref = false
                }
                captureStatus && ns == DavNs.DAV && localName == "status" -> {
                    val code = parseStatusLine(buffer.toString())
                    if (inPropstat) propstatStatus = code else responseStatus = code
                    captureStatus = false
                }
                propDepth >= 0 && depth == propDepth + 1 && currentKey != null -> {
                    propstatProps[currentKey!!] = DavProp(
                        currentText.toString().trim(), currentChildren.toList(),
                        currentChildNames.mapValues { it.value.toList() }
                    )
                    currentKey = null
                }
                propDepth >= 0 && depth == propDepth && ns == DavNs.DAV && localName == "prop" -> propDepth = -1
                inPropstat && ns == DavNs.DAV && localName == "propstat" -> {
                    val code = propstatStatus
                    if (code == null || code in 200..299) responseProps.putAll(propstatProps)
                    inPropstat = false
                }
                inResponse && ns == DavNs.DAV && localName == "response" -> {
                    val href = rawHref
                    if (!href.isNullOrEmpty()) {
                        responses.add(DavResponse(canonicalHref(href, basePath), responseStatus, responseProps))
                    }
                    inResponse = false
                }
            }
            depth--
        }

        override fun error(e: org.xml.sax.SAXParseException) { throw e }
        override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
    }

    private fun parseStatusLine(line: String): Int? =
        Regex("""\b(\d{3})\b""").find(line)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * Turns an href from a multistatus into the app-wide form: absolute path, percent-decoded (a literal
 * `+` is kept), and without the server's sub-path (`/nextcloud`) so the same `/remote.php/dav/...`
 * convention holds for root installs and sub-folder installs alike.
 */
fun canonicalHref(rawHref: String, basePath: String = ""): String {
    var path = rawHref.trim()
    val schemeEnd = path.indexOf("://")
    if (schemeEnd in 1..10) {
        val pathStart = path.indexOf('/', schemeEnd + 3)
        path = if (pathStart < 0) "/" else path.substring(pathStart)
    }
    path = path.substringBefore('#').substringBefore('?')
    path = percentDecode(path)
    val base = basePath.trimEnd('/')
    if (base.isNotEmpty() && (path == base || path.startsWith("$base/"))) path = path.removePrefix(base).ifEmpty { "/" }
    return path
}

/** RFC 3986 percent-decoding to UTF-8. Unlike `URLDecoder`, `+` is a plain character. */
fun percentDecode(value: String): String {
    if (value.indexOf('%') < 0) return value
    val out = java.io.ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hi = Character.digit(value[i + 1], 16)
            val lo = Character.digit(value[i + 2], 16)
            if (hi >= 0 && lo >= 0) {
                out.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        val codePoint = value.codePointAt(i)
        val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        out.write(bytes, 0, bytes.size)
        i += Character.charCount(codePoint)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}
