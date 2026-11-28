package dev.kobbler.app.ui.player

import dev.kobbler.app.platform.FormFactor
import kotlin.test.Test
import kotlin.test.assertEquals

class PictureInPictureSettingTest {

    @Test
    fun `only a phone defaults on, and a stored choice wins everywhere`() {
        val cases = listOf(
            Triple(FormFactor.Phone, null, true),
            Triple(FormFactor.TV, null, false),
            Triple(FormFactor.Desktop, null, false),
            Triple(FormFactor.Phone, "false", false),
            Triple(FormFactor.TV, "true", true),
            Triple(FormFactor.Desktop, "true", true),
            Triple(FormFactor.TV, "garbage", false),
        )
        cases.forEach { (formFactor, stored, expected) ->
            assertEquals(expected, pictureInPictureEnabled(formFactor, stored), "$formFactor with $stored")
        }
    }

    @Test
    fun `the mini player on back is off until asked for`() {
        listOf(null to false, "true" to true, "false" to false, "garbage" to false).forEach { (stored, expected) ->
            assertEquals(expected, miniPlayerOnBackEnabled(stored), "$stored")
        }
    }
}
