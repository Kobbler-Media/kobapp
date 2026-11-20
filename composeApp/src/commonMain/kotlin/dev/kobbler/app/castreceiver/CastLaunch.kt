package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.KEY_SHOW_PICKER_ON_LAUNCH
import dev.kobbler.app.data.KeyValueStore
import dev.kobbler.app.data.PlatformKeyValueStore
import dev.kobbler.app.data.storedActiveProfile

/**
 * Whether a cast launched now would reach this app's player unattended: it already is,
 * or a cold start lands straight on a profile. A PIN, the profile picker or a login would
 * hold the cast until someone at the TV got past them.
 */
fun castConnectCanServe(store: KeyValueStore = PlatformKeyValueStore, serving: Boolean = CastReceiver.isServing): Boolean =
    serving || (store.getString(KEY_SHOW_PICKER_ON_LAUNCH) != "true" && storedActiveProfile(store)?.takeUnless { it.hasPin } != null)
