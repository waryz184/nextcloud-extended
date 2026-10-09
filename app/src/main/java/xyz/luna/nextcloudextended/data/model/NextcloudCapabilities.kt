package xyz.luna.nextcloudextended.data.model

data class NextcloudCapabilities(
    val discovered: Boolean,
    val serverVersion: String?,
    val availableKeys: Set<String>,
    /** `files.chunked_upload.max_size` (bytes). 0 = not advertised. */
    val chunkedUploadMaxSize: Long = 0L,
    /** `files.versioning` — file version history can be browsed/restored. */
    val versioning: Boolean = false,
    /** `files.undelete` — the trash bin is available. */
    val trashbin: Boolean = false,
    /** `files.bigfilechunking` — the server accepts chunked uploads at all. */
    val bigFileChunking: Boolean = true,
    /** `files.comments`. */
    val comments: Boolean = false,
    /** `files_sharing.api_enabled`. */
    val sharingEnabled: Boolean = true,
    /** `files_sharing.public.password.enforced_for.read_only` etc. collapsed: true when links must carry a password. */
    val linkPasswordEnforced: Boolean = false
) {
    fun has(key: String): Boolean = availableKeys.contains(key.lowercase())

    /** Major version number (29 for "29.0.1"), or null when unknown. */
    val majorVersion: Int? get() = serverVersion?.substringBefore('.')?.toIntOrNull()

    companion object {
        fun unavailable() = NextcloudCapabilities(false, null, emptySet())
    }
}
