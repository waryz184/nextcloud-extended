package xyz.luna.nextcloudextended.data.network

import xyz.luna.nextcloudextended.data.model.LabeledValue
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.PostalAddress
import java.util.UUID

/** vCard 3.0 <-> [NextcloudContact]. Unmodelled properties of an existing card survive an edit. */
internal object Vcard {
    // Rebuilds a vCard 3.0 from the managed fields while carrying over any property we don't model
    // (NICKNAME, URL, IMPP, X-*…) from the original card so an edit doesn't drop it.
    fun build(contact: NextcloudContact): String {
        val managed = setOf("BEGIN", "END", "VERSION", "UID", "FN", "N", "TEL", "EMAIL", "ORG", "REV",
                            "ADR", "BDAY", "PHOTO", "CATEGORIES")
        val preserved = contact.rawVcard
            ?.lineSequence()
            ?.filter { line ->
                val prop = line.substringBefore(':').substringBefore(';').trim().uppercase()
                prop.isNotEmpty() && prop !in managed
            }
            ?.map { it.trimEnd('\r') }
            ?.toList().orEmpty()

        val name = contact.fullName.trim()
        val tokens = name.split(" ").filter { it.isNotBlank() }
        val family = if (tokens.size > 1) tokens.last() else ""
        val given = if (tokens.size > 1) tokens.dropLast(1).joinToString(" ") else name

        fun typeParam(type: String) = if (type.isNotBlank()) ";TYPE=$type" else ""

        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("UID:${contact.uid}")
            appendLine("FN:${escapeIcsText(name)}")
            appendLine("N:${escapeIcsText(family)};${escapeIcsText(given)};;;")
            contact.phones.filter { it.value.isNotBlank() }.forEach {
                appendLine("TEL${typeParam(it.type)}:${escapeIcsText(it.value.trim())}")
            }
            contact.emails.filter { it.value.isNotBlank() }.forEach {
                appendLine("EMAIL${typeParam(it.type)}:${escapeIcsText(it.value.trim())}")
            }
            contact.organization?.takeIf { it.isNotBlank() }?.let { appendLine("ORG:${escapeIcsText(it.trim())}") }
            contact.addresses.filter { !it.isEmpty }.forEach { a ->
                // ADR components: po-box ; ext ; street ; locality ; region ; postal-code ; country
                appendLine("ADR${typeParam(a.type)}:;;${escapeIcsText(a.street.trim())};${escapeIcsText(a.city.trim())};;${escapeIcsText(a.postalCode.trim())};${escapeIcsText(a.country.trim())}")
            }
            contact.birthday?.takeIf { it.isNotBlank() }?.let { appendLine("BDAY:$it") }
            contact.categories.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { cats ->
                appendLine("CATEGORIES:${cats.joinToString(",") { escapeIcsText(it.trim()) }}")
            }
            contact.photoBase64?.takeIf { it.isNotBlank() }?.let { photo ->
                val type = contact.photoMimeType?.substringAfter("/")?.uppercase()?.takeIf { it.isNotBlank() } ?: "JPEG"
                appendLine("PHOTO;ENCODING=b;TYPE=$type:$photo")
            }
            preserved.forEach { appendLine(it) }
            append("END:VCARD")
        }
    }

    fun parse(vcard: String, addressBookHref: String, href: String, etag: String = ""): NextcloudContact {
        val unfolded = vcard.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "")
        fun rawFirst(prop: String) = Regex("(?m)^$prop(?:;[^:\\r\\n]*)?:(.*)$").find(unfolded)?.groupValues?.get(1)?.trimEnd('\r')

        val uid = rawFirst("UID")?.trim()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val fn = rawFirst("FN")?.let { unescapeIcsText(it).trim() }
        val nameFromN = rawFirst("N")?.let { n ->
            val parts = splitVcardComponents(n, ';')
            listOf(parts.getOrNull(1) ?: "", parts.getOrNull(0) ?: "").filter { it.isNotBlank() }.joinToString(" ")
        }
        val fullName = fn?.takeIf { it.isNotBlank() } ?: nameFromN?.takeIf { it.isNotBlank() } ?: "?"

        val phones = vcardEntries(unfolded, "TEL")
            .map { (params, value) -> LabeledValue(unescapeIcsText(value).trim(), extractType(params)) }
            .filter { it.value.isNotBlank() }
        val emails = vcardEntries(unfolded, "EMAIL")
            .map { (params, value) -> LabeledValue(unescapeIcsText(value).trim(), extractType(params)) }
            .filter { it.value.isNotBlank() }

        val org = rawFirst("ORG")?.let { splitVcardComponents(it, ';').filter { c -> c.isNotBlank() }.joinToString(" · ") }
            ?.takeIf { it.isNotBlank() }

        val addresses = vcardEntries(unfolded, "ADR").mapNotNull { (params, value) ->
            val c = splitVcardComponents(value, ';')
            PostalAddress(
                type = extractType(params),
                street = c.getOrNull(2) ?: "",
                city = c.getOrNull(3) ?: "",
                postalCode = c.getOrNull(5) ?: "",
                country = c.getOrNull(6) ?: ""
            ).takeUnless { it.isEmpty }
        }

        val birthday = rawFirst("BDAY")?.let { normalizeBday(it.trim()) }
        val categories = rawFirst("CATEGORIES")?.let { splitVcardComponents(it, ',') }?.filter { it.isNotBlank() }.orEmpty()
        val (photo, photoMime) = parsePhoto(unfolded)

        return NextcloudContact(uid, fullName, phones, emails, org, addresses, birthday, photo, photoMime, categories, addressBookHref, href, etag, unfolded)
    }

    // Returns (paramsString, rawValue) for every line of the given vCard property.
    fun vcardEntries(unfolded: String, prop: String): List<Pair<String, String>> =
        Regex("(?m)^$prop((?:;[^:\\r\\n]*)?):(.*)$").findAll(unfolded)
            .map { Pair(it.groupValues[1], it.groupValues[2].trimEnd('\r')) }
            .toList()

    // Extracts (base64, mimeType) from the PHOTO property — handles vCard 3.0 inline and 4.0 data URI.
    fun parsePhoto(unfolded: String): Pair<String?, String?> {
        val (params, value) = vcardEntries(unfolded, "PHOTO").firstOrNull() ?: return null to null
        val v = value.trim()
        if (v.startsWith("data:", ignoreCase = true)) {
            val mime = Regex("data:([^;]+)", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)
            val b64 = v.substringAfter("base64,", "").ifBlank { return null to null }
            return b64 to mime
        }
        val isBase64 = params.contains("ENCODING=b", true) || params.contains("BASE64", true)
        if (!isBase64 || v.isBlank()) return null to null
        val typeTok = Regex("TYPE=([^;:]*)", RegexOption.IGNORE_CASE).find(params)?.groupValues?.get(1)?.trim()
        val mime = typeTok?.takeIf { it.isNotBlank() }?.let { "image/${it.lowercase()}" }
        return v to mime
    }
}
