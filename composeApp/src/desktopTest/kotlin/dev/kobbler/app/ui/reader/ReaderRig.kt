@file:OptIn(ExperimentalTestApi::class)

package dev.kobbler.app.ui.reader

import androidx.compose.runtime.MutableState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import java.util.UUID

/** A [ReaderScreen] on a stub server answering a pack of [chapters]; [test] gets the server and the book id. */
internal fun readerTest(
    progress: String,
    open: MutableState<Boolean>,
    chapters: List<TestChapter>,
    width: Int,
    height: Int,
    slowChapter: Int?,
    test: ComposeUiTest.(StubServer, String) -> Unit,
) {
    val id = "paging-${UUID.randomUUID()}"
    // A neighbour arriving after the current chapter is how a phone meets a long book.
    val routes = packRoutes(id, chapters).mapValues { (path, route) ->
        if (slowChapter != null && path.endsWith("/chapters/$slowChapter")) { exchange -> Thread.sleep(1_000); route(exchange) } else route
    }
    val server = StubServer(routes + ("/api/v1/admin/books/$id/reader-progress" to { _ -> progress }))
    try {
        runUiTest(width = width, height = height) {
            setContent {
                KobappTheme(formFactor = FormFactor.Desktop) {
                    if (open.value) {
                        ReaderScreen(
                            book = Book(id = id, title = "Paged Book", author = "A", coverUrl = ""),
                            api = KobblerApi(HttpClient(), server.url, "t"),
                            formFactor = FormFactor.Desktop,
                            onBack = {},
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { isShown("Paragraph") || isShown("chapter") }
            waitForIdle()
            test(server, id)
        }
    } finally {
        server.close()
    }
}

internal fun ComposeUiTest.isShown(text: String) =
    onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

internal fun ComposeUiTest.pageCounter(): Pair<Int, Int> {
    val label = onAllNodesWithText("Ch ", substring = true).fetchSemanticsNodes()
        .firstNotNullOf { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
    val (page, total) = Regex("""(\d+)/(\d+)  •  \d+%""").find(label)!!.destructured
    return page.toInt() to total.toInt()
}

internal fun ComposeUiTest.isSpinning() =
    onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isNotEmpty()

internal fun ComposeUiTest.assertShown(text: String) {
    waitUntil(timeoutMillis = UiTimeoutMs) { isShown(text) }
}

internal fun ComposeUiTest.press(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
    waitForIdle()
}
