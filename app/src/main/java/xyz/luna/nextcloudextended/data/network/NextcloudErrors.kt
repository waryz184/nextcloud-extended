package xyz.luna.nextcloudextended.data.network

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Typed failure for a non-success HTTP answer. [message] keeps the historical "HTTP Error: NNN"
 * wording so callers that match on the status text keep working, while [kind] lets new code branch
 * on the cause instead of parsing strings.
 */
class HttpStatusException(
    val code: Int,
    val reason: String? = null,
    /** Sabre `<s:message>` / `<s:exception>` extracted from the body, when the server sent one. */
    val davMessage: String? = null,
    val davException: String? = null,
    /** Server-provided `Retry-After`, in milliseconds. */
    val retryAfterMs: Long? = null,
    val maintenance: Boolean = false
) : IOException("HTTP Error: $code") {

    enum class Kind {
        UNAUTHORIZED, FORBIDDEN, NOT_FOUND, METHOD_NOT_ALLOWED, CONFLICT, PRECONDITION_FAILED,
        PAYLOAD_TOO_LARGE, LOCKED, RATE_LIMITED, MAINTENANCE, QUOTA_EXCEEDED, SERVER_ERROR,
        CLIENT_ERROR, UNEXPECTED
    }

    val kind: Kind
        get() = when {
            maintenance -> Kind.MAINTENANCE
            code == 401 -> Kind.UNAUTHORIZED
            code == 403 -> Kind.FORBIDDEN
            code == 404 -> Kind.NOT_FOUND
            code == 405 -> Kind.METHOD_NOT_ALLOWED
            code == 409 -> Kind.CONFLICT
            code == 412 -> Kind.PRECONDITION_FAILED
            code == 413 -> Kind.PAYLOAD_TOO_LARGE
            code == 423 -> Kind.LOCKED
            code == 429 -> Kind.RATE_LIMITED
            code == 507 -> Kind.QUOTA_EXCEEDED
            code in 500..599 -> Kind.SERVER_ERROR
            code in 400..499 -> Kind.CLIENT_ERROR
            else -> Kind.UNEXPECTED
        }

    /** True when trying again later (same request) has a realistic chance to succeed. */
    val isTransient: Boolean
        get() = when (kind) {
            Kind.RATE_LIMITED, Kind.MAINTENANCE, Kind.LOCKED -> true
            Kind.SERVER_ERROR -> code != 501 && code != 505
            // The server saw fewer bytes than OC-Total-Length announced: the connection dropped mid-upload.
            Kind.CLIENT_ERROR -> code == 408 || (code == 400 && davMessage?.contains("expected filesize", ignoreCase = true) == true)
            else -> false
        }

    /** Human readable detail suitable for an error line (server message first, then status). */
    val detail: String
        get() = davMessage?.takeIf { it.isNotBlank() } ?: reason?.takeIf { it.isNotBlank() } ?: message.orEmpty()
}

/** The credentials were refused (HTTP 401, or a redirect to an IdP login page). */
class AuthenticationException(message: String = "HTTP Error: 401") : IOException(message)

/** The server redirected to a plain-HTTP URL, or off-host while carrying credentials. */
class InsecureRedirectException(val location: String) : IOException("Refused insecure redirect to $location")

class TooManyRedirectsException(val lastLocation: String?) : IOException("Too many redirects")

/** Raised when a [DavSession] was cancelled via `cancelAll()` while a request was in flight. */
class RequestCancelledException : IOException("Request cancelled")

/** The server answered 2xx but with a body that is not the expected DAV/OCS/JSON document. */
class UnexpectedResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Disk/local problem while reading a source or writing a download — never worth a network retry. */
class LocalIoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Coarse, UI-friendly classification of any failure. Mirrors what the official client reports
 * through `RemoteOperationResult.ResultCode`.
 */
enum class FailureKind {
    NO_NETWORK, HOST_UNREACHABLE, TIMEOUT, TLS, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT,
    PRECONDITION_FAILED, LOCKED, QUOTA_EXCEEDED, TOO_LARGE, MAINTENANCE, RATE_LIMITED, SERVER_ERROR,
    INSECURE_REDIRECT, CANCELLED, LOCAL_IO, UNEXPECTED_RESPONSE, UNTRUSTED_DOMAIN, NOT_NEXTCLOUD, OTHER
}

fun Throwable.failureKind(): FailureKind {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < 8) {
        when (current) {
            is HttpStatusException -> return when (current.kind) {
                HttpStatusException.Kind.UNAUTHORIZED -> FailureKind.UNAUTHORIZED
                HttpStatusException.Kind.FORBIDDEN -> FailureKind.FORBIDDEN
                HttpStatusException.Kind.NOT_FOUND -> FailureKind.NOT_FOUND
                HttpStatusException.Kind.CONFLICT -> FailureKind.CONFLICT
                HttpStatusException.Kind.PRECONDITION_FAILED -> FailureKind.PRECONDITION_FAILED
                HttpStatusException.Kind.PAYLOAD_TOO_LARGE -> FailureKind.TOO_LARGE
                HttpStatusException.Kind.LOCKED -> FailureKind.LOCKED
                HttpStatusException.Kind.RATE_LIMITED -> FailureKind.RATE_LIMITED
                HttpStatusException.Kind.MAINTENANCE -> FailureKind.MAINTENANCE
                HttpStatusException.Kind.QUOTA_EXCEEDED -> FailureKind.QUOTA_EXCEEDED
                HttpStatusException.Kind.SERVER_ERROR -> FailureKind.SERVER_ERROR
                else -> FailureKind.OTHER
            }
            is AuthenticationException -> return FailureKind.UNAUTHORIZED
            is ConflictException -> return FailureKind.PRECONDITION_FAILED
            is InsecureRedirectException, is TooManyRedirectsException -> return FailureKind.INSECURE_REDIRECT
            is RequestCancelledException -> return FailureKind.CANCELLED
            is LocalIoException -> return FailureKind.LOCAL_IO
            is UnexpectedResponseException -> return FailureKind.UNEXPECTED_RESPONSE
            is UntrustedDomainException -> return FailureKind.UNTRUSTED_DOMAIN
            is NotNextcloudException -> return FailureKind.NOT_NEXTCLOUD
            is UnknownHostException, is NoRouteToHostException, is ConnectException -> return FailureKind.HOST_UNREACHABLE
            is SocketTimeoutException -> return FailureKind.TIMEOUT
            is SSLHandshakeException, is SSLPeerUnverifiedException, is SSLException -> return FailureKind.TLS
            is SocketException -> return FailureKind.NO_NETWORK
        }
        current = current.cause
    }
    return FailureKind.OTHER
}

/**
 * Whether the *same* operation is worth retrying later (background queues). Anything that needs the
 * user (bad credentials, quota, too large, forbidden, missing source) is permanent; connectivity and
 * server-side hiccups are transient.
 */
fun Throwable.isTransientFailure(): Boolean {
    val http = generateSequence(this) { it.cause }.take(8).filterIsInstance<HttpStatusException>().firstOrNull()
    if (http != null) return http.isTransient
    return when (failureKind()) {
        FailureKind.NO_NETWORK, FailureKind.HOST_UNREACHABLE, FailureKind.TIMEOUT,
        FailureKind.MAINTENANCE, FailureKind.RATE_LIMITED, FailureKind.LOCKED, FailureKind.SERVER_ERROR -> true
        FailureKind.OTHER -> this is IOException
        else -> false
    }
}
