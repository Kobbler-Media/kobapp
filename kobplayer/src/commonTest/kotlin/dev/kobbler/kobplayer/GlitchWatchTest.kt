package dev.kobbler.kobplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlitchWatchTest {

    private class Run {
        val watch = GlitchWatch()
        var wallMs = 0L
        var clockMs = 600_000L
        var displayed = 0L
        var lost = 0L
        val found = mutableListOf<PlaybackGlitch.Kind>()

        fun second(clockStep: Long = 1_000, pictures: Long = 25, dropped: Long = 0, steady: Boolean = true, showsPicture: Boolean = true) {
            wallMs += 1_000
            clockMs += clockStep
            displayed += pictures
            lost += dropped
            watch.sample(PictureSample(wallMs, clockMs, displayed, lost, steady, showsPicture))?.let { found += it.kind }
        }

        fun seconds(n: Int, step: Run.() -> Unit) = repeat(n) { step() }
    }

    @Test
    fun `clean playback reports nothing`() {
        val run = Run()
        run.seconds(60) { second() }
        assertEquals(emptyList(), run.found)
    }

    @Test
    fun `a picture standing still while the clock runs is reported once`() {
        val run = Run()
        run.seconds(5) { second() }
        run.seconds(10) { second(pictures = 0) }
        assertEquals(listOf(PlaybackGlitch.Kind.PictureFrozen), run.found)
        run.seconds(5) { second() }
        run.seconds(5) { second(pictures = 0) }
        assertEquals(List(2) { PlaybackGlitch.Kind.PictureFrozen }, run.found)
    }

    @Test
    fun `no picture is expected while paused, buffering or with nothing to show`() {
        val run = Run()
        run.seconds(10) { second(clockStep = 0, pictures = 0) }
        run.seconds(10) { second(pictures = 0, steady = false) }
        run.seconds(10) { second(pictures = 0, showsPicture = false) }
        assertEquals(emptyList(), run.found)
    }

    @Test
    fun `a clock running fast for ten seconds is reported`() {
        val run = Run()
        run.seconds(10) { second() }
        run.seconds(20) { second(clockStep = 1_800, pictures = 25) }
        assertEquals(listOf(PlaybackGlitch.Kind.ClockRacing), run.found)
    }

    @Test
    fun `a seek is a jump, not a race`() {
        val run = Run()
        run.seconds(5) { second() }
        run.second(clockStep = -10_000)
        run.second(clockStep = 30_000)
        run.seconds(15) { second() }
        assertEquals(emptyList(), run.found)
    }

    @Test
    fun `pictures dropped for lateness are reported, a stray one is not`() {
        val run = Run()
        run.seconds(10) { second(dropped = 1) }
        assertEquals(emptyList(), run.found)
        run.seconds(20) { second(pictures = 15, dropped = 10) }
        assertEquals(listOf(PlaybackGlitch.Kind.PicturesDropped), run.found)
    }

    @Test
    fun `a play that leaves the player paused is reported`() {
        assertEquals(true, playIgnored(asked = 3, latest = 3, PlayerState.PAUSED, preloading = false))
        assertEquals(false, playIgnored(asked = 3, latest = 3, PlayerState.PLAYING, preloading = false))
        assertEquals(false, playIgnored(asked = 3, latest = 3, PlayerState.BUFFERING, preloading = false))
        assertEquals(false, playIgnored(asked = 3, latest = 4, PlayerState.PAUSED, preloading = false))
        assertEquals(false, playIgnored(asked = 3, latest = 3, PlayerState.PAUSED, preloading = true))
    }

    @Test
    fun `the trail keeps the latest notes, oldest first, timed from now`() {
        var now = 0L
        val trail = PlaybackTrail(capacity = 3) { now }
        listOf("play", "pause", "seek 10s", "play").forEach { now += 1_500; trail.note(it) }
        now += 500
        assertEquals(listOf("-3.5s pause", "-2.0s seek 10s", "-0.5s play"), trail.lines())
        assertNull(PlaybackTrail(capacity = 3) { 0 }.lines().firstOrNull())
    }
}
