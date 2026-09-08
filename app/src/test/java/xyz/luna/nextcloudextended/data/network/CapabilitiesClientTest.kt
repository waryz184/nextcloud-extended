package xyz.luna.nextcloudextended.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesClientTest {
    @Test fun parsesOcsCapabilitiesAndNestedApplicationKeys() {
        val capabilities = CapabilitiesClient.parse(
            """
            {"ocs":{"data":{"version":{"string":"29.0.1"},"capabilities":{
              "files_sharing":{"api_enabled":true},
              "notes":{"api_version":"1.2"},
              "richdocuments":{"mimetypes":["application/pdf"]}
            }}}}
            """.trimIndent()
        )

        assertTrue(capabilities.discovered)
        assertEquals("29.0.1", capabilities.serverVersion)
        assertTrue(capabilities.has("notes"))
        assertTrue(capabilities.has("files_sharing"))
        assertTrue(capabilities.has("richdocuments"))
        assertFalse(capabilities.has("talk"))
    }

    @Test fun acceptsDataAsTheCapabilitiesRoot() {
        val capabilities = CapabilitiesClient.parse("{\"data\":{\"capabilities\":{\"notes\":{}}}}")
        assertTrue(capabilities.discovered)
        assertTrue(capabilities.has("notes"))
    }
}
