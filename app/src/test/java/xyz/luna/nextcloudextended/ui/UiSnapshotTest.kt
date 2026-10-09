package xyz.luna.nextcloudextended.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.ui.files.FileSort
import xyz.luna.nextcloudextended.ui.screens.FilesScreen
import xyz.luna.nextcloudextended.ui.shell.AppDrawerContent
import xyz.luna.nextcloudextended.ui.shell.DrawerTarget
import xyz.luna.nextcloudextended.ui.shell.NcTopBar
import xyz.luna.nextcloudextended.ui.theme.NextcloudExtendedTheme
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the main screens to PNG files under `build/ui-snapshots/` so the layout can be inspected
 * without a device. It asserts nothing about pixels — it only has to render without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xxhdpi")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class UiSnapshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val files = listOf(
        NextcloudFile("Documents", "/remote.php/dav/files/alice/Documents/", true, 1_258_291, "Tue, 07 Oct 2025 09:00:00 GMT", favorite = true, permissions = "RGDNVCK"),
        NextcloudFile("Photos", "/remote.php/dav/files/alice/Photos/", true, 482_344_960, "Mon, 06 Oct 2025 18:20:00 GMT", permissions = "RGDNVCK"),
        NextcloudFile("Quarterly report 2025 with a very long file name.pdf", "/remote.php/dav/files/alice/report.pdf", false, 2_411_724, "Wed, 08 Oct 2025 12:00:00 GMT", mimeType = "application/pdf", favorite = true),
        NextcloudFile("Budget.xlsx", "/remote.php/dav/files/alice/Budget.xlsx", false, 48_200, "Sun, 05 Oct 2025 08:00:00 GMT"),
        NextcloudFile("Slides.pptx", "/remote.php/dav/files/alice/Slides.pptx", false, 9_400_000, "Fri, 03 Oct 2025 08:00:00 GMT"),
        NextcloudFile("holiday.jpg", "/remote.php/dav/files/alice/holiday.jpg", false, 3_100_000, "Thu, 02 Oct 2025 08:00:00 GMT", mimeType = "image/jpeg"),
        NextcloudFile("notes.txt", "/remote.php/dav/files/alice/notes.txt", false, 812, "Thu, 25 Sep 2025 08:00:00 GMT", mimeType = "text/plain"),
        NextcloudFile("backup.zip", "/remote.php/dav/files/alice/backup.zip", false, 120_000_000, "Mon, 01 Sep 2025 08:00:00 GMT")
    )

    private fun snapshot(name: String, content: @Composable () -> Unit) {
        compose.setContent { NextcloudExtendedTheme(darkTheme = false) { content() } }
        compose.waitForIdle()
        // captureToImage() relies on PixelCopy, which Robolectric does not provide: draw the window instead.
        val view = compose.activity.window.decorView
        val width = view.width.takeIf { it > 0 } ?: 1080
        val height = view.height.takeIf { it > 0 } ?: 2400
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/ui-snapshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Shell(title: String, showBack: Boolean, content: @Composable () -> Unit) {
        Scaffold(
            topBar = {
                NcTopBar(title, true, showBack, {}, true, false, "", {}, {}, {}, {}, 2, true, {}, null)
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(true, {}, { Icon(Icons.Default.Folder, null) }, label = { Text("Files") })
                    NavigationBarItem(false, {}, { Icon(Icons.Default.Star, null) }, label = { Text("Favorites") })
                    NavigationBarItem(false, {}, { Icon(Icons.Default.DateRange, null) }, label = { Text("Calendar") })
                    NavigationBarItem(false, {}, { Icon(Icons.Default.Edit, null) }, label = { Text("Notes") })
                }
            },
            floatingActionButton = { FloatingActionButton(onClick = {}) { Icon(Icons.Default.Star, null) } }
        ) { padding -> Column(Modifier.padding(padding)) { content() } }
    }

    private fun filesScreen(grid: Boolean): @Composable () -> Unit = {
        FilesScreen(
            files = files, filter = "", sort = FileSort.NAME_ASC, onSortChange = {}, gridView = grid, onGridViewChange = {},
            loader = null, resultsLabel = null, onClearResults = {}, emptyText = "Empty",
            canShareFiles = true, canShowVersions = true,
            onFileClick = {}, onOpenFile = {}, onShareFile = {}, onDownloadFile = {}, onMakeOffline = {},
            onToggleFavorite = {}, onShowVersions = {}, onRenameFile = {}, onCopyFile = {}, onMoveFile = {}, onDeleteFile = {}
        )
    }

    @Test fun filesList() = snapshot("files-list") { Shell("Files", false, filesScreen(false)) }

    @Test fun filesGrid() = snapshot("files-grid") { Shell("Files", false, filesScreen(true)) }

    @Test fun filesSubfolder() = snapshot("files-subfolder") { Shell("Documents", true, filesScreen(false)) }

    @Test fun drawer() = snapshot("drawer") {
        Surface {
            AppDrawerContent("Alice Martin", "cloud.example.com", "1.2 GB of 5 GB used", 0.24f, DrawerTarget.FILES, true, true) {}
        }
    }
}
