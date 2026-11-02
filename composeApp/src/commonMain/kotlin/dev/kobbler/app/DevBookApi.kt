package dev.kobbler.app

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient

/**
 * Dev harness for TestBookActivity: a [KobblerApi] over a client that answers from the
 * packaged test book instead of a server. Progress calls degrade quietly
 * (readerProgressOrNull is quiet; the save on exit is fire-and-forget).
 */
fun devBookApi(httpClient: HttpClient): KobblerApi = KobblerApi(httpClient, DevBookOrigin, "test")

const val DevBookOrigin = "http://127.0.0.1:9"
