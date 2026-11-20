package dev.kobbler.app.platform.dlna

import java.net.URI

internal const val AvTransportType = "urn:schemas-upnp-org:service:AVTransport:1"
internal const val ConnectionManagerType = "urn:schemas-upnp-org:service:ConnectionManager:1"

internal data class ServiceEndpoint(val type: String, val controlUrl: String)

/** A MediaRenderer as its description file has it: enough to name it and drive it. */
internal data class DlnaRenderer(
    val udn: String,
    val friendlyName: String,
    val location: String,
    val avTransport: ServiceEndpoint,
    val connectionManager: ServiceEndpoint?,
)

/** Null when the description names no MediaRenderer with an AVTransport to drive. */
internal fun parseDeviceDescription(xml: String, location: String): DlnaRenderer? {
    val root = parseXml(xml) ?: return null
    val base = root.text("URLBase") ?: location
    val device = root.descendants("device").firstOrNull { d ->
        d.text("deviceType")?.startsWith("urn:schemas-upnp-org:device:MediaRenderer:") == true
    } ?: return null
    val services = device.child("serviceList")?.children("service").orEmpty()
    fun endpoint(typePrefix: String): ServiceEndpoint? = services.firstOrNull { s ->
        s.text("serviceType")?.startsWith(typePrefix.substringBeforeLast(':')) == true
    }?.let { s ->
        val control = s.text("controlURL") ?: return@let null
        ServiceEndpoint(s.text("serviceType")!!, resolve(base, control) ?: return@let null)
    }
    val avTransport = endpoint(AvTransportType) ?: return null
    return DlnaRenderer(
        udn = device.text("UDN") ?: location,
        friendlyName = device.text("friendlyName") ?: device.text("modelName") ?: "DLNA renderer",
        location = location,
        avTransport = avTransport,
        connectionManager = endpoint(ConnectionManagerType),
    )
}

private fun resolve(base: String, path: String): String? =
    runCatching { URI(base).resolve(path.trim()).toString() }.getOrNull()

