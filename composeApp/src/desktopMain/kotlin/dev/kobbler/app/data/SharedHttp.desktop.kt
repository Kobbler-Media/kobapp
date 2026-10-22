package dev.kobbler.app.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java

internal actual fun createSharedEngine(): HttpClientEngine = Java.create {
    config { followRedirects(java.net.http.HttpClient.Redirect.NORMAL) }
}
