package dev.kobbler.app.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun createSharedEngine(): HttpClientEngine = Darwin.create {
    configureSession { HTTPMaximumConnectionsPerHost = 16 }
}
