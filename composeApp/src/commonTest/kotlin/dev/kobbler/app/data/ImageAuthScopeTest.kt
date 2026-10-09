package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageAuthScopeTest {

    private fun profile(id: String, apiUrl: String, token: String) =
        Profile(id = id, token = token, apiUrl = apiUrl)

    private val home = profile("home", "https://kob.example.test", "home-token")
    private val away = profile("away", "http://192.0.2.4:8080", "away-token")

    @Test
    fun anArtworkUrlOnTheCurrentServerCarriesItsToken() {
        assertEquals(
            "home-token",
            imageAuthToken("https://kob.example.test/api/v1/media/1/poster", home, listOf(home, away)),
        )
    }

    @Test
    fun aThirdPartyCdnNeverCarriesTheToken() {
        assertNull(
            imageAuthToken("https://img.hdhomerun.com/channels/AV104679.png", home, listOf(home, away)),
        )
    }

    @Test
    fun aLinkedProfilesHostCarriesThatProfilesOwnToken() {
        assertEquals(
            "away-token",
            imageAuthToken("http://192.0.2.4:8080/api/v1/users/2/avatar", null, listOf(home, away)),
        )
    }

    @Test
    fun aHostThatMerelyStartsWithTheServerNameIsNotTheServer() {
        assertNull(
            imageAuthToken("https://kob.example.test.evil.test/poster.png", home, listOf(home, away)),
        )
    }

    @Test
    fun aTrailingSlashOnTheConfiguredUrlStillMatches() {
        val slashed = profile("home", "https://kob.example.test/", "home-token")
        assertEquals(
            "home-token",
            imageAuthToken("https://kob.example.test/api/v1/media/1/poster", slashed, listOf(slashed)),
        )
    }
}
