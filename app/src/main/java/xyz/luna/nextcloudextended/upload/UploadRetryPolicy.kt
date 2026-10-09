package xyz.luna.nextcloudextended.upload

import xyz.luna.nextcloudextended.data.network.FailureKind
import xyz.luna.nextcloudextended.data.network.failureKind
import xyz.luna.nextcloudextended.data.network.isTransientFailure
import java.io.IOException

object UploadRetryPolicy {
    /** Attempts for failures that point at the server (5xx, locked, rate limited…). */
    const val MAX_ATTEMPTS = 5

    /** Being offline or on a bad link is not the file's fault: keep trying for much longer. */
    const val MAX_CONNECTIVITY_ATTEMPTS = 24

    fun isRetryable(error: Throwable): Boolean {
        if (error.failureKind() != FailureKind.OTHER || error is IOException) return error.isTransientFailure()
        // Untyped failures from older call sites only carry their status in the message.
        return Regex("HTTP Error: (5[0-9][0-9]|408|423|429)\\b").containsMatchIn(error.message.orEmpty())
    }

    fun maxAttempts(error: Throwable): Int = when (error.failureKind()) {
        FailureKind.NO_NETWORK, FailureKind.HOST_UNREACHABLE, FailureKind.TIMEOUT -> MAX_CONNECTIVITY_ATTEMPTS
        else -> MAX_ATTEMPTS
    }
}
