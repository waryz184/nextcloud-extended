package xyz.luna.nextcloudextended.data.network

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process wide HTTP plumbing shared by every account/client/worker.
 *
 * Before this, each `CalDavClient` (one per login, per worker run, per DocumentsProvider call, per
 * sync) built its own `OkHttpClient`: no connection reuse, a new thread pool each time and no common
 * policy. Everything now derives from [baseClient], sharing one connection pool and dispatcher.
 */
object NextcloudHttp {
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Android) Nextcloud-Extended"

    /**
     * Sent with every request. Nextcloud names the app password created by browser sign-in after it, so
     * it includes the app version and the device model ("Devices & sessions" stays readable).
     */
    @Volatile var userAgent: String = DEFAULT_USER_AGENT
        private set

    fun configureUserAgent(appVersion: String, deviceModel: String) {
        userAgent = "$DEFAULT_USER_AGENT/$appVersion (${deviceModel.replace(Regex("[^A-Za-z0-9 ._-]"), "")})"
    }

    const val CONNECT_TIMEOUT_MS = 15_000L
    const val READ_TIMEOUT_MS = 60_000L
    const val WRITE_TIMEOUT_MS = 60_000L

    /** Hard ceiling for short API calls (PROPFIND, OCS, ...). Transfers disable it. */
    const val API_CALL_TIMEOUT_MS = 120_000L

    private val threadCounter = AtomicInteger()

    /**
     * Runs blocking requests for the callback style API. Unbounded on purpose: a bounded pool with a
     * "caller runs" fallback would eventually execute network code on the main thread, and one with a
     * queue could starve a short request behind a long transfer. Idle threads are reclaimed after 30 s.
     */
    val ioExecutor: ExecutorService by lazy {
        ThreadPoolExecutor(0, Int.MAX_VALUE, 30L, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "nextcloud-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    val dns: Dns by lazy { AddressFamilyDns() }

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // Redirects are followed by DavSession: OkHttp would turn a 301/302 MOVE/COPY/PUT into a GET.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .cookieJar(CookieJar.NO_COOKIES)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dns(dns)
            .sslSocketFactory(TlsTrust.sslSocketFactory, TlsTrust.trustManager)
            .hostnameVerifier(TlsTrust.hostnameVerifier)
            .eventListenerFactory { AddressFamilyListener }
            .build()
    }
}

/** Read timeout scaled with the payload: the server only answers a PUT/MOVE once it processed it all. */
object TransferTimeouts {
    private const val MIN_MS = 60_000L
    private const val MAX_MS = 30L * 60_000L
    private const val PER_GB_MS = 180_000L

    fun readTimeoutMs(sizeBytes: Long?): Long {
        if (sizeBytes == null || sizeBytes <= 0) return MIN_MS
        // size * PER_GB / 1e9 without overflowing Long for multi-TB values
        val scaled = (sizeBytes.toDouble() * PER_GB_MS / 1_000_000_000.0).toLong()
        return scaled.coerceIn(MIN_MS, MAX_MS)
    }
}

/**
 * Orders resolved addresses so that a broken IPv6 (or IPv4) path does not cost a full connect
 * timeout on every request: families alternate, starting with whichever worked last for the host.
 * OkHttp 4 tries addresses strictly one after the other, so ordering is the only lever we have.
 */
internal class AddressFamilyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.size < 2) return addresses
        val v4 = addresses.filterIsInstance<Inet4Address>()
        val v6 = addresses.filterIsInstance<Inet6Address>()
        if (v4.isEmpty() || v6.isEmpty()) return addresses
        val preferV4 = AddressFamilyListener.preferIpv4(hostname)
        val first = if (preferV4) v4 else v6
        val second = if (preferV4) v6 else v4
        return buildList {
            var i = 0
            while (i < first.size || i < second.size) {
                if (i < first.size) add(first[i])
                if (i < second.size) add(second[i])
                i++
            }
        }
    }
}

/** Learns which address family connects for a host and remembers it. */
internal object AddressFamilyListener : EventListener() {
    private val ipv4Preferred = ConcurrentHashMap<String, Boolean>()

    fun preferIpv4(host: String): Boolean = ipv4Preferred[host] ?: false

    internal fun remember(host: String, ipv4: Boolean) { ipv4Preferred[host] = ipv4 }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        val address = inetSocketAddress.address ?: return
        remember(call.request().url.host, address is Inet4Address)
    }

    override fun connectFailed(
        call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: java.io.IOException
    ) {
        val address = inetSocketAddress.address ?: return
        // The family that just failed loses its priority; the other one is tried first next time.
        remember(call.request().url.host, address !is Inet4Address)
    }
}
