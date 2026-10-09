package dev.kobbler.app

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient

/**
 * Dev harness for TestBookActivity: an offline [KobblerApi] for opening a packaged
 * book without a server. Progress calls degrade quietly (readerProgressOrNull is
 * quiet; the save on exit is fire-and-forget).
 */
fun offlineTestApi(): KobblerApi = KobblerApi(HttpClient(), "http://127.0.0.1:9", "test")