package dev.kobbler.app.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

internal actual fun createSharedEngine(): HttpClientEngine = Js.create()
