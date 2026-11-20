package dev.kobbler.app.platform.dlna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What renderers say on the wire, as real ones phrase it. */
class DlnaParsingTest {

    @Test
    fun `search replies and notifies for a MediaRenderer are read, everything else is not`() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
            "LOCATION: http://192.168.1.20:9197/dmr\r\nSERVER: Samsung UPnP SDK/1.0\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:0a1b::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\ncache-control: max-age = 900\r\n" +
            "location: http://10.0.0.5:49152/description.xml\r\nnt: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "nts: ssdp:alive\r\nusn: uuid:lg::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val byebye = "NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\nNTS: ssdp:byebye\r\n" +
            "USN: uuid:lg::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val server = "NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:device:MediaServer:1\r\nNTS: ssdp:alive\r\n" +
            "USN: uuid:nas::urn:schemas-upnp-org:device:MediaServer:1\r\nLOCATION: http://nas/desc.xml\r\n\r\n"
        val search = mSearch()

        assertEquals(SsdpAnnouncement("uuid:0a1b::urn:schemas-upnp-org:device:MediaRenderer:1", "http://192.168.1.20:9197/dmr", true, 1800), parseSsdp(reply))
        assertEquals(SsdpAnnouncement("uuid:lg::urn:schemas-upnp-org:device:MediaRenderer:1", "http://10.0.0.5:49152/description.xml", true, 900), parseSsdp(alive))
        assertEquals(false, parseSsdp(byebye)?.alive)
        assertNull(parseSsdp(server))
        assertNull(parseSsdp(search), "our own search echoed back")
        assertTrue(search.startsWith("M-SEARCH * HTTP/1.1\r\n") && "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" in search)
    }

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Living Room</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <UDN>uuid:0a1b</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/upnp/control/ConnectionManager1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `a description names the renderer and where to drive it`() {
        val renderer = assertNotNull(parseDeviceDescription(description, "http://192.168.1.20:9197/dmr"))
        assertEquals("uuid:0a1b", renderer.udn)
        assertEquals("[TV] Living Room", renderer.friendlyName)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", renderer.avTransport.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", renderer.avTransport.type)
        assertEquals("http://192.168.1.20:9197/upnp/control/ConnectionManager1", renderer.connectionManager?.controlUrl)
    }

    @Test
    fun `a renderer nested in a root device, with a URLBase, is still found`() {
        val nested = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://10.0.0.5:49152/</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
                <friendlyName>Receiver</friendlyName>
                <deviceList><device>
                  <deviceType>urn:schemas-upnp-org:device:MediaRenderer:2</deviceType>
                  <friendlyName>Receiver Renderer</friendlyName>
                  <UDN>uuid:avr</UDN>
                  <serviceList><service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
                    <controlURL>AVTransport/ctrl</controlURL>
                  </service></serviceList>
                </device></deviceList>
              </device>
            </root>
        """.trimIndent()
        val renderer = assertNotNull(parseDeviceDescription(nested, "http://10.0.0.5:49152/desc/root.xml"))
        assertEquals("Receiver Renderer", renderer.friendlyName)
        assertEquals("http://10.0.0.5:49152/AVTransport/ctrl", renderer.avTransport.controlUrl)
        assertNull(renderer.connectionManager)
    }

    @Test
    fun `a description that is not a renderer, or not XML, or smuggles a DTD, is no renderer`() {
        assertNull(parseDeviceDescription(description.replace("MediaRenderer", "MediaServer"), "http://x/"))
        assertNull(parseDeviceDescription("not xml", "http://x/"))
        val dtd = """<?xml version="1.0"?><!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
            description.substringAfter("?>").replace("[TV] Living Room", "&x;")
        assertNull(parseDeviceDescription(dtd, "http://x/"))
    }

    @Test
    fun `SOAP responses and times read the way renderers write them`() {
        val response = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track><TrackDuration>0:44:10</TrackDuration><RelTime>00:01:02.500</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        val out = parseSoapResponse(response, "GetPositionInfo")
        assertEquals(62.5, parseUpnpTime(out["RelTime"]))
        assertEquals(2650.0, parseUpnpTime(out["TrackDuration"]))
        assertNull(parseUpnpTime("NOT_IMPLEMENTED"))
        assertEquals("1:02:03", upnpTime(3723))
        assertTrue("<Target>0:00:05</Target>" in soapEnvelope(AvTransportType, "Seek", listOf("Target" to upnpTime(5))))
        assertTrue("&lt;DIDL" in soapEnvelope(AvTransportType, "SetAVTransportURI", listOf("CurrentURIMetaData" to "<DIDL")))
    }
}
