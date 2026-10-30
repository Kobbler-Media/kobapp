package dev.kobbler.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

internal expect fun createSharedEngine(): HttpClientEngine

// One engine, so images, API calls, downloads and the event stream share a connection pool
// and TLS sessions to the server. A client built on a passed-in engine never closes it, so
// dropping a profile's clients cannot take the others down.
object SharedHttp {
    private val engine: HttpClientEngine by lazy { createSharedEngine() }

    // A guard against a hung connection, not a UX budget: screens show skeletons while they
    // wait and navigating away cancels, so err long. Bulk transfers use fileClient.
    fun apiClient(): HttpClient = client {
        requestTimeoutMillis = 60_000L
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = 30_000L
    }

    fun fileClient(): HttpClient = client {
        requestTimeoutMillis = BookFetchTimeoutMs
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = BookFetchTimeoutMs
    }

    // An idle event stream is the normal state, not a hung one.
    fun eventClient(): HttpClient = client {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }

    // Coil cancels a load the moment its tile scrolls away, so only a stalled socket needs
    // catching here; the token is looked up per request because the active profile changes.
    fun imageClient(tokenFor: (url: String) -> String?): HttpClient = client {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = 30_000L
    }.also { client ->
        client.requestPipeline.intercept(HttpRequestPipeline.State) {
            tokenFor(context.url.buildString())?.let { context.header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

    private fun client(timeouts: HttpTimeoutConfig.() -> Unit) = HttpClient(engine) {
        install(HttpTimeout, timeouts)
    }

    private const val CONNECT_TIMEOUT_MS = 15_000L
}

