package xyz.luna.nextcloudextended.data.network

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import xyz.luna.nextcloudextended.data.model.NextcloudShare

class ServerAccessTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    // ── URL normalisation ───────────────────────────────────────────────────────────────────

    @Test fun normalizesWhatUsersPaste() {
        assertEquals("https://cloud.example.com", normalizeServerInput("cloud.example.com"))
        assertEquals("https://cloud.example.com", normalizeServerInput("  HTTPS://cloud.example.com/  "))
        assertEquals("https://cloud.example.com", normalizeServerInput("https://cloud.example.com/index.php/apps/files/"))
        assertEquals("https://cloud.example.com", normalizeServerInput("https://cloud.example.com/remote.php/dav/files/alice"))
        assertEquals("https://cloud.example.com", normalizeServerInput("https://cloud.example.com/login?redirect_url=/x"))
        assertEquals("https://example.com/nextcloud", normalizeServerInput("example.com/nextcloud/index.php/login"))
        assertEquals("https://example.com/nextcloud", normalizeServerInput("https://example.com/nextcloud/"))
        assertEquals("", normalizeServerInput("   "))
    }

    // ── status.php ──────────────────────────────────────────────────────────────────────────

    private fun serve(vararg handlers: Pair<String, (RecordedRequest) -> MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                handlers.firstOrNull { request.path!!.substringBefore('?') == it.first }?.second?.invoke(request)
                    ?: MockResponse().setResponseCode(404)
        }
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test fun probeReadsVersionAndMaintenanceFlag() {
        serve("/status.php" to { MockResponse().setBody("""{"installed":true,"maintenance":false,"needsDbUpgrade":false,"version":"29.0.4.1","versionstring":"29.0.4","productname":"Nextcloud"}""") })
        val status = ServerAccess.probe(baseUrl() + "/index.php/apps/files/", allowHttp = true)
        assertEquals("29.0.4", status.version)
        assertTrue(status.installed)
        assertEquals(baseUrl(), status.baseUrl)
    }

    @Test fun probeFollowsRedirectsAndReportsTheFinalBaseUrl() {
        serve(
            "/status.php" to { MockResponse().setResponseCode(301).setHeader("Location", "/cloud/status.php") },
            "/cloud/status.php" to { MockResponse().setBody("""{"installed":true,"maintenance":false,"version":"30.0.0.1"}""") }
        )
        val status = ServerAccess.probe(baseUrl(), allowHttp = true)
        assertEquals(baseUrl() + "/cloud", status.baseUrl)
    }

    @Test fun probeRejectsProxyPagesAndWrongHosts() {
        serve("/status.php" to { MockResponse().setBody("<html>Welcome to nginx</html>") })
        try { ServerAccess.probe(baseUrl(), allowHttp = true); fail() } catch (e: NotNextcloudException) { }
        serve("/status.php" to { MockResponse().setResponseCode(404) })
        try { ServerAccess.probe(baseUrl(), allowHttp = true); fail() } catch (e: NotNextcloudException) { }
    }

    @Test fun probeReportsUntrustedDomain() {
        serve("/status.php" to { MockResponse().setResponseCode(400).setBody("""{"code":15,"message":"Trusted domain error."}""") })
        try { ServerAccess.probe(baseUrl(), allowHttp = true); fail() } catch (e: UntrustedDomainException) {
            assertEquals(FailureKind.UNTRUSTED_DOMAIN, e.failureKind())
        }
    }

    @Test fun probeSurfacesMaintenanceMode() {
        serve("/status.php" to { MockResponse().setBody("""{"installed":true,"maintenance":true,"version":"29.0.4.1"}""") })
        assertTrue(ServerAccess.probe(baseUrl(), allowHttp = true).maintenance)
    }

    // ── Login flow v2 ───────────────────────────────────────────────────────────────────────

    @Test fun loginFlowStartsPollsAndReturnsTheAppPassword() {
        var polls = 0
        serve(
            "/index.php/login/v2" to {
                assertTrue("a User-Agent is what names the device in Devices & sessions", it.getHeader("User-Agent")!!.contains("Nextcloud"))
                MockResponse().setBody("""{"poll":{"token":"tok","endpoint":"${baseUrl()}/index.php/login/v2/poll"},"login":"${baseUrl()}/index.php/login/v2/flow/abc"}""")
            },
            "/index.php/login/v2/poll" to {
                assertEquals("token=tok", it.body.readUtf8())
                if (++polls < 3) MockResponse().setResponseCode(404)
                else MockResponse().setBody("""{"server":"${baseUrl()}","loginName":"alice","appPassword":"s3cret-app-pass"}""")
            }
        )
        val flow = LoginFlowV2(baseUrl(), allowHttp = true)
        val start = flow.start()
        assertTrue(start.loginUrl.endsWith("/flow/abc"))
        val result = flow.awaitResult(start, timeoutMs = 10_000, intervalMs = 1, sleeper = { })
        assertNotNull(result)
        assertEquals("alice", result!!.loginName)
        assertEquals("s3cret-app-pass", result.appPassword)
        assertEquals(3, polls)
    }

    @Test fun loginFlowSurvivesAFlakyConnectionWhilePolling() {
        var polls = 0
        serve(
            "/index.php/login/v2" to { MockResponse().setBody("""{"poll":{"token":"t","endpoint":"${baseUrl()}/p"},"login":"${baseUrl()}/l"}""") },
            "/p" to {
                when (++polls) {
                    1 -> MockResponse().setResponseCode(503)
                    2 -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else -> MockResponse().setBody("""{"server":"${baseUrl()}","loginName":"bob","appPassword":"pw"}""")
                }
            }
        )
        val flow = LoginFlowV2(baseUrl(), allowHttp = true)
        val result = flow.awaitResult(flow.start(), timeoutMs = 10_000, intervalMs = 1, sleeper = { })
        assertEquals("bob", result?.loginName)
    }

    @Test fun loginFlowCanBeCancelled() {
        serve(
            "/index.php/login/v2" to { MockResponse().setBody("""{"poll":{"token":"t","endpoint":"${baseUrl()}/p"},"login":"${baseUrl()}/l"}""") },
            "/p" to { MockResponse().setResponseCode(404) }
        )
        val flow = LoginFlowV2(baseUrl(), allowHttp = true)
        var calls = 0
        assertNull(flow.awaitResult(flow.start(), timeoutMs = 10_000, intervalMs = 1, isCancelled = { ++calls > 3 }, sleeper = { }))
    }

    // ── OCS ─────────────────────────────────────────────────────────────────────────────────

    private fun ocs(): OcsApi = OcsApi(DavSession(baseUrl(), "alice", "Basic x", allowInsecureHttp = true, sleeper = { }))

    @Test fun currentUserReturnsTheRealAccountIdNotTheLoginName() {
        serve("/ocs/v2.php/cloud/user" to { MockResponse().setBody(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"id":"u-7f3a","display-name":"Alice A.","email":"alice@example.com","quota":{"free":900,"used":100,"total":1000,"relative":10.0}}}}""") })
        val user = ocs().currentUser()
        assertEquals("u-7f3a", user.id)
        assertEquals("Alice A.", user.displayName)
        assertEquals(1000L, user.quotaTotal)
    }

    @Test fun sharesAreParsedFromAJsonArrayAndTheLegacyElementWrapper() {
        val share = """{"id":"5","share_type":3,"share_with":null,"url":"https://c.example/s/abc","permissions":1,"expiration":"2030-01-01 00:00:00","label":"Team","note":""}"""
        serve("/ocs/v2.php/apps/files_sharing/api/v1/shares" to { MockResponse().setBody("""{"ocs":{"meta":{"statuscode":200},"data":[$share]}}""") })
        val shares = ocs().shares("/remote.php/dav/files/alice/Docs/a.txt")
        assertEquals(1, shares.size)
        assertEquals("https://c.example/s/abc", shares[0].url)
        assertEquals(NextcloudShare.TYPE_LINK, shares[0].shareType)
        assertEquals("Team", shares[0].label)
        val recorded = server.takeRequest()
        assertTrue("the DAV prefix is converted to a share path: ${recorded.path}", recorded.path!!.contains("path=%2FDocs%2Fa.txt"))

        serve("/ocs/v2.php/apps/files_sharing/api/v1/shares" to { MockResponse().setBody("""{"ocs":{"meta":{"statuscode":200},"data":{"element":[$share]}}}""") })
        assertEquals(1, ocs().shares("/Docs/a.txt").size)
    }

    @Test fun createShareSendsPasswordExpiryAndPermissions() {
        var form = ""
        serve("/ocs/v2.php/apps/files_sharing/api/v1/shares" to {
            form = it.body.readUtf8()
            MockResponse().setResponseCode(200).setBody("""{"ocs":{"meta":{"statuscode":200},"data":{"id":"9","share_type":3,"url":"https://c.example/s/zzz","permissions":1}}}""")
        })
        val created = ocs().createShare("/remote.php/dav/files/alice/x.txt", NextcloudShare.TYPE_LINK, permissions = 1, password = "pw!", expireDate = "2030-02-03")
        assertEquals("https://c.example/s/zzz", created.url)
        assertTrue(form.contains("path=%2Fx.txt") && form.contains("shareType=3") && form.contains("expireDate=2030-02-03") && form.contains("password=pw%21"))
    }

    @Test fun ocsFailuresBecomeTypedErrors() {
        serve("/ocs/v2.php/apps/files_sharing/api/v1/shares" to { MockResponse().setResponseCode(403).setBody(
            """{"ocs":{"meta":{"status":"failure","statuscode":403,"message":"Public upload disabled"},"data":[]}}""") })
        try { ocs().shares("/x"); fail() } catch (e: HttpStatusException) {
            assertEquals(403, e.code)
        }
        serve("/ocs/v2.php/apps/files_sharing/api/v1/shares" to { MockResponse().setBody("""{"ocs":{"meta":{"status":"failure","statuscode":404,"message":"Wrong path, file/folder doesn't exist"},"data":[]}}""") })
        try { ocs().shares("/x"); fail() } catch (e: HttpStatusException) {
            assertEquals(404, e.code)
            assertEquals("Wrong path, file/folder doesn't exist", e.davMessage)
        }
    }

    @Test fun deletingAnAlreadyRemovedShareSucceeds() {
        serve("/ocs/v2.php/apps/files_sharing/api/v1/shares/77" to { MockResponse().setResponseCode(404) })
        ocs().deleteShare("77")
    }

    @Test fun capabilitiesExposeChunkSizeAndFeatureFlags() {
        val json = JSONObject("""{"ocs":{"data":{"version":{"string":"29.0.4"},"capabilities":{"files":{"bigfilechunking":true,"chunked_upload":{"max_size":104857600},"undelete":true,"versioning":true,"comments":true},"files_sharing":{"api_enabled":true,"public":{"password":{"enforced_for":{"read_only":true}}}}}}}}""").toString()
        val caps = xyz.luna.nextcloudextended.data.network.CapabilitiesClient.parse(json)
        assertEquals(104857600L, caps.chunkedUploadMaxSize)
        assertTrue(caps.trashbin && caps.versioning && caps.comments && caps.linkPasswordEnforced)
        assertEquals(29, caps.majorVersion)
    }

    @Test fun notificationsAndActivityAreParsed() {
        serve(
            "/ocs/v2.php/apps/notifications/api/v2/notifications" to { MockResponse().setBody(
                """{"ocs":{"meta":{"statuscode":200},"data":[{"notification_id":12,"app":"files_sharing","datetime":"2025-01-01T10:00:00+00:00","subject":"Bob shared x","message":"","link":"https://c/l","actions":[{"label":"Accept","link":"https://c/a","type":"POST","primary":true}]}]}}""") },
            "/ocs/v2.php/apps/activity/api/v2/activity/all" to { MockResponse().setBody(
                """{"ocs":{"meta":{"statuscode":200},"data":[{"activity_id":3,"app":"files","type":"file_created","datetime":"2025-01-01T10:00:00+00:00","subject":"You created a.txt","message":"","object_type":"files","object_id":42,"object_name":"/a.txt","link":""}]}}""") }
        )
        val n = ocs().notifications().single()
        assertEquals(12L, n.id)
        assertEquals("Accept", n.actions.single().label)
        val a = ocs().activity().single()
        assertEquals(42L, a.objectId)
        assertEquals("You created a.txt", a.subject)
    }
}
