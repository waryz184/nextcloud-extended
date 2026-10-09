package xyz.luna.nextcloudextended.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs blocking network [block] on the shared I/O pool and suspends. Cancelling the coroutine (for
 * instance because WorkManager stopped the worker) aborts the in-flight HTTP call immediately —
 * blocking OkHttp I/O does not react to thread interruption on its own.
 */
suspend fun <T> DavSession.awaitBlocking(block: () -> T): T = suspendCancellableCoroutine { continuation ->
    val future = NextcloudHttp.ioExecutor.submit {
        try {
            val value = block()
            if (continuation.isActive) continuation.resume(value)
        } catch (e: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
    continuation.invokeOnCancellation {
        cancelAll()
        future.cancel(true)
    }
}
