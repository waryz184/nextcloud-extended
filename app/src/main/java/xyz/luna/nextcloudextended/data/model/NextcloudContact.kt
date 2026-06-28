package xyz.luna.nextcloudextended.data.model

// A value with a vCard TYPE token (CELL, HOME, WORK, FAX, OTHER…). "" = unspecified.
data class LabeledValue(
    val value: String,
    val type: String = ""
)

data class PostalAddress(
    val type: String = "",
    val street: String = "",
    val city: String = "",
    val postalCode: String = "",
    val country: String = ""
) {
    val isEmpty: Boolean get() = street.isBlank() && city.isBlank() && postalCode.isBlank() && country.isBlank()
}

data class NextcloudContact(
    val uid: String,
    val fullName: String,
    val phones: List<LabeledValue>,
    val emails: List<LabeledValue>,
    val organization: String?,
    val addresses: List<PostalAddress> = emptyList(),
    val birthday: String? = null,            // "YYYY-MM-DD"
    val photoBase64: String? = null,         // base64 image data (no "data:" prefix), or null
    val photoMimeType: String? = null,       // e.g. "image/jpeg"
    val categories: List<String> = emptyList(),  // Nextcloud contact groups
    val addressBookHref: String,
    val href: String = "",                   // full path to the .vcf on the server ("" for a new contact)
    val rawVcard: String? = null             // unfolded source vCard, so unmanaged properties survive an edit
)
