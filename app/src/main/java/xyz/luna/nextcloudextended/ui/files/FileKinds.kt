package xyz.luna.nextcloudextended.ui.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import xyz.luna.nextcloudextended.data.model.NextcloudFile

/** The file-type glyphs the official client shows in front of every row, with their brand colours. */
enum class FileKind(val icon: ImageVector, val tint: Color) {
    FOLDER(Icons.Default.Folder, Color(0xFF0082C9)),
    IMAGE(Icons.Default.Image, Color(0xFF8E24AA)),
    VIDEO(Icons.Default.VideoFile, Color(0xFF6D4C41)),
    AUDIO(Icons.Default.AudioFile, Color(0xFFD81B60)),
    PDF(Icons.Default.PictureAsPdf, Color(0xFFE53935)),
    DOCUMENT(Icons.Default.Article, Color(0xFF1E88E5)),
    SPREADSHEET(Icons.Default.TableChart, Color(0xFF2E7D32)),
    PRESENTATION(Icons.Default.Slideshow, Color(0xFFEF6C00)),
    ARCHIVE(Icons.Default.FolderZip, Color(0xFF757575)),
    TEXT(Icons.Default.Article, Color(0xFF546E7A)),
    OTHER(Icons.Default.InsertDriveFile, Color(0xFF757575))
}

fun fileKind(file: NextcloudFile): FileKind {
    if (file.isDirectory) return FileKind.FOLDER
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val mime = file.mimeType.orEmpty().lowercase()
    return when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg") -> FileKind.IMAGE
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp") -> FileKind.VIDEO
        mime.startsWith("audio/") || ext in setOf("mp3", "ogg", "flac", "wav", "m4a", "opus", "aac") -> FileKind.AUDIO
        mime == "application/pdf" || ext == "pdf" -> FileKind.PDF
        ext in setOf("doc", "docx", "odt", "rtf") -> FileKind.DOCUMENT
        ext in setOf("xls", "xlsx", "ods", "csv") -> FileKind.SPREADSHEET
        ext in setOf("ppt", "pptx", "odp") -> FileKind.PRESENTATION
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> FileKind.ARCHIVE
        mime.startsWith("text/") || ext in setOf("txt", "md", "json", "xml", "log", "html") -> FileKind.TEXT
        else -> FileKind.OTHER
    }
}

enum class FileSort(val key: String) {
    NAME_ASC("name_asc"), NAME_DESC("name_desc"),
    DATE_NEWEST("date_new"), DATE_OLDEST("date_old"),
    SIZE_BIGGEST("size_big"), SIZE_SMALLEST("size_small");

    /** Sorted the way the official client does: folders always first. */
    fun apply(files: List<NextcloudFile>): List<NextcloudFile> {
        val base: Comparator<NextcloudFile> = when (this) {
            NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            DATE_NEWEST -> compareByDescending { modifiedMillis(it) }
            DATE_OLDEST -> compareBy { modifiedMillis(it) }
            SIZE_BIGGEST -> compareByDescending { it.size }
            SIZE_SMALLEST -> compareBy { it.size }
        }
        return files.sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.then(base))
    }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NAME_ASC
    }
}

fun modifiedMillis(file: NextcloudFile): Long =
    xyz.luna.nextcloudextended.data.network.FileApi.parseHttpDate(file.lastModified) ?: 0L
