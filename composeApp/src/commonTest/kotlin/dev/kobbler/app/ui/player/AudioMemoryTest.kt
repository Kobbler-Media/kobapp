package dev.kobbler.app.ui.player

import dev.kobbler.app.data.UserSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioMemoryTest {

    private val race = playbackTitleKey("episode", "race", "motogp")
    private val other = playbackTitleKey("episode", "ep", "other")

    @AfterTest
    fun clear() = listOfNotNull(race, other).forEach { UserSettings.remove("audio_title:$it") }

    @Test
    fun `a picked track is remembered for its show alone`() {
        rememberAudioChoice(race, "Natural/Ambient")
        assertEquals("Natural/Ambient", rememberedAudioTitle(race))
        assertNull(rememberedAudioTitle(other))
    }

    @Test
    fun `an untitled track cannot be found again, so it clears the memory`() {
        rememberAudioChoice(race, "Commentary")
        rememberAudioChoice(race, null)
        assertNull(rememberedAudioTitle(race))
    }

    @Test
    fun `nothing is remembered without a title to key it`() {
        assertNull(playbackTitleKey("livetv", "chan", null))
        assertNull(playbackTitleKey("episode", "ep", null))
        rememberAudioChoice(null, "Commentary")
        assertNull(rememberedAudioTitle(null))
    }
}
