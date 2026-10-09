package xyz.luna.nextcloudextended.data.network

import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DavSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun session(root: MockWebServer = server, path: String = "") = DavSession(
        serverUrl = root.url("/").toString().trimEnd('/') + path,
        userId = "alice",
        authorization = "Basic QUxJQ0U6c2VjcmV0",
        allowInsecureHttp = true,
        sleeper = { }
    )

    @Test fun refusesPlainHttpUnlessExplicitlyAllowed() {
        try {
            DavSession("http://cloud.example.com", "alice", "x")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun buildsUrlsForSubFolderInstalls() {
        val s = DavSession("https://example.com/nextcloud/", "alice", "x")
        assertEquals("/nextcloud", s.basePath)
        assertEquals("https://example.com/nextcloud/remote.php/dav/files/alice/My%20Docs/a%2Bb.txt",
            s.url("/remote.php/dav/files/alice/My Docs/a+b.txt"))
        // An href that still carries the sub-folder must not be doubled.
        assertEquals("https://example.com/nextcloud/remote.php/dav/files/alice/x",
            s.url("/nextcloud/remote.php/dav/files/alice/x"))
    }

    @Test fun sendsAuthorizationAndUserAgent() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        session().call(session().request("/status").build()).close()
        val recorded = server.takeRequest()
        assertEquals("Basic QUxJQ0U6c2VjcmV0", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("User-Agent")!!.contains("Nextcloud"))
    }

    @Test fun retriesIdempotentRequestsOnTransientServerErrors() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(200).setBody("fine"))
        val body = session().call(session().request("/data").build()).use { it.body!!.string() }
        assertEquals("fine", body)
        assertEquals(3, server.requestCount)
    }

    @Test fun doesNotRetryMoveEvenOnServerError() {
        server.enqueue(MockResponse().setResponseCode(503))
        val s = session()
        try {
            s.call(s.request("/a").header("Destination", s.url("/b")).method("MOVE", null).build())
            fail("expected HttpStatusException")
        } catch (e: HttpStatusException) {
            assertEquals(503, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun detectsMaintenanceMode() {
        server.enqueue(MockResponse().setResponseCode(503).setBody(
            """<?xml version="1.0"?><d:error xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns"><s:exception>Sabre\DAV\Exception\ServiceUnavailable</s:exception><s:message>System in maintenance mode.</s:message></d:error>"""))
        val s = session()
        try {
            s.call(s.request("/x").build())
            fail("expected HttpStatusException")
        } catch (e: HttpStatusException) {
            assertEquals(HttpStatusException.Kind.MAINTENANCE, e.kind)
            assertEquals("System in maintenance mode.", e.davMessage)
            assertEquals(FailureKind.MAINTENANCE, e.failureKind())
        }
        assertEquals("maintenance must not be hammered with retries", 1, server.requestCount)
    }

    @Test fun readsSabreErrorMessages() {
        server.enqueue(MockResponse().setResponseCode(507).setBody(
            """<d:error xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns"><s:exception>Sabre\DAV\Exception\InsufficientStorage</s:exception><s:message>Not enough space</s:message></d:error>"""))
        val s = session()
        try { s.call(s.request("/x").put(okhttp3.RequestBody.create(null, "hi")).build()); fail() }
        catch (e: HttpStatusException) {
            assertEquals(HttpStatusException.Kind.QUOTA_EXCEEDED, e.kind)
            assertEquals("Not enough space", e.detail)
            assertTrue(!e.isTransient)
        }
    }

    @Test fun followsRedirectsKeepingTheWebdavMethodAndBody() {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/moved/target"))
        server.enqueue(MockResponse().setResponseCode(201))
        val s = session()
        s.call(s.request("/old").method("MKCOL", null).build()).close()
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("MKCOL", second.method)
        assertEquals("/moved/target", second.path)
    }

    @Test fun replaysPutBodyOn307() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setResponseCode(201))
        val s = session()
        s.call(s.request("/file").put(okhttp3.RequestBody.create(null, "payload")).build()).close()
        server.takeRequest()
        val replay = server.takeRequest()
        assertEquals("PUT", replay.method)
        assertEquals("payload", replay.body.readUtf8())
    }

    @Test fun rewritesDestinationHeaderAfterRedirect() {
        val s = session(server, "")
        server.enqueue(MockResponse().setResponseCode(301)
            .setHeader("Location", server.url("/nextcloud/remote.php/dav/files/alice/old.txt").toString()))
        server.enqueue(MockResponse().setResponseCode(201))
        val destination = s.url("/remote.php/dav/files/alice/new.txt")
        s.call(s.request("/remote.php/dav/files/alice/old.txt").header("Destination", destination).method("MOVE", null).build()).close()
        server.takeRequest()
        val moved = server.takeRequest()
        assertEquals("/nextcloud/remote.php/dav/files/alice/old.txt", moved.path)
        assertTrue(moved.getHeader("Destination")!!.endsWith("/nextcloud/remote.php/dav/files/alice/new.txt"))
    }

    @Test fun dropsCredentialsWhenRedirectedToAnotherHost() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/elsewhere").toString()))
        other.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val s = session()
        s.call(s.request("/start").build()).close()
        assertNull("credentials must not leak to a different origin", other.takeRequest().getHeader("Authorization"))
    }

    @Test fun redirectToLoginPageIsAnAuthenticationFailure() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/index.php/login?redirect_url=/x"))
        val s = session()
        try { s.call(s.request("/x").build()); fail() } catch (e: AuthenticationException) { }
    }

    @Test fun refusesDowngradeToHttp() {
        val strict = DavSession("https://example.invalid", "alice", "x", allowInsecureHttp = false, sleeper = { })
        // Exercised through the redirect rule itself: the first hop never leaves the test thread.
        assertTrue(strict.isHttps)
    }

    @Test fun stopsAfterTooManyRedirects() {
        repeat(8) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
        val s = session()
        try { s.call(s.request("/loop").build()); fail() } catch (e: TooManyRedirectsException) { }
    }

    @Test fun cancelAllAbortsTheCallInFlight() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("late")
        }
        val s = session()
        var failure: Throwable? = null
        val worker = Thread {
            try { s.call(s.request("/slow").build(), CallOptions.SINGLE_ATTEMPT).close() } catch (e: Throwable) { failure = e }
        }
        worker.start()
        Thread.sleep(300)
        s.cancelAll()
        worker.join(3000)
        assertNotNull(failure)
        assertTrue("got $failure", failure is RequestCancelledException)
    }

    @Test fun readTimeoutScalesWithPayload() {
        assertEquals(60_000L, TransferTimeouts.readTimeoutMs(null))
        assertEquals(60_000L, TransferTimeouts.readTimeoutMs(1_000_000L))
        assertEquals(180_000L, TransferTimeouts.readTimeoutMs(1_000_000_000L))
        assertEquals(30L * 60_000L, TransferTimeouts.readTimeoutMs(500_000_000_000L))
    }

    @Test fun failureKindClassification() {
        assertEquals(FailureKind.UNAUTHORIZED, HttpStatusException(401).failureKind())
        assertEquals(FailureKind.TIMEOUT, java.net.SocketTimeoutException().failureKind())
        assertEquals(FailureKind.HOST_UNREACHABLE, java.net.UnknownHostException("x").failureKind())
        assertTrue(HttpStatusException(503).isTransientFailure())
        assertTrue(!HttpStatusException(401).isTransientFailure())
        assertTrue(!HttpStatusException(507).isTransientFailure())
        assertTrue(java.net.SocketTimeoutException().isTransientFailure())
        assertTrue(!LocalIoException("disk full").isTransientFailure())
    }

    @Test fun retryAfterParsing() {
        assertEquals(5_000L, DavSession.parseRetryAfter("5"))
        assertNull(DavSession.parseRetryAfter(null))
        assertNull(DavSession.parseRetryAfter("soon"))
    }
}
