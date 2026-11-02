package dev.kobbler.app.ui.detail.tvshow

import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.kobbler.app.platform.FormFactor
import kotlin.test.Test

/** A sport runs to hundreds of rounds; "everything before this one" is one press, not a season at a time. */
@OptIn(ExperimentalTestApi::class)
class EpisodeMenuTest {

    @Test
    fun `a long press on an episode marks everything before it watched`() = showPage(
        routes = mapOf("/api/v1/admin/tv/episodes/e3-3/watch-state/earlier" to { _ -> """{"updated_count":8}""" }),
        formFactor = FormFactor.Desktop,
    ) { stub ->
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("S3E3 title"))
        onAllNodesWithText("S3E3 title").onFirst().performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText("Mark earlier episodes watched").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/tv/episodes/e3-3/watch-state/earlier" in stub.requestBodies }
    }
}
