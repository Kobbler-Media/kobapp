package dev.kobbler.app.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AspectBucketTest {

    @Test
    fun classicTvFraming() {
        assertEquals("4:3", aspectBucket(640, 480))
        assertEquals("4:3", aspectBucket(1024, 768))
        assertEquals("4:3", aspectBucket(720, 540))
    }

    @Test
    fun standardWidescreen() {
        assertEquals("16:9", aspectBucket(1920, 1080))
        assertEquals("16:9", aspectBucket(1280, 720))
        assertEquals("16:9", aspectBucket(3840, 2160))
        assertEquals("16:9", aspectBucket(1920, 1036))
    }

    @Test
    fun cinematicWide() {
        assertEquals("wide", aspectBucket(1920, 800))
        assertEquals("wide", aspectBucket(4096, 1716))
        assertEquals("wide", aspectBucket(3840, 1600))
        assertEquals("wide", aspectBucket(2560, 1080))
    }

    @Test
    fun boundariesAtFifteenAndOneNineFive() {
        assertEquals("16:9", aspectBucket(1500, 1000))
        assertEquals("wide", aspectBucket(1950, 1000))
    }

    @Test
    fun zeroOrNegativeReturnsNull() {
        assertNull(aspectBucket(0, 1080))
        assertNull(aspectBucket(1920, 0))
        assertNull(aspectBucket(0, 0))
        assertNull(aspectBucket(-1, 480))
    }
}
