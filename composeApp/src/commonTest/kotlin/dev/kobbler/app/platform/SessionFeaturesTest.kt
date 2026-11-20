package dev.kobbler.app.platform

import dev.kobbler.app.data.ClientCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionFeaturesTest {
    private val caps = ClientCapabilities(listOf("h264"), listOf("aac"), listOf("mp4"))
    private val all = CastFeature.entries.toSet()

    @Test
    fun `a receiver that lists no features keeps everything the protocol promises`() {
        val session = SessionFeatures(all)
        session.heard(CastEvent.Capabilities(caps))
        assertEquals(all, session.current)
    }

    @Test
    fun `a receiver's list narrows the session, never widens it`() {
        val session = SessionFeatures(all - CastFeature.Tts)
        session.heard(CastEvent.Capabilities(caps, setOf(CastFeature.Hls, CastFeature.Tts)))
        assertEquals(setOf(CastFeature.Hls), session.current)
    }

    @Test
    fun `a refusal drops that feature until the next session`() {
        val session = SessionFeatures(all)
        session.heard(CastEvent.Refused(CastFeature.Audiobook))
        assertEquals(all - CastFeature.Audiobook, session.current)
        session.reset()
        assertEquals(all, session.current)
    }
}
