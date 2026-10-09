package xyz.luna.nextcloudextended.data.network

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/** Per-call knobs. Defaults suit short API calls; transfers pass `callTimeoutMs = 0` and a scaled read timeout. */
data class CallOptions(
    /** Extra status codes handed back to the caller instead of being raised (e.g. 404 for DELETE). */
    val accept: Set<Int> = emptySet(),
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
    val readTimeoutMs: Long? = null,
    val callTimeoutMs: Long = NextcloudHttp.API_CALL_TIMEOUT_MS,
    /** Retry MOVE/COPY/MKCOL/POST after an I/O failure too. Only safe when the caller re-checks state. */
    val retryNonIdempotent: Boolean = false
) {
    companion object {
        const val DEFAULT_ATTEMPTS = 3
        val SINGLE_ATTEMPT = CallOptions(maxAttempts = 1)
    }
}

/**
 * A blocking, thread-safe connection to one Nextcloud account: URL building (sub-folder installs
 * included), authentication, redirects, retries with back-off, cancellation and error mapping.
 *
 * Everything that talks to the server goes through [call]. The callback style `CalDavClient` is a
 * thin asynchronous facade on top of this class, which keeps the protocol logic testable on the JVM.
 */
class DavSession(
    serverUrl: String,
    userId: String,
    private val authorization: String?,
    baseClient: OkHttpClient = NextcloudHttp.baseClient,
    private val allowInsecureHttp: Boolean = false,
    private val userAgent: String = NextcloudHttp.userAgent,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    /** Server root including any sub-folder (`https://host/nextcloud`), without trailing slash. */
    val baseUrl: String = serverUrl.trim().trimEnd('/')
    private val base: HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid server URL: $serverUrl" }

    /** `""` for a root install, `/nextcloud` for a sub-folder install. */
    val basePath: String = base.encodedPath.trimEnd('/').let { percentDecode(it) }

    var userId: String = userId
        private set

    val isHttps: Boolean get() = base.isHttps

    /** Root of the user's files, as an app-internal (decoded) path. */
    val filesRoot: String get() = "/remote.php/dav/files/$userId/"

    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var epoch = 0
    private val requestCounter = AtomicInteger()

    private val listener = object : EventListener() {
        override fun callStart(call: Call) { active.add(call) }
        override fun callEnd(call: Call) { active.remove(call) }
        override fun callFailed(call: Call, ioe: IOException) { active.remove(call) }
        override fun canceled(call: Call) { active.remove(call) }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?) =
            AddressFamilyListener.connectEnd(call, inetSocketAddress, proxy, protocol)
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?, ioe: IOException) =
            AddressFamilyListener.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }

    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .eventListenerFactory { listener }
        .build()

    init {
        if (!allowInsecureHttp) {
            require(base.isHttps) { "HTTPS is required" }
        }
    }

    fun setUserId(id: String) { if (id.isNotBlank()) userId = id }

    /** Cancels every in-flight request and aborts pending retry waits. The session stays usable. */
    fun cancelAll() {
        epoch++
        active.toList().forEach { it.cancel() }
        active.clear()
    }

    // ── URLs ────────────────────────────────────────────────────────────────────────────────

    /** Full URL for an app-internal path (decoded, sub-folder free). Absolute URLs pass through. */
    fun url(path: String): String {
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        val absolute = if (path.startsWith("/")) path else "/$path"
        val internal = if (basePath.isNotEmpty() && (absolute == basePath || absolute.startsWith("$basePath/remote.php") || absolute.startsWith("$basePath/index.php") || absolute.startsWith("$basePath/ocs"))) {
            absolute.removePrefix(basePath)
        } else absolute
        return baseUrl + encodePath(internal)
    }

    fun request(path: String): Request.Builder = Request.Builder().url(url(path))

    fun canonicalHref(rawHref: String): String = canonicalHref(rawHref, basePath)

    // ── Execution ───────────────────────────────────────────────────────────────────────────

    /**
     * Runs [request] and returns the response for 2xx (and any [CallOptions.accept] code). The caller
     * must close it. Everything else raises a typed [IOException].
     */
    fun call(request: Request, options: CallOptions = CallOptions()): Response {
        val startEpoch = epoch
        val idempotent = options.retryNonIdempotent || request.method.uppercase(Locale.ROOT) in IDEMPOTENT_METHODS
        val attempts = if (idempotent) options.maxAttempts.coerceAtLeast(1) else 1
        var attempt = 0
        while (true) {
            checkNotCancelled(startEpoch)
            val response = try {
                send(request, options, startEpoch)
            } catch (e: IOException) {
                val failure = if (epoch != startEpoch) RequestCancelledException() else e
                if (failure is RequestCancelledException || !isRetryableFailure(failure) || ++attempt >= attempts) throw failure
                pause(backoffMs(attempt), startEpoch)
                continue
            }
            if (response.isSuccessful || response.code in options.accept) return response

            val error = response.use { toException(it) }
            if (error is HttpStatusException && !error.maintenance && error.code in RETRYABLE_STATUS && ++attempt < attempts) {
                val serverWait = error.retryAfterMs
                // A long Retry-After is the server asking us to come back much later: let the caller
                // (WorkManager for queued transfers) reschedule instead of blocking a thread.
                if (serverWait == null || serverWait <= MAX_RETRY_AFTER_MS) {
                    pause(serverWait ?: backoffMs(attempt), startEpoch)
                    continue
                }
            }
            throw error
        }
    }

    private fun send(initial: Request, options: CallOptions, startEpoch: Int): Response {
        val http = clientFor(options)
        var request = initial.withAuth()
        var currentBase = baseUrl
        var hops = 0
        while (true) {
            checkNotCancelled(startEpoch)
            requestCounter.incrementAndGet()
            val response = http.newCall(request).execute()
            val code = response.code
            if (code !in REDIRECT_STATUS) return response

            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw UnexpectedResponseException("HTTP $code redirect without Location")
            if (++hops > MAX_REDIRECTS) throw TooManyRedirectsException(location)
            val target = request.url.resolve(location) ?: throw UnexpectedResponseException("Bad redirect target: $location")
            if (!target.isHttps && !allowInsecureHttp) throw InsecureRedirectException(target.toString())
            if (looksLikeLogin(target)) throw AuthenticationException("Redirected to a login page: ${target.host}")

            val builder = request.newBuilder().url(target)
            if (code == 303) {
                builder.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length")
            }
            if (!sameOrigin(target)) builder.removeHeader("Authorization")
            request.header("Destination")?.let { destination ->
                val newBase = target.toString().substringBefore("/remote.php/dav", missingDelimiterValue = "")
                if (newBase.isNotEmpty() && destination.startsWith(currentBase)) {
                    builder.header("Destination", newBase + destination.removePrefix(currentBase))
                    currentBase = newBase
                }
            }
            request = builder.build()
        }
    }

    private fun clientFor(options: CallOptions): OkHttpClient {
        if (options.readTimeoutMs == null && options.callTimeoutMs == NextcloudHttp.API_CALL_TIMEOUT_MS) {
            return defaultClient
        }
        return client.newBuilder().apply {
            options.readTimeoutMs?.let { readTimeout(it, TimeUnit.MILLISECONDS) }
            callTimeout(options.callTimeoutMs, TimeUnit.MILLISECONDS)
        }.build()
    }

    private val defaultClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(NextcloudHttp.API_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    private fun Request.withAuth(): Request {
        val builder = newBuilder()
        if (header("User-Agent") == null) builder.header("User-Agent", userAgent)
        if (!authorization.isNullOrBlank() && header("Authorization") == null && sameOrigin(url)) {
            builder.header("Authorization", authorization)
        }
        return builder.build()
    }

    private fun sameOrigin(url: HttpUrl) =
        url.host.equals(base.host, true) && url.port == base.port && url.scheme == base.scheme

    private fun looksLikeLogin(url: HttpUrl): Boolean {
        val text = url.toString().lowercase(Locale.ROOT)
        return "saml" in text || "wayf" in text || url.encodedPath.endsWith("/login") ||
            url.encodedPath.contains("/index.php/login") || url.encodedPath.contains("/apps/user_saml")
    }

    private fun checkNotCancelled(startEpoch: Int) {
        if (epoch != startEpoch) throw RequestCancelledException()
    }

    private fun pause(ms: Long, startEpoch: Int) {
        var remaining = ms
        while (remaining > 0) {
            checkNotCancelled(startEpoch)
            val slice = minOf(remaining, 250L)
            try { sleeper(slice) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RequestCancelledException()
            }
            remaining -= slice
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val exp = BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 5)
        return minOf(exp, BACKOFF_MAX_MS) + ThreadLocalRandom.current().nextLong(0, BACKOFF_JITTER_MS)
    }

    private fun isRetryableFailure(e: IOException): Boolean = when (e) {
        is RequestCancelledException, is AuthenticationException, is InsecureRedirectException,
        is TooManyRedirectsException, is UnexpectedResponseException, is LocalIoException -> false
        is SSLException -> false
        is SocketTimeoutException, is ConnectException, is UnknownHostException -> true
        is SocketException -> true
        else -> true // truncated stream, connection reset, HTTP/2 stream error, ...
    }

    // ── Error mapping ───────────────────────────────────────────────────────────────────────

    private fun toException(response: Response): IOException {
        val code = response.code
        val body = runCatching { response.body?.source()?.let { it.request(MAX_ERROR_BODY); it.buffer.snapshot().utf8() } }
            .getOrNull().orEmpty().take(MAX_ERROR_BODY.toInt())
        val davMessage = extract(body, "message")?.let { unescapeXml(it).trim() }
            ?: Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        val davException = extract(body, "exception")?.let { unescapeXml(it).trim() }
        val maintenance = code == 503 && (
            body.contains("maintenance", ignoreCase = true) ||
                response.header("X-Nextcloud-Maintenance") != null ||
                davException?.contains("ServiceUnavailable", ignoreCase = true) == true
            )
        return HttpStatusException(
            code = code,
            reason = response.message.takeIf { it.isNotBlank() },
            davMessage = davMessage,
            davException = davException,
            retryAfterMs = parseRetryAfter(response.header("Retry-After")),
            maintenance = maintenance
        )
    }

    private fun extract(body: String, tag: String): String? =
        Regex("<(?:[A-Za-z0-9_-]+:)?$tag(?:\\s[^>]*)?>([\\s\\S]*?)</(?:[A-Za-z0-9_-]+:)?$tag>").find(body)?.groupValues?.get(1)

    companion object {
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "PROPFIND", "REPORT", "PUT", "DELETE", "PROPPATCH")
        private val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)
        private val RETRYABLE_STATUS = setOf(408, 429, 502, 503, 504)
        private const val MAX_REDIRECTS = 5
        private const val MAX_ERROR_BODY = 16L * 1024
        private const val BACKOFF_BASE_MS = 600L
        private const val BACKOFF_MAX_MS = 8_000L
        private const val BACKOFF_JITTER_MS = 250L
        private const val MAX_RETRY_AFTER_MS = 30_000L

        /** `Retry-After` is either delta-seconds or an HTTP date. */
        internal fun parseRetryAfter(value: String?): Long? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            v.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            return runCatching {
                val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(v) ?: return null
                (date.time - System.currentTimeMillis()).coerceAtLeast(0)
            }.getOrNull()
        }
    }
}
