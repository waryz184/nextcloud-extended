package xyz.luna.nextcloudextended.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextcloudLoginQrTest {

    @Test
    fun parsesStandardLoginQr() {
        val qr = parseNextcloudLoginQr("nc://login/user:alice&password:abcde-fghij-klmno&server:https://cloud.example.com")
        assertEquals(NextcloudLoginQr("https://cloud.example.com", "alice", "abcde-fghij-klmno"), qr)
    }

    @Test
    fun keepsPortAndSubfolderOfServer() {
        val qr = parseNextcloudLoginQr("nc://login/user:bob&password:secret&server:https://example.com:8443/nextcloud/")
        assertEquals("https://example.com:8443/nextcloud", qr?.serverUrl)
    }

    @Test
    fun acceptsAnyFieldOrderAndUrlEncodedValues() {
        val qr = parseNextcloudLoginQr("nc://login/server:https%3A%2F%2Fcloud.example.com&password:p%26ss&user:jean%20dupont")
        assertEquals(NextcloudLoginQr("https://cloud.example.com", "jean dupont", "p&ss"), qr)
    }

    @Test
    fun rejectsForeignOrIncompleteContent() {
        assertNull(parseNextcloudLoginQr("https://cloud.example.com"))
        assertNull(parseNextcloudLoginQr("nc://login/user:alice&server:https://cloud.example.com"))
        assertNull(parseNextcloudLoginQr("nc://login/user:&password:x&server:https://cloud.example.com"))
        assertNull(parseNextcloudLoginQr(""))
    }
}
