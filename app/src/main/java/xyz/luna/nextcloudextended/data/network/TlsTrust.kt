package xyz.luna.nextcloudextended.data.network

import android.content.SharedPreferences
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** What the user is shown before deciding to trust a certificate that no public CA vouches for. */
data class CertificateInfo(
    val host: String,
    /** SHA-256 of the leaf certificate, colon separated upper-case hex — what browsers display. */
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val selfSigned: Boolean,
    /** A different certificate was trusted for this host before: possible interception, or a renewal. */
    val replacesTrusted: Boolean
)

/** Persistent list of certificates the user explicitly accepted, per host (self-hosted servers). */
class KnownCertificates(private val prefs: SharedPreferences) {
    fun fingerprints(host: String): Set<String> = prefs.getStringSet(key(host), emptySet()) ?: emptySet()
    fun allFingerprints(): Set<String> = prefs.all.values.flatMap { (it as? Set<*>)?.filterIsInstance<String>().orEmpty() }.toSet()
    fun isTrusted(host: String, sha256: String) = sha256 in fingerprints(host)
    fun trust(host: String, sha256: String) {
        prefs.edit().putStringSet(key(host), setOf(sha256)).apply() // one pinned certificate per host
    }
    fun forget(host: String) { prefs.edit().remove(key(host)).apply() }
    private fun key(host: String) = "cert_" + host.lowercase()
}

/**
 * TLS trust for self-hosted servers: certificates validated by the system store are accepted as usual;
 * a certificate the user explicitly trusted (pinned by SHA-256) is accepted for that host only. A
 * rejected chain is remembered so the UI can show its fingerprint and ask.
 */
object TlsTrust {
    @Volatile var known: KnownCertificates? = null

    private val rejected = ConcurrentHashMap<String, Array<X509Certificate>>()

    /** Chains accepted only because their fingerprint was pinned (for some host), keyed by that fingerprint. */
    private val acceptedByPin = ConcurrentHashMap<String, Array<X509Certificate>>()

    private val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = systemTrustManager.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val fingerprint = chain.firstOrNull()?.let(::fingerprint)
                // The host is not known here; the exact-certificate pin plus the hostname verifier below
                // make accepting by fingerprint equivalent to accepting per host.
                if (fingerprint != null && known?.allFingerprints()?.contains(fingerprint) == true) {
                    acceptedByPin[fingerprint] = chain
                    return
                }
                chain.firstOrNull()?.let { rejected[LAST] = chain }
                throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    private val defaultVerifier: HostnameVerifier by lazy { HttpsURLConnection.getDefaultHostnameVerifier() }

    val hostnameVerifier = HostnameVerifier { host: String, session: SSLSession ->
        defaultVerifier.verify(host, session) || runCatching {
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
            val pinnedForHost = leaf != null && known?.isTrusted(host, fingerprint(leaf)) == true
            if (!pinnedForHost && leaf != null) {
                // A certificate the user trusted for another address (same server reached by IP, a second
                // name, another port…): keep its chain so the UI asks about this address instead of failing.
                acceptedByPin[fingerprint(leaf)]?.let { rejected[LAST] = it }
            }
            pinnedForHost
        }.getOrDefault(false)
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }.socketFactory
    }

    /** The certificate chain that was refused most recently, described for [host], or null. */
    fun describeRejected(host: String): CertificateInfo? {
        val chain = rejected[LAST] ?: return null
        val leaf = chain.firstOrNull() ?: return null
        val previous = known?.fingerprints(host).orEmpty()
        return CertificateInfo(
            host = host,
            sha256 = fingerprint(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBefore = leaf.notBefore.time,
            notAfter = leaf.notAfter.time,
            selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
            replacesTrusted = previous.isNotEmpty() && fingerprint(leaf) !in previous
        )
    }

    fun trust(info: CertificateInfo) {
        known?.trust(info.host, info.sha256)
        rejected.remove(LAST)
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    private const val LAST = "last"
}
