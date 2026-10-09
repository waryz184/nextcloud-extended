package xyz.luna.nextcloudextended.upload

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.luna.nextcloudextended.data.network.HttpStatusException
import xyz.luna.nextcloudextended.data.network.LocalIoException

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

    @Test fun typedStatusErrorsFollowTheirKind() {
        assertTrue(UploadRetryPolicy.isRetryable(HttpStatusException(503)))
        assertTrue(UploadRetryPolicy.isRetryable(HttpStatusException(429)))
        assertTrue("a locked file is released eventually", UploadRetryPolicy.isRetryable(HttpStatusException(423)))
        assertFalse("a full account needs the user", UploadRetryPolicy.isRetryable(HttpStatusException(507)))
        assertFalse(UploadRetryPolicy.isRetryable(HttpStatusException(413)))
        assertFalse(UploadRetryPolicy.isRetryable(HttpStatusException(403)))
        assertFalse(UploadRetryPolicy.isRetryable(HttpStatusException(404)))
    }

    @Test fun maintenanceModeWaitsInsteadOfFailing() {
        assertTrue(UploadRetryPolicy.isRetryable(HttpStatusException(503, maintenance = true)))
    }

    @Test fun localProblemsAreNeverRetriedOverTheNetwork() {
        assertFalse(UploadRetryPolicy.isRetryable(LocalIoException("disk full")))
    }

    @Test fun beingOfflineIsGivenMuchMoreRoomThanAServerFailure() {
        assertEquals(UploadRetryPolicy.MAX_CONNECTIVITY_ATTEMPTS, UploadRetryPolicy.maxAttempts(UnknownHostException("cloud.example.com")))
        assertEquals(UploadRetryPolicy.MAX_CONNECTIVITY_ATTEMPTS, UploadRetryPolicy.maxAttempts(SocketTimeoutException()))
        assertEquals(UploadRetryPolicy.MAX_ATTEMPTS, UploadRetryPolicy.maxAttempts(HttpStatusException(500)))
        assertTrue(UploadRetryPolicy.MAX_CONNECTIVITY_ATTEMPTS > UploadRetryPolicy.MAX_ATTEMPTS)
    }
}
