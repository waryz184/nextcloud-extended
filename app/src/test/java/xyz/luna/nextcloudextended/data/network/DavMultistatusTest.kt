package xyz.luna.nextcloudextended.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DavMultistatusTest {

    private val filesXml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:response>
    <d:href>/remote.php/dav/files/alice/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getetag>"root"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/alice/a%2Bb%20c.txt</d:href>
    <d:propstat>
      <d:prop>
        <d:getlastmodified>Mon, 06 Oct 2025 10:00:00 GMT</d:getlastmodified>
        <d:getcontentlength>12</d:getcontentlength>
        <d:resourcetype/>
        <d:getetag>&quot;abc123&quot;</d:getetag>
        <oc:fileid>42</oc:fileid>
        <oc:favorite>1</oc:favorite>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
    <d:propstat>
      <d:prop><nc:has-preview/><oc:size/></d:prop>
      <d:status>HTTP/1.1 404 Not Found</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

    @Test fun parsesHrefsPropertiesAndIgnoresFailedPropstat() {
        val responses = DavMultistatus.parse(filesXml)
        assertEquals(2, responses.size)
        val file = responses[1]
        // %2B is a literal '+', and a bare '+' would stay a '+': neither may become a space.
        assertEquals("/remote.php/dav/files/alice/a+b c.txt", file.href)
        assertEquals("\"abc123\"", file.text(DavNs.DAV, "getetag"))
        assertEquals("42", file.text(DavNs.OC, "fileid"))
        assertEquals("1", file.text(DavNs.OC, "favorite"))
        assertNull("props from a 404 propstat must be dropped", file.prop(DavNs.NC, "has-preview"))
        assertFalse(file.isCollection)
        assertTrue(responses[0].isCollection)
    }

    @Test fun isIndependentOfNamespacePrefixes() {
        val xml = """<?xml version="1.0"?>
<D:multistatus xmlns:D="DAV:" xmlns:X="http://owncloud.org/ns">
  <D:response><D:href>/remote.php/dav/files/bob/f.txt</D:href>
    <D:propstat><D:prop><D:displayname>f.txt</D:displayname><X:fileid>7</X:fileid></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response></D:multistatus>"""
        val r = DavMultistatus.parse(xml).single()
        assertEquals("f.txt", r.text(DavNs.DAV, "displayname"))
        assertEquals("7", r.text(DavNs.OC, "fileid"))
    }

    @Test fun stripsSubFolderInstallPrefix() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/nextcloud/remote.php/dav/files/alice/x.txt</d:href>
            <d:propstat><d:prop><d:displayname>x.txt</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        assertEquals("/remote.php/dav/files/alice/x.txt", DavMultistatus.parse(xml, "/nextcloud").single().href)
    }

    @Test fun readsCalendarComponentsColorAndCdata() {
        val xml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
          <d:response><d:href>/remote.php/dav/calendars/alice/work/</d:href>
            <d:propstat><d:prop>
              <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
              <c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set>
              <a:calendar-color>#FF8800FF</a:calendar-color>
              <c:calendar-data><![CDATA[BEGIN:VCALENDAR
SUMMARY:R&D <review>
END:VCALENDAR]]></c:calendar-data>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val r = DavMultistatus.parse(xml).single()
        assertTrue(r.hasChild(DavNs.DAV, "resourcetype", DavNs.CALDAV, "calendar"))
        assertEquals(listOf("VEVENT", "VTODO"), r.prop(DavNs.CALDAV, "supported-calendar-component-set")!!.childNames["comp"])
        assertEquals("#FF8800FF", r.text(DavNs.APPLE, "calendar-color"))
        assertTrue(r.text(DavNs.CALDAV, "calendar-data")!!.contains("SUMMARY:R&D <review>"))
    }

    @Test fun rejectsNonXmlBodiesWithATypedError() {
        try {
            DavMultistatus.parse("<html><body>Captive portal login</body>")
            org.junit.Assert.fail("expected UnexpectedResponseException")
        } catch (expected: UnexpectedResponseException) { }
    }

    @Test fun percentDecodeKeepsPlusAndHandlesUtf8() {
        assertEquals("a+b", percentDecode("a+b"))
        assertEquals("é.txt", percentDecode("%C3%A9.txt"))
        assertEquals("100%", percentDecode("100%"))
        assertEquals("😀", percentDecode("%F0%9F%98%80"))
    }
}
