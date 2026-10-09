package xyz.luna.nextcloudextended.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.luna.nextcloudextended.ui.screens.remoteFolder

class TransferHistoryTest {
    @Test fun aRemoteFolderIsShownRelativeToTheAccountRoot() {
        assertEquals("Photos/2025", remoteFolder("/remote.php/dav/files/alice/Photos/2025/"))
        assertEquals("/", remoteFolder("/remote.php/dav/files/alice/"))
        assertEquals("/", remoteFolder("/remote.php/dav/files/alice"))
    }

    @Test fun namesAreDecodedAndSubFolderInstallsAreHandled() {
        assertEquals("OUTILS IT/été", remoteFolder("/nextcloud/remote.php/dav/files/alice/OUTILS%20IT/%C3%A9t%C3%A9/"))
        assertEquals("a+b", remoteFolder("/remote.php/dav/files/alice/a+b/"))
    }

    @Test fun somethingUnexpectedIsLeftReadable() {
        assertEquals("some/where", remoteFolder("some/where"))
        assertEquals("/", remoteFolder(""))
    }
}
