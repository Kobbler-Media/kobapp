package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class IptvSearchPagingTest {

    private fun channels(prefix: String, count: Int, total: Int) = buildString {
        append("{\"total\":$total,\"items\":[")
        append((0 until count).joinToString(",") { "{\"id\":\"$prefix$it\",\"name\":\"$prefix channel $it\"}" })
        append("]}")
    }

    @Test
    fun `a search does not page in the directory it is hiding`() {
        val directoryPages = AtomicInteger()
        StubServer(
            mapOf(
                "/api/v1/admin/iptv/groups" to { _ -> "[]" },
                "/api/v1/admin/iptv/channels" to { ex ->
                    if ("search=" in (ex.requestURI.rawQuery ?: "")) {
                        channels("hit", count = 2, total = 2)
                    } else {
                        directoryPages.incrementAndGet()
                        channels("dir", count = 60, total = 5_000)
                    }
                },
            ),
        ).use { server ->
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(1280.dp, 720.dp)) {
                        FocusScopeHost(id = "test", store = FocusScopeStore()) {
                            KobappTheme(formFactor = FormFactor.Desktop) {
                                IptvScreen(api = KobblerApi(HttpClient(), server.url, "t"))
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { directoryPages.get() >= 1 }
                waitForIdle()
                Thread.sleep(300)
                waitForIdle()
                assertEquals(1, directoryPages.get(), "a full first page already paged in more")

                onNode(hasSetTextAction()).performTextInput("news")
                waitForIdle()
                Thread.sleep(800)
                waitForIdle()
                Thread.sleep(300)
                waitForIdle()
            }
        }
        assertEquals(1, directoryPages.get(), "searching fetched another page of the hidden directory")
    }
}
