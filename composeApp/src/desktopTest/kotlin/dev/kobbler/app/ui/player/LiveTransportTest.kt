package dev.kobbler.app.ui.player

import dev.kobbler.app.data.LiveBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live seek bar spans the ring buffer, so a seek is either a step back into the
 * buffer or a no-op at the edge. There is no network catch-up behind it: the buffer
 * starts when the tuner did, which is what the programme region has to survive.
 */
class LiveTransportTest {

    private val bufferMs = 1_800_000L // tuned in 30 minutes ago

    @Test
    fun seeking_to_the_live_edge_stays_on_the_raw_stream() {
        assertNull(timeshiftTargetSeconds(bufferMs, bufferMs))
    }

    /**
     * The bar steps 10 s per press and the edge slack is 12 s, so every single press
     * asked to land inside the slack and was answered with "you are already live" — the
     * one press a viewer makes to step back did nothing at all, and the 10 s rewind
     * button could never work on a live tune. A press that means "go back" goes back; it
     * is only held off the segment the server is still writing.
     */
    @Test
    fun one_step_back_from_the_edge_enters_the_buffer() {
        val step = timeshiftTargetSeconds(bufferMs - 10_000, bufferMs)
        assertEquals((bufferMs - AtLiveEdgeSeconds * 1000) / 1000, step?.toLong())
    }

    @Test
    fun a_seek_just_shy_of_the_edge_is_still_the_edge() {
        // HLS hands out whole segments, so a couple of seconds back is where we already are.
        assertNull(timeshiftTargetSeconds(bufferMs - 4_000, bufferMs))
    }

    @Test
    fun seeking_back_lands_on_that_buffer_offset() {
        assertEquals(600, timeshiftTargetSeconds(600_000, bufferMs))
        assertEquals(0, timeshiftTargetSeconds(0, bufferMs))
    }

    // A rewind that reloads the player tears the video surface down and re-parses the
    // source, which is the "it switched to another track" a step back used to show. It is
    // only actually necessary when the thing being played is not already the buffer.
    private val playlist = "https://kob/api/v1/admin/livetv/sessions/s1/stream.m3u8"
    private val rawTs = "https://kob/api/v1/admin/livetv/channels/c1/stream.ts?session=s1"

    @Test
    fun a_direct_tune_has_to_swap_off_the_raw_transport_stream() {
        // The raw TS carries no timeline, so there is nothing in it to seek.
        assertTrue(timeshiftNeedsSourceSwap(false, rawTs, playlist))
    }

    @Test
    fun a_transcoded_tune_is_already_playing_the_buffer() {
        // Its stream URL *is* the playlist, so the reload swapped a source for itself.
        assertFalse(timeshiftNeedsSourceSwap(false, playlist, playlist))
    }

    @Test
    fun a_second_rewind_never_reloads() {
        assertFalse(timeshiftNeedsSourceSwap(true, playlist, playlist))
        assertFalse(timeshiftNeedsSourceSwap(true, rawTs, playlist))
    }

    @Test
    fun a_player_that_has_loaded_nothing_still_swaps() {
        assertTrue(timeshiftNeedsSourceSwap(false, null, playlist))
    }

    // now/next is read once at tune-in. Left there, the player names the programme that
    // was on when the channel opened for as long as it stays open, and Record sends its id.
    private val at = Instant.parse("2026-08-30T20:00:00Z")

    @Test
    fun the_refresh_lands_just_after_the_programme_ends() {
        assertEquals(
            3.minutes + 5.seconds,
            nextNowRefreshDelay("2026-08-30T20:03:00Z", at),
        )
    }

    @Test
    fun an_end_time_already_behind_us_waits_the_floor_rather_than_spinning() {
        assertEquals(20.seconds, nextNowRefreshDelay("2026-08-30T19:40:00Z", at))
    }

    @Test
    fun a_channel_with_no_guide_still_gets_checked_eventually() {
        assertEquals(10.minutes, nextNowRefreshDelay(null, at))
        assertEquals(10.minutes, nextNowRefreshDelay("not a timestamp", at))
    }

    @Test
    fun a_programme_hours_long_is_still_re_read_within_the_ceiling() {
        assertEquals(10.minutes, nextNowRefreshDelay("2026-08-31T00:00:00Z", at))
    }

    private fun state(programmeStart: String, bufferStart: String = "2026-08-30T09:20:00Z") =
        GlobalPlayerState().apply {
            isLive = true
            liveTimeshift = false
            liveBuffer = LiveBuffer(
                startTime = bufferStart,
                endTime = "2026-08-30T09:50:00Z",
                liveEdgeSeconds = 1800.0,
                durationSeconds = 1800.0,
                retainedSeconds = 1800.0,
                capSeconds = 10800.0,
            )
            liveNowStart = programmeStart
            liveNowEnd = "2026-08-30T09:50:00Z"
        }

    @Test
    fun the_programme_region_marks_where_it_starts_inside_the_buffer() {
        // Buffer opened 09:20, programme began 09:25 — five minutes into a thirty
        // minute buffer, so a sixth of the way along.
        val region = liveProgrammeRegion(
            state("2026-08-30T09:25:00Z"),
            bufferMs,
            androidx.compose.ui.graphics.Color.White,
        )
        assertTrue(region != null, "a programme inside the buffer gets a region")
        assertEquals(1f / 6f, region.startFraction, 0.01f)
        assertEquals(1f, region.endFraction, 0.01f)
    }

    @Test
    fun a_programme_that_began_before_the_tune_is_clamped_not_dropped() {
        // Joined at 09:20 on a programme that started at 09:00. The first twenty
        // minutes were never received by anything, so the region starts at the
        // buffer's own start rather than off the left of the bar.
        val region = liveProgrammeRegion(
            state("2026-08-30T09:00:00Z"),
            bufferMs,
            androidx.compose.ui.graphics.Color.White,
        )
        assertTrue(region != null, "the part that was received is still markable")
        assertEquals(0f, region.startFraction, 0.001f)
    }
}
