package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.scopeChip(): SemanticsNodeInteraction = onNodeWithTag(SearchScopeChipTag)

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.scopeOption(scope: SearchScope): SemanticsNodeInteraction =
    onNode(hasText(scope.label) and hasAnyAncestor(isPopup()))

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.scopeOptions(): List<String> =
    onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") }

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.pickScope(scope: SearchScope) {
    scopeChip().performClick()
    waitForIdle()
    scopeOption(scope).performClick()
    waitForIdle()
}
