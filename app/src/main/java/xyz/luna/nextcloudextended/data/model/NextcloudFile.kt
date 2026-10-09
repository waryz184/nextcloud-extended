package xyz.luna.nextcloudextended.data.model

data class NextcloudFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: String,
    val etag: String? = null,
    /** `oc:fileid` — stable across renames/moves, needed for previews, comments, activity… */
    val fileId: String? = null,
    /** `oc:permissions`, e.g. "RGDNVW" (R share, G read, D delete, N rename, V move, W write, C create). */
    val permissions: String? = null,
    val favorite: Boolean = false,
    val mimeType: String? = null,
    val hasPreview: Boolean = false,
    val ownerName: String? = null,
    /** `nc:lock` held by someone (files_lock app). */
    val locked: Boolean = false
) {
    val canDelete: Boolean get() = permissions == null || 'D' in permissions
    val canRename: Boolean get() = permissions == null || 'N' in permissions
    val canWrite: Boolean get() = permissions == null || 'W' in permissions || 'C' in permissions
    val canShare: Boolean get() = permissions == null || 'R' in permissions
}
