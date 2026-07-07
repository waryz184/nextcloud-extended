package xyz.luna.nextcloudextended.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.LocalStrings
import java.io.File

// Owns a PdfRenderer and renders pages one at a time, on demand. PdfRenderer allows only one open
// page at a time and is not thread-safe, so every render is serialized behind a Mutex. Rendering
// lazily (page-by-page, off-screen pages recycled) keeps memory bounded — the previous approach
// rendered every page into an ARGB_8888 bitmap up front and OOM'd on large documents.
private class PdfDocument(context: Context, bytes: ByteArray) {
    private val mutex = Mutex()
    private var renderer: PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    private var file: File? = null

    var pageCount: Int = 0
        private set
    var failed: Boolean = false
        private set

    init {
        try {
            val f = File(context.cacheDir, "pdf_view_${System.nanoTime()}.pdf")
            f.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(descriptor)
            file = f; fd = descriptor; renderer = r; pageCount = r.pageCount
        } catch (e: Exception) {
            failed = true
            close()
        }
    }

    suspend fun renderPage(index: Int, width: Int): Bitmap? = mutex.withLock {
        val r = renderer ?: return null
        if (index !in 0 until r.pageCount || width <= 0) return null
        var page: PdfRenderer.Page? = null
        try {
            page = r.openPage(index)
            val ratio = width.toFloat() / page.width
            val height = (page.height * ratio).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (e: Exception) {
            null
        } finally {
            runCatching { page?.close() }
        }
    }

    fun close() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        runCatching { file?.delete() }
        renderer = null; fd = null; file = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    fileName: String,
    pdfBytes: ByteArray,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val listState = rememberLazyListState()

    val document = remember(pdfBytes) { PdfDocument(context, pdfBytes) }
    DisposableEffect(document) { onDispose { document.close() } }

    val screenWidth = remember { context.resources.displayMetrics.widthPixels }
    val pageCount = document.pageCount
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (pageCount > 0) {
                            Text(
                                s.pageOf(currentPage, pageCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, s.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0.25f, 0.25f, 0.25f))
        ) {
            when {
                document.failed -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(s.pdfError, color = Color.White.copy(alpha = 0.75f))
                    }
                }
                pageCount == 0 -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(s.loadingPdf, color = Color.White)
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(pageCount) { index ->
                            PdfPageView(
                                document = document,
                                index = index,
                                width = screenWidth,
                                contentDescription = s.pageOf(index + 1, pageCount)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Renders a single page on demand; recycles its bitmap when the item scrolls out of composition.
@Composable
private fun PdfPageView(document: PdfDocument, index: Int, width: Int, contentDescription: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, index) {
        value = withContext(Dispatchers.IO) { document.renderPage(index, width) }
    }

    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
        DisposableEffect(b) { onDispose { b.recycle() } }
    } else {
        // Placeholder sized to a portrait A4-ish ratio while the page renders.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.414f)
                .background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}
