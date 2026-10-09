package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryKeyValueStore : KeyValueStore {
    val backing = mutableMapOf<String, String>()
    override fun getString(key: String): String? = backing[key]
    override fun putString(key: String, value: String) {
        backing[key] = value
    }
    override fun remove(key: String) {
        backing.remove(key)
    }
}

class ProfileSettingsTest {

    @Test
    fun writesScopedToActiveProfile() {
        val store = InMemoryKeyValueStore()
        var active: String? = "alice"
        val ps = ProfileSettings(store) { active }

        ps.putString("pref_audio", "eng")

        assertEquals("eng", ps.getString("pref_audio"))
        assertTrue(store.backing.keys.any { it.contains("alice") && it.endsWith("pref_audio") })
        assertFalse(store.backing.containsKey("pref_audio"))
    }

    @Test
    fun readsAreIsolatedBetweenProfiles() {
        val store = InMemoryKeyValueStore()
        var active: String? = "alice"
        val ps = ProfileSettings(store) { active }

        ps.putString("pref_audio", "eng")

        active = "bob"
        assertNull(ps.getString("pref_audio"))

        ps.putString("pref_audio", "jpn")
        assertEquals("jpn", ps.getString("pref_audio"))

        active = "alice"
        assertEquals("eng", ps.getString("pref_audio"))
    }

    @Test
    fun removeOnlyAffectsActiveProfile() {
        val store = InMemoryKeyValueStore()
        var active: String? = "alice"
        val ps = ProfileSettings(store) { active }

        ps.putString("pref_audio", "eng")
        active = "bob"
        ps.putString("pref_audio", "jpn")

        active = "alice"
        ps.remove("pref_audio")
        assertNull(ps.getString("pref_audio"))

        active = "bob"
        assertEquals("jpn", ps.getString("pref_audio"))
    }

    @Test
    fun noActiveProfileUsesUnprefixedKeys() {
        val store = InMemoryKeyValueStore()
        val ps = ProfileSettings(store) { null }

        ps.putString("pref_audio", "eng")
        assertEquals("eng", store.backing["pref_audio"])
        assertEquals("eng", ps.getString("pref_audio"))
    }

    @Test
    fun migrateLegacyMovesUnprefixedIntoProfileNamespace() {
        val store = InMemoryKeyValueStore()
        store.backing["pref_audio"] = "eng"
        store.backing["pref_subs"] = "true"
        val ps = ProfileSettings(store) { "alice" }

        ps.migrateLegacyKeys(listOf("pref_audio", "pref_subs", "pref_missing"), "alice")

        assertEquals("eng", ps.getString("pref_audio"))
        assertEquals("true", ps.getString("pref_subs"))
        assertFalse(store.backing.containsKey("pref_audio"))
        assertFalse(store.backing.containsKey("pref_subs"))
    }
}
