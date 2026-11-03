package dev.kobbler.app.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.kobbler.app.MainActivity
import dev.kobbler.app.watchnext.HomeWatchNext
import dev.kobbler.app.watchnext.parseWatchNextLaunch

/**
 * Opens a Watch Next program. A trampoline in a task of its own because a deep link that
 * starts MainActivity becomes its task's root intent, and Android answers the same link a
 * second time by surfacing that task without delivering anything.
 */
class WatchNextLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parseWatchNextLaunch(intent?.dataString)?.let { HomeWatchNext.launches.value = it }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
