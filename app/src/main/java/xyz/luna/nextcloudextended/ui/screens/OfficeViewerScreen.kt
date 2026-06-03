package xyz.luna.nextcloudextended.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xwpf.usermodel.XWPFDocument
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.OfficeViewerType
import java.io.ByteArrayInputStream

// ── Domain models ─────────────────────────────────────────────────────────────

private sealed class OfficeContent {
    data class Spreadsheet(val sheets: List<SheetData>) : OfficeContent()
    data class Document(val paragraphs: List<ParaData>) : OfficeContent()
    data class Presentation(val slides: List<SlideData>) : OfficeContent()
    data class Csv(val rows: List<List<String>>) : OfficeContent()
    data class Error(val message: String) : OfficeContent()
}

private data class CellData(val value: String)
private data class RowData(val cells: List<CellData>)
private data class SheetData(val name: String, val rows: List<RowData>)
private data class ParaData(val text: String, val isHeading: Boolean, val isBullet: Boolean)
private data class SlideData(val index: Int, val title: String, val body: String)

// ── Public entry point ─────────────────────────────────────────────────────────

@Composable
fun OfficeViewerScreen(
    fileName: String,
    fileBytes: ByteArray?,
    filePath: String,
    viewerType: OfficeViewerType,
    onDismiss: () -> Unit,
    onGetOnlineEditorUrl: (onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) -> Unit
) {
    when (viewerType) {
        OfficeViewerType.POI -> PoiViewerContent(
            fileName = fileName,
            fileBytes = fileBytes ?: byteArrayOf(),
            onDismiss = onDismiss
        )
        OfficeViewerType.ONLINE -> OnlineViewerContent(
            fileName = fileName,
            onDismiss = onDismiss,
            onGetOnlineEditorUrl = onGetOnlineEditorUrl
        )
    }
}

// ── POI local viewer ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PoiViewerContent(fileName: String, fileBytes: ByteArray, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val ext = fileName.substringAfterLast('.', "").lowercase()

    val content by produceState<OfficeContent?>(null) {
        value = withContext(Dispatchers.IO) {
            try {
                when (ext) {
                    "xlsx", "xls" -> parseSpreadsheet(fileBytes)
                    "docx" -> parseDocument(fileBytes)
                    "pptx" -> parsePresentation(fileBytes)
                    "csv" -> parseCsv(fileBytes)
                    else -> OfficeContent.Error(".$ext")
                }
            } catch (e: Throwable) {
                OfficeContent.Error(e.message ?: e::class.simpleName ?: "")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, s.back) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val c = content) {
                null -> LoadingView(s.loadingPdf)
                is OfficeContent.Error -> ErrorView(s.documentError, c.message)
                is OfficeContent.Spreadsheet -> SpreadsheetView(c)
                is OfficeContent.Document -> DocumentView(c)
                is OfficeContent.Presentation -> PresentationView(c)
                is OfficeContent.Csv -> CsvView(c)
            }
        }
    }
}

// ── Collabora / OnlyOffice WebView viewer ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineViewerContent(
    fileName: String,
    onDismiss: () -> Unit,
    onGetOnlineEditorUrl: (onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) -> Unit
) {
    val s = LocalStrings.current
    var editorUrl by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        onGetOnlineEditorUrl({ url -> editorUrl = url }, { e -> errorMsg = e.message })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, s.back) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                errorMsg != null -> ErrorView(s.collaboraLoadFailed, errorMsg ?: "")
                editorUrl != null -> {
                    val url = editorUrl!!
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                webViewClient = WebViewClient()
                                loadUrl(url)
                            }
                        }
                    )
                }
                else -> LoadingView(s.loadingPdf)
            }
        }
    }
}

// ── Shared UI components ───────────────────────────────────────────────────────

@Composable
private fun LoadingView(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorView(title: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(Icons.Default.BrokenImage, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Spreadsheet renderer ───────────────────────────────────────────────────────

@Composable
private fun SpreadsheetView(content: OfficeContent.Spreadsheet) {
    var selectedSheet by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (content.sheets.size > 1) {
            ScrollableTabRow(selectedTabIndex = selectedSheet) {
                content.sheets.forEachIndexed { idx, sheet ->
                    Tab(selected = selectedSheet == idx, onClick = { selectedSheet = idx },
                        text = { Text(sheet.name, maxLines = 1) })
                }
            }
        }
        val sheet = content.sheets.getOrNull(selectedSheet) ?: return@Column
        if (sheet.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        val maxCols = sheet.rows.maxOf { it.cells.size }.coerceAtLeast(1)
        val colW = 130.dp
        Box(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(modifier = Modifier.fillMaxHeight()) {
                itemsIndexed(sheet.rows) { rowIdx, row ->
                    Row(
                        modifier = Modifier.background(
                            when {
                                rowIdx == 0 -> MaterialTheme.colorScheme.primaryContainer
                                rowIdx % 2 != 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        repeat(maxCols) { colIdx ->
                            Text(
                                text = row.cells.getOrNull(colIdx)?.value ?: "",
                                style = if (rowIdx == 0) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                modifier = Modifier.width(colW).padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                            if (colIdx < maxCols - 1) {
                                Box(modifier = Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// ── Document renderer ──────────────────────────────────────────────────────────

@Composable
private fun DocumentView(content: OfficeContent.Document) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(content.paragraphs) { para ->
            when {
                para.isHeading -> Text(para.text, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                para.isBullet -> Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text("• ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(para.text, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(para.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ── Presentation renderer ──────────────────────────────────────────────────────

@Composable
private fun PresentationView(content: OfficeContent.Presentation) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(content.slides) { slide ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Slide ${slide.index + 1}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    if (slide.title.isNotEmpty()) {
                        Text(slide.title, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (slide.body.isNotEmpty()) {
                        Text(slide.body, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── CSV renderer (reuses spreadsheet table) ────────────────────────────────────

@Composable
private fun CsvView(content: OfficeContent.Csv) {
    val asSheet = OfficeContent.Spreadsheet(listOf(
        SheetData("CSV", content.rows.map { cols -> RowData(cols.map { CellData(it) }) })
    ))
    SpreadsheetView(asSheet)
}

// ── POI parsing ────────────────────────────────────────────────────────────────

private fun parseSpreadsheet(bytes: ByteArray): OfficeContent.Spreadsheet {
    val wb = WorkbookFactory.create(ByteArrayInputStream(bytes))
    val sheets = (0 until wb.numberOfSheets).map { i ->
        val sheet = wb.getSheetAt(i)
        val rows = (0..sheet.lastRowNum).mapNotNull { rowIdx ->
            val row = sheet.getRow(rowIdx) ?: return@mapNotNull null
            val lastCol = row.lastCellNum.toInt().coerceAtLeast(0)
            if (lastCol == 0) return@mapNotNull null
            val cells = (0 until lastCol).map { colIdx ->
                val cell = row.getCell(colIdx)
                CellData(when (cell?.cellType) {
                    CellType.STRING -> cell.stringCellValue
                    CellType.NUMERIC -> {
                        if (DateUtil.isCellDateFormatted(cell)) cell.dateCellValue?.toString() ?: ""
                        else cell.numericCellValue.let { n ->
                            if (n == n.toLong().toDouble()) n.toLong().toString() else "%.2f".format(n)
                        }
                    }
                    CellType.BOOLEAN -> cell.booleanCellValue.toString()
                    CellType.FORMULA -> runCatching { cell.stringCellValue }
                        .getOrElse { cell.numericCellValue.toString() }
                    else -> ""
                })
            }
            if (cells.all { it.value.isEmpty() }) null else RowData(cells)
        }
        SheetData(sheet.sheetName, rows)
    }
    wb.close()
    return OfficeContent.Spreadsheet(sheets)
}

private fun parseDocument(bytes: ByteArray): OfficeContent.Document {
    val doc = XWPFDocument(ByteArrayInputStream(bytes))
    val paragraphs = doc.paragraphs.mapNotNull { para ->
        val text = para.text.trim().ifEmpty { return@mapNotNull null }
        val styleId = para.style?.lowercase() ?: ""
        val isHeading = styleId.contains("heading") || styleId == "title" ||
            (styleId.length == 1 && styleId[0] in '1'..'3')
        val isBullet = !isHeading && para.numID != null
        ParaData(text, isHeading, isBullet)
    }
    doc.close()
    return OfficeContent.Document(paragraphs)
}

private fun parsePresentation(bytes: ByteArray): OfficeContent.Presentation {
    val ppt = XMLSlideShow(ByteArrayInputStream(bytes))
    val slides = ppt.slides.mapIndexed { idx, slide ->
        val title = runCatching { slide.title }.getOrNull()?.trim() ?: ""
        val body = slide.shapes
            .filterIsInstance<XSLFTextShape>()
            .flatMap { it.textParagraphs }
            .mapNotNull { it.text.trim().ifEmpty { null } }
            .filter { it != title }
            .joinToString("\n")
        SlideData(idx, title, body)
    }
    ppt.close()
    return OfficeContent.Presentation(slides)
}

private fun parseCsv(bytes: ByteArray): OfficeContent.Csv {
    val rows = bytes.toString(Charsets.UTF_8).lines()
        .filter { it.isNotBlank() }
        .map { line ->
            val sep = if (line.contains(';')) ';' else ','
            line.split(sep).map { it.trim().removeSurrounding("\"") }
        }
    return OfficeContent.Csv(rows)
}
