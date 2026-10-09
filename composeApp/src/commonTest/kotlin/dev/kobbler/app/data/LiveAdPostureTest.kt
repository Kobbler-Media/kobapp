package dev.kobbler.app.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveAdPostureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun recording(extra: String) = json.decodeFromString<LiveRecordingListResponse>(
        """{"items":[{"id":"r1","channel_id":"c1","channel_name":"ABC","title":"News",
           "scheduled_start":"2026-08-30T09:00:00Z","scheduled_end":"2026-08-30T09:30:00Z",
           "status":"completed","seconds_on_disk":1800.0,$extra}],"total":1}""",
    ).items[0]

    @Test
    fun postureRidesOnTheRecording() {
        val rec = recording(""""ad_posture":"auto","ad_skip_disabled":false""")
        assertEquals(LiveAdPostureAuto, resolveAdPosture(rec.adPosture))
        assertFalse(rec.adSkipDisabled)
    }

    @Test
    fun newsAndSportCarryTheSuppressionFlag() {
        val rec = recording(""""ad_posture":"auto","ad_skip_disabled":true""")
        assertTrue(rec.adSkipDisabled)
        assertFalse(shouldAutoSkipAd(LiveAdPostureAuto, adSkipDisabled = true))
    }

    @Test
    fun autoSkipOnlyInAutoPosture() {
        assertTrue(shouldAutoSkipAd(LiveAdPostureAuto, adSkipDisabled = false))
        assertFalse(shouldAutoSkipAd(LiveAdPostureShade, adSkipDisabled = false))
        assertFalse(shouldAutoSkipAd(LiveAdPostureButton, adSkipDisabled = false))
    }

    @Test
    fun unknownOrAbsentPostureFallsBackToShade() {
        assertEquals(LiveAdPostureShade, resolveAdPosture(null))
        assertEquals(LiveAdPostureShade, resolveAdPosture("aggressive"))
    }
}
