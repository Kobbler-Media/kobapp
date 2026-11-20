package dev.kobbler.app.platform

import kotlin.concurrent.Volatile

/**
 * What the receiver of one session does, starting from what its protocol promises.
 *
 * A Cast session can land on the web receiver or on kobapp on an Android TV; only the
 * receiver knows which, and it says so in its CAPABILITIES (or by refusing a hand-over).
 */
class SessionFeatures(private val declared: Set<CastFeature>) {
    @Volatile
    var current: Set<CastFeature> = declared
        private set

    fun reset() {
        current = declared
    }

    fun heard(event: CastEvent) {
        when (event) {
            is CastEvent.Capabilities -> event.features?.let { listed -> current = declared intersect listed }
            is CastEvent.Refused -> current = current - event.feature
            else -> Unit
        }
    }
}
