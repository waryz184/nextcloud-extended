package xyz.luna.nextcloudextended.upload

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRetryPolicyTest {
    @Test fun networkIoIsRetryable() {
        assertTrue(UploadRetryPolicy.isRetryable(IOException("offline")))
    }

    @Test fun serverErrorIsRetryable() {
        assertTrue(UploadRetryPolicy.isRetryable(Exception("HTTP Error: 503")))
    }

    @Test fun authenticationErrorIsPermanent() {
        assertFalse(UploadRetryPolicy.isRetryable(Exception("HTTP Error: 401")))
    }

    @Test fun invalidSourceIsPermanent() {
        assertFalse(UploadRetryPolicy.isRetryable(IllegalArgumentException("source missing")))
    }
}
