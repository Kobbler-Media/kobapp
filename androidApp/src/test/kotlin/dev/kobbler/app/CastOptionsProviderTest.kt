package dev.kobbler.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastOptionsProviderTest {

    // The system cast dialog's "Stop casting" ends the session through the framework, not
    // through our own stop(), so only this option keeps the TV from playing on alone.
    @Test
    fun `ending a cast session from anywhere stops the receiver`() {
        val options = CastOptionsProvider().getCastOptions(RuntimeEnvironment.getApplication())
        assertTrue(options.stopReceiverApplicationWhenEndingSession)
    }
}
