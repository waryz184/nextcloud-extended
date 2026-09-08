package xyz.luna.nextcloudextended.upload

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException

object UploadRetryPolicy {
    const val MAX_ATTEMPTS = 5

    fun isRetryable(error: Exception): Boolean =
        error is IOException || error is TimeoutCancellationException || error.message?.contains("HTTP Error: 5") == true
}
