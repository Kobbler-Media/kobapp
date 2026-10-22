package dev.kobbler.app.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

// OkHttp allows 5 calls per host by default, and everything here goes to one host: the
// event stream holds one for good and a screen of posters wants the rest at once.
internal actual fun createSharedEngine(): HttpClientEngine = OkHttp.create {
    config {
        dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        retryOnConnectionFailure(true)
        followRedirects(true)
        followSslRedirects(true)
        pingInterval(30, TimeUnit.SECONDS)
    }
}
