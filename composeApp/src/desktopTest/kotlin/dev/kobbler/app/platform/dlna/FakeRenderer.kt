package dev.kobbler.app.platform.dlna

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A MediaRenderer across the room: a description, an AVTransport and a ConnectionManager. */
internal class FakeRenderer(val name: String = "Fake TV") : AutoCloseable {
    val actions = CopyOnWriteArrayList<String>()
    val uris = CopyOnWriteArrayList<String>()
    val metadata = CopyOnWriteArrayList<String>()
    val seeks = CopyOnWriteArrayList<String>()
    @Volatile var state = "NO_MEDIA_PRESENT"
    @Volatile var positionSec = 0

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/desc.xml") { exchange ->
            exchange.reply(description())
        }
        createContext("/ctl/") { exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPACTION").orEmpty().trim('"').substringAfter('#')
            val body = exchange.requestBody.readBytes().decodeToString()
            actions += action
            val out = when (action) {
                "SetAVTransportURI" -> {
                    // Reset before recording: a test waiting on the URI must not see the old stream's state.
                    positionSec = 0
                    state = "STOPPED"
                    metadata += arg(body, "CurrentURIMetaData")
                    uris += arg(body, "CurrentURI")
                    ""
                }
                "Play" -> { state = "PLAYING"; "" }
                "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
                "Stop" -> { state = "STOPPED"; "" }
                "Seek" -> { seeks += arg(body, "Target"); positionSec = parseUpnpTime(arg(body, "Target"))!!.toInt(); "" }
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
                "GetPositionInfo" -> "<Track>1</Track><TrackDuration>0:00:00</TrackDuration><RelTime>${upnpTime(positionSec)}</RelTime>"
                "GetProtocolInfo" -> "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:audio/vnd.dolby.dd-raw:*</Sink>"
                else -> null
            }
            if (out == null) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                exchange.reply(
                    """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                        """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$out</u:${action}Response></s:Body></s:Envelope>""",
                )
            }
        }
        executor = Executors.newCachedThreadPool()
        start()
    }

    val location: String get() = "http://127.0.0.1:${server.address.port}/desc.xml"

    /** Answers a search with this renderer, the way one on the LAN would. */
    fun ssdp(): SsdpTransport = object : SsdpTransport {
        private val replies = Channel<String>(Channel.UNLIMITED)
        override suspend fun search(message: String) {
            replies.send(
                "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\n" +
                    "ST: $MediaRendererType\r\nUSN: uuid:fake-tv::$MediaRendererType\r\n\r\n",
            )
        }
        override val datagrams: Flow<String> = replies.receiveAsFlow()
        override fun close() { replies.close() }
    }

    private fun description() = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
          <friendlyName>$name</friendlyName><UDN>uuid:fake-tv</UDN>
          <serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/ctl/av</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ctl/cm</controlURL></service>
          </serviceList>
        </device></root>"""

    private fun arg(body: String, name: String): String =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun com.sun.net.httpserver.HttpExchange.reply(body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "text/xml")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
