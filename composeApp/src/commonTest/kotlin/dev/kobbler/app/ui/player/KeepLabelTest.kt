package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KeepProgress
import dev.kobbler.app.data.KeepState
import kotlin.test.Test
import kotlin.test.assertEquals

class KeepLabelTest {

    @Test
    fun `keep reads as an offer, then quiet progress, then done`() {
        listOf(
            null to "Keep in library",
            KeepProgress(KeepState.Queued) to "Saving to library…",
            KeepProgress(KeepState.Downloading, 0.4f) to "Saving to library… 40%",
            KeepProgress(KeepState.Importing) to "Saving to library…",
            KeepProgress(KeepState.Done) to "Saved",
            KeepProgress(KeepState.Failed) to "Couldn't save to library",
        ).forEach { (progress, label) -> assertEquals(label, keepLabel(progress), progress.toString()) }
    }
}
