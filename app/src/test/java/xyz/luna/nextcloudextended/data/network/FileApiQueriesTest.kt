package xyz.luna.nextcloudextended.data.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Request shape of the read-only queries: favourites, server-side search, user id based roots. */
class FileApiQueriesTest {
    private lateinit var server: MockWebServer
    private lateinit var api: FileApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = FileApi(DavSession(server.url("/nextcloud").toString().trimEnd('/'), "u-alice", "Basic x", allowInsecureHttp = true, sleeper = { }))
    }

    @After fun tearDown() { server.shutdown() }

    private val answer = """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
      <d:response><d:href>/nextcloud/remote.php/dav/files/u-alice/Docs/Plan%20B+.pdf</d:href><d:propstat><d:prop>
        <d:getcontentlength>10</d:getcontentlength><d:resourcetype/><d:getetag>"abc-gzip"</d:getetag><oc:fileid>5</oc:fileid>
        <oc:favorite>1</oc:favorite><nc:has-preview>true</nc:has-preview><oc:permissions>RGDNVW</oc:permissions></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
      <d:response><d:href>/nextcloud/remote.php/dav/files/u-alice/Photos/</d:href><d:propstat><d:prop>
        <d:resourcetype><d:collection/></d:resourcetype><oc:size>2048</oc:size><oc:favorite>1</oc:favorite></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
    </d:multistatus>"""

    @Test fun favoritesIssueTheFilterReportAndStripTheSubFolder() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(answer))
        val favorites = api.favorites()
        val request = server.takeRequest()
        assertEquals("REPORT", request.method)
        assertEquals("/nextcloud/remote.php/dav/files/u-alice/", request.path)
        assertTrue(request.body.readUtf8().contains("<oc:favorite>1</oc:favorite>"))

        assertEquals(listOf("Photos", "Plan B+.pdf"), favorites.map { it.name })
        val pdf = favorites.last()
        assertEquals("/remote.php/dav/files/u-alice/Docs/Plan B+.pdf", pdf.path)
        assertEquals("abc", pdf.etag)
        assertTrue(pdf.favorite && pdf.hasPreview)
        assertEquals(2048L, favorites.first().size)
    }

    @Test fun searchScopesTheQueryToTheFolderAndTheAccountId() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(answer))
        val results = api.search("plan", "/remote.php/dav/files/u-alice/Docs/")
        val request = server.takeRequest()
        assertEquals("SEARCH", request.method)
        assertEquals("/nextcloud/remote.php/dav/", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("<d:href>/files/u-alice/Docs</d:href>"))
        assertTrue(body.contains("%plan%"))
        assertEquals(2, results.size)
    }

    @Test fun blankSearchDoesNotHitTheServer() {
        assertTrue(api.search("   ").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun searchEscapesXmlInTheQuery() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
        api.search("a&b <c>")
        assertTrue(server.takeRequest().body.readUtf8().contains("%a&amp;b &lt;c&gt;%"))
    }
}
