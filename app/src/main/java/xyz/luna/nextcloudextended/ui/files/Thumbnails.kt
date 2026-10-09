package xyz.luna.nextcloudextended.ui.files

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.network.CalDavClient
import java.io.File
import java.security.MessageDigest

/**
 * Server-rendered previews (`/index.php/core/preview`) cached on disk and in memory, like the official
 * client's list thumbnails. At most four are fetched at once so scrolling a big folder stays smooth.
 */
class ThumbnailLoader(context: Context, private val client: CalDavClient?) {
    private val directory = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val gate = Semaphore(4)

    suspend fun load(file: NextcloudFile, sizePx: Int): ImageBitmap? {
        val id = file.fileId ?: return null
        if (!file.hasPreview || client == null) return null
        val key = sha("${client.baseUrl}|$id|${file.etag}|$sizePx")
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val cached = File(directory, key)
            try {
                if (!cached.isFile) gate.withPermit { client.files.downloadPreview(id, sizePx, cached) }
                BitmapFactory.decodeFile(cached.absolutePath)?.asImageBitmap()?.also { memory.put(key, it) }
            } catch (e: Exception) {
                cached.delete()
                null
            }
        }
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(40)
}

/** File-type glyph, or the server's preview of the file when it has one. */
@Composable
fun FileThumbnail(
    file: NextcloudFile,
    loader: ThumbnailLoader?,
    size: Dp,
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    cornerRadius: Dp = 6.dp,
    sizePx: Int = 192
) {
    var bitmap by remember(file.path, file.etag) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path, file.etag, loader) {
        bitmap = if (loader != null && file.hasPreview && !file.isDirectory) loader.load(file, sizePx) else null
    }
    Box(modifier.size(size).clip(RoundedCornerShape(cornerRadius)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val kind = fileKind(file)
            Icon(kind.icon, contentDescription = null, tint = kind.tint, modifier = Modifier.size(iconSize))
        }
    }
}
