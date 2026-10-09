package xyz.luna.nextcloudextended.data.network

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

/** End-to-end check of the self-signed-certificate flow against a real TLS server. */
class TlsTrustTest {
    private lateinit var server: MockWebServer
    private lateinit var heldCertificate: HeldCertificate

    private class MemoryPrefs : SharedPreferences {
        private val data = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun contains(key: String) = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals.add(key) }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { removals.forEach { data.remove(it) }; data.putAll(pending) }
        }
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before fun setUp() {
        heldCertificate = HeldCertificate.Builder().commonName("homeserver.lan").addSubjectAlternativeName("localhost").build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
        server = MockWebServer().also {
            it.useHttps(handshake.sslSocketFactory(), false)
            it.enqueue(MockResponse().setBody("hello"))
            it.enqueue(MockResponse().setBody("hello again"))
            it.start()
        }
        TlsTrust.known = KnownCertificates(MemoryPrefs())
    }

    @After fun tearDown() {
        server.shutdown()
        TlsTrust.known = null
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(TlsTrust.sslSocketFactory, TlsTrust.trustManager)
        .hostnameVerifier(TlsTrust.hostnameVerifier)
        .build()

    private fun get(): String = client().newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() }

    @Test fun selfSignedCertificateIsRefusedUntilTheUserTrustsIt() {
        try { get(); fail("an unknown self-signed certificate must be refused") } catch (e: IOException) {
            assertTrue(e is SSLException)
            assertEquals(FailureKind.TLS, e.failureKind())
        }
        val info = TlsTrust.describeRejected(server.hostName)
        assertNotNull("the refused certificate is available to show the user", info)
        assertEquals(heldCertificate.certificate.let(TlsTrust::fingerprint), info!!.sha256)
        assertTrue(info.selfSigned)
        assertFalse(info.replacesTrusted)

        TlsTrust.trust(info)
        assertEquals("hello", get())
    }

    @Test fun trustingOneCertificateDoesNotTrustAnother() {
        val info = run { try { get() } catch (_: IOException) {}; TlsTrust.describeRejected(server.hostName)!! }
        TlsTrust.known!!.trust("someother.example", "AA:BB")
        // pinned for a different host/fingerprint: still refused
        try { get(); fail() } catch (e: IOException) { assertTrue(e is SSLException) }
        TlsTrust.trust(info)
        assertEquals("hello", get())
    }

    @Test fun aChangedCertificateIsFlaggedAsReplacingTheTrustedOne() {
        TlsTrust.known!!.trust(server.hostName, "11:22:33")
        try { get(); fail() } catch (e: IOException) { }
        val info = TlsTrust.describeRejected(server.hostName)!!
        assertTrue("a different certificate for an already trusted host deserves a loud warning", info.replacesTrusted)
    }

    @Test fun forgettingAHostRevokesTheTrust() {
        try { get() } catch (_: IOException) {}
        TlsTrust.trust(TlsTrust.describeRejected(server.hostName)!!)
        assertEquals("hello", get())
        TlsTrust.known!!.forget(server.hostName)
        client().connectionPool.evictAll()
        try { get(); fail() } catch (e: IOException) { assertTrue(e is SSLException) }
    }

    @Test fun nothingIsReportedWhenNothingWasRefused() {
        assertNull(TlsTrust.describeRejected("never-seen.example"))
    }
}
