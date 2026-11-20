package dev.kobbler.app.platform

import dev.kobbler.app.data.AppForeground
import dev.kobbler.app.data.SharedHttp
import dev.kobbler.app.platform.dlna.DlnaBackend
import dev.kobbler.app.platform.dlna.UdpSsdpTransport

// Tests set this so a composed button never multicasts onto the LAN of whoever runs them.
private val discovers = System.getProperty("kobbler.dlna.discovery") != "off"

private val router by lazy {
    CastRouter(
        listOf(
            DlnaBackend(
                http = SharedHttp.apiClient(),
                openTransport = { if (discovers) UdpSsdpTransport.open() else null },
                foreground = AppForeground.state,
            ),
        ),
    )
}

actual fun castManager(): CastRouter = router

actual fun saveCastAppId(appId: String?) {}
