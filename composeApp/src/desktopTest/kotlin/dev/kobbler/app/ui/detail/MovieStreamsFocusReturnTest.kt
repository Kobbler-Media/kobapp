package dev.kobbler.app.ui.detail

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.ui.detail.tvshow.detailPage
import dev.kobbler.app.ui.detail.tvshow.assertFocusSettlesOn
import dev.kobbler.app.ui.detail.tvshow.focusedText
import dev.kobbler.app.ui.detail.tvshow.moveUntil
import dev.kobbler.app.ui.detail.tvshow.press
import dev.kobbler.app.ui.navigation.Destination
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MovieStreamsFocusReturnTest {

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/movies/m1" to { _ -> """{"id":"m1","title":"Film","year":2020}""" },
        "/api/v1/admin/stream/search" to { _ ->
            """{"options":[{"id":"o1","release_title":"Film.2020.1080p","debrid_provider":"rd","debrid_provider_id":"p1"}]}"""
        },
    )

    @Test
    fun `closing a movie's stream options returns to the button that opened them`() =
        detailPage(Destination.MovieDetail("m1"), routes, canStream = true) {
            moveUntil(Key.DirectionRight, { it == "Streams" })
            assertEquals("Streams", focusedText())
            press(Key.Enter)
            waitUntil(timeoutMillis = UiTimeoutMs) { focusedText()?.contains("Film.2020.1080p") == true }
            press(Key.Back)
            assertFocusSettlesOn("Streams")
        }
}
