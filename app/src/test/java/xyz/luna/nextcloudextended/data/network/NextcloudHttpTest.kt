package xyz.luna.nextcloudextended.data.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NextcloudHttpTest {
    private val v4a = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 1))
    private val v4b = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 2))
    private val v6a = InetAddress.getByAddress("h", ByteArray(15) + byteArrayOf(1))
    private val v6b = InetAddress.getByAddress("h", ByteArray(15) + byteArrayOf(2))

    private fun dns(vararg addresses: InetAddress) = AddressFamilyDns(object : Dns {
        override fun lookup(hostname: String) = addresses.toList()
    })

    @Test fun alternatesAddressFamiliesSoABrokenOneCostsOneAttemptNotAll() {
        val result = dns(v6a, v6b, v4a, v4b).lookup("h")
        // IPv6 first by default, but never two of the same family in a row.
        assertEquals(listOf(v6a, v4a, v6b, v4b), result)
    }

    @Test fun rememberedFamilyGoesFirst() {
        AddressFamilyListener.remember("h", ipv4 = true)
        try {
            assertEquals(listOf(v4a, v6a, v4b, v6b), dns(v6a, v6b, v4a, v4b).lookup("h"))
        } finally {
            AddressFamilyListener.remember("h", ipv4 = false)
        }
    }

    @Test fun singleFamilyAndSingleAddressAreLeftAlone() {
        assertEquals(listOf(v4a, v4b), dns(v4a, v4b).lookup("h"))
        assertEquals(listOf(v6a), dns(v6a).lookup("h"))
    }

    @Test fun userAgentIdentifiesTheAppAndDevice() {
        NextcloudHttp.configureUserAgent("1.2.3", "Pixel 9 (Pro)")
        assertTrue(NextcloudHttp.userAgent, NextcloudHttp.userAgent.startsWith("Mozilla/5.0 (Android) Nextcloud-Extended/1.2.3"))
        assertTrue("characters that could break a header are stripped", !NextcloudHttp.userAgent.contains("(Pro)"))
    }

    @Test fun readTimeoutsScaleWithSize() {
        assertEquals(60_000L, TransferTimeouts.readTimeoutMs(0))
        assertEquals(1_800_000L, TransferTimeouts.readTimeoutMs(Long.MAX_VALUE / 4))
    }
}
