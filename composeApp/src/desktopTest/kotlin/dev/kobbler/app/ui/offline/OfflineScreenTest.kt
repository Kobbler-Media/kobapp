package dev.kobbler.app.ui.offline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.offline.ByteRanges
import dev.kobbler.app.offline.JvmOfflineFs
import dev.kobbler.app.offline.OfflineEngine
import dev.kobbler.app.offline.OfflineFile
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.offline.OfflineStore
import dev.kobbler.app.offline.Reachability
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.PosterCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class OfflineScreenTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = Files.createTempDirectory("offline-ui").toFile()
    private val engine = OfflineEngine(OfflineStore(JvmOfflineFs(root), scope), Reachability(scope), scope, startProxy = { null })

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun have(vararg items: OfflineItem) = engine.store.update { idx -> idx.copy(items = items.associateBy { it.key }) }

    private fun done(kind: OfflineKind, id: String, title: String, showId: String? = null, format: String? = null) =
        OfflineItem(kind, id, title, showId = showId, showTitle = showId?.let { "Show" }, bookFormat = format, state = OfflineState.Complete)

    @Test
    fun downloadsAreRowedByKindWithAShowStandingForItsEpisodes() = runUiTest(height = 3200) {
        have(
            done(OfflineKind.Movie, "m1", "Film"),
            done(OfflineKind.Episode, "e1", "One", showId = "s1"),
            done(OfflineKind.Episode, "e2", "Two", showId = "s1"),
            done(OfflineKind.Book, "c1", "Comic", format = "CBZ"),
            OfflineItem(
                OfflineKind.Audiobook, "a1", "Audio", state = OfflineState.Downloading,
                files = listOf(OfflineFile("audio/f", size = 100, ranges = ByteRanges() + (0L until 50L))),
            ),
        )
        var opened: String? = null
        setContent {
            KobappTheme {
                CompositionLocalProvider(LocalOfflineEngine provides engine) {
                    OfflineScreen(onOpenMovie = {}, onOpenShow = { opened = it }, onOpenBook = {})
                }
            }
        }
        listOf("Movies", "TV shows", "Comics", "Audiobooks", "2 episodes", "Downloading 50%").forEach {
            onNodeWithText(it, useUnmergedTree = true).assertExists(it)
        }
        onNodeWithText("Books", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Show", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals("s1", opened)
    }

    @Test
    fun rowsLineUpWithThePageHeading() = runUiTest {
        have(done(OfflineKind.Movie, "m1", "Film"))
        setContent {
            KobappTheme {
                CompositionLocalProvider(LocalOfflineEngine provides engine) {
                    OfflineScreen(onOpenMovie = {}, onOpenShow = {}, onOpenBook = {})
                }
            }
        }
        val heading = onNodeWithText("Offline", useUnmergedTree = true).getUnclippedBoundsInRoot().left
        assertEquals(heading.value, onNodeWithText("Film", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value, absoluteTolerance = 4f)
    }

    @Test
    fun withNothingDownloadedThePageSaysHowToStart() = runUiTest {
        setContent {
            KobappTheme {
                CompositionLocalProvider(LocalOfflineEngine provides engine) {
                    OfflineScreen(onOpenMovie = {}, onOpenShow = {}, onOpenBook = {})
                }
            }
        }
        onNodeWithText("Nothing downloaded yet", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aLibraryCardIsMarkedOnlyOnceItsDownloadIsComplete() = runUiTest {
        have(done(OfflineKind.Movie, "m1", "Kept"), OfflineItem(OfflineKind.Movie, "m2", "Halfway", state = OfflineState.Downloading))
        setContent {
            KobappTheme {
                CompositionLocalProvider(LocalOfflineEngine provides engine) {
                    androidx.compose.foundation.layout.Row {
                        listOf("m1", "m2", "m3").forEach { id ->
                            PosterCard(title = id, imageUrl = "", offline = isAvailableOffline(OfflineKind.Movie, id))
                        }
                    }
                }
            }
        }
        onAllNodesWithContentDescription("Available offline", useUnmergedTree = true).assertCountEquals(1)
    }
}
