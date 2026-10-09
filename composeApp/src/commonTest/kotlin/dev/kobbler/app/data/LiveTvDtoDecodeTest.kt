package dev.kobbler.app.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Payloads are the server's own serialisation, numerics and explicit nulls
 * included: every field the tuner API types as a number or an `Option` has been
 * a decode crash at least once.
 */
class LiveTvDtoDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun channelListDecodesNumericLcn() {
        val body = """
            {"items":[{"id":"c1","tuner_id":"t1","last_seen_at":"2026-08-29T22:56:01.953591Z",
            "lcn":1,"logo_url":null,"modulation":"QAM64","name":"ABC","kind":"tv","hd":true,
            "signal_strength":90,"signal_quality":100,"frequency":226500000,"program_number":1,
            "mux_label":"ABC","type_label":"TV","has_guide":true,"guide_entry_count":42}],
            "total":1}
        """.trimIndent()
        val resp = json.decodeFromString<LiveChannelListResponse>(body)
        assertEquals(1, resp.items[0].lcn)
    }

    @Test
    fun guideChannelDecodesNumericLcn() {
        val body = """
            {"channels":[{"channel":{"id":"c1","lcn":24,"name":"ABC ME","kind":"tv"},
            "entries":[{"id":"g1","channel_id":"c1","start_time":"2026-08-30T09:00:00Z",
            "end_time":"2026-08-30T09:30:00Z","title":"Bluey","duration_seconds":1800,
            "progress_pct":42.5,"repeat_count":3}]}]}
        """.trimIndent()
        val resp = json.decodeFromString<LiveGuideResponse>(body)
        assertEquals(24, resp.channels[0].channel.lcn)
        assertEquals(42.5f, resp.channels[0].entries[0].progressPct)
    }

    @Test
    fun recordingDecodesFractionalSecondsAndNullSize() {
        val body = """
            {"items":[{"id":"r1","channel_id":"c1","channel_name":"ABC","title":"Bluey",
            "scheduled_start":"2026-08-30T09:00:00Z","scheduled_end":"2026-08-30T09:30:00Z",
            "status":"recording","tuner_index":null,"duration_seconds":null,"size_bytes":null,
            "is_in_progress":true,"seconds_on_disk":812.532,"playable_from_start":true,
            "ad_breaks":[{"id":"a1","recording_id":"r1","start_seconds":61.5,"end_seconds":181.25,
            "method":"black","confidence":0.87,"ads_known":2,"ads_total":3,"corrected_at":null}],
            "provenance":{"channel_name":"ABC","lcn":2,"aired":"2026-08-30T09:00:00Z"}}],
            "total":1}
        """.trimIndent()
        val resp = json.decodeFromString<LiveRecordingListResponse>(body)
        val rec = resp.items[0]
        assertNull(rec.durationSeconds)
        assertNull(rec.sizeBytes)
        assertEquals(812.532, rec.secondsOnDisk)
        assertEquals(2, rec.provenance?.lcn)
    }

    @Test
    fun finishedRecordingDecodesFractionalDuration() {
        val body = """
            {"items":[{"id":"r1","channel_id":"c1","channel_name":"ABC","title":"Bluey",
            "scheduled_start":"2026-08-30T09:00:00Z","scheduled_end":"2026-08-30T09:30:00Z",
            "status":"completed","duration_seconds":1793.44,"size_bytes":1073741824,
            "seconds_on_disk":1793.44,"is_in_progress":false,"playable_from_start":true}],
            "total":1}
        """.trimIndent()
        val rec = json.decodeFromString<LiveRecordingListResponse>(body).items[0]
        assertEquals(1793.44, rec.durationSeconds)
        assertEquals(1073741824L, rec.sizeBytes)
    }

    @Test
    fun leaseDecodesNullTunerIndex() {
        val body = """
            {"total":4,"in_use":1,"items":[{"id":"l1","tuner_id":"t1","channel_id":"c1",
            "channel_name":"ABC","tuner_index":null,"holder_kind":"viewer","session_id":"s1",
            "acquired_at":"2026-08-30T09:00:00Z","expires_at":null}]}
        """.trimIndent()
        val resp = json.decodeFromString<LiveLeaseListResponse>(body)
        assertNull(resp.items[0].tunerIndex)
    }

    @Test
    fun sessionDecodesFractionalDuration() {
        val body = """
            {"session_id":"s1","channel_id":"c1","method":"direct","playlist_url":null,
            "direct_url":"http://tuner/auto/v1","video_codec":"h264","audio_codec":"ac3",
            "hd":true,"width":1920,"height":1080,"buffer_start_time":"2026-08-30T09:00:00Z",
            "is_live":true,"duration_seconds":18.24,"tuners_in_use":1,"tuner_count":4,
            "lease":{"id":"l1","tuner_id":"t1","channel_id":"c1","channel_name":"ABC",
            "tuner_index":0,"holder_kind":"viewer","acquired_at":"2026-08-30T09:00:00Z"}}
        """.trimIndent()
        val session = json.decodeFromString<LiveSessionDto>(body)
        assertEquals(18.24, session.durationSeconds)
    }

    @Test
    fun rulePreviewDecodesNumericLcn() {
        val body = """
            {"clusters":[{"label":"Thu 7:30pm","start_time":"2026-08-30T09:30:00Z",
            "members":[{"guide_id":"g1","channel_id":"c1","channel_name":"ABC HD","lcn":21,
            "hd":true,"start_time":"2026-08-30T09:30:00Z","action":"record","reason":"hd"}]}],
            "would_record":1,"suppressed":0}
        """.trimIndent()
        val resp = json.decodeFromString<LiveRulePreviewResponse>(body)
        assertEquals(21, resp.clusters[0].members[0].lcn)
    }

    @Test
    fun onNowDecodesNumericLcn() {
        val body = """
            {"generated_at":"2026-08-30T09:00:00Z","groups":[{"key":"habit","title":"Because you watch",
            "subtitle":"","items":[{"channel":{"id":"c1","lcn":7,"name":"7","kind":"tv"},
            "guide":{"id":"g1","channel_id":"c1","start_time":"2026-08-30T09:00:00Z",
            "end_time":"2026-08-30T09:30:00Z","title":"News"},"minutes_left":12,
            "reason":"habit","confidence":0.62}]}]}
        """.trimIndent()
        val resp = json.decodeFromString<LiveOnNowResponse>(body)
        assertEquals(7, resp.groups[0].items[0].channel.lcn)
    }
}
