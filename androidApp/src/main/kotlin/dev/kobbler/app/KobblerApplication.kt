package dev.kobbler.app

import android.app.Application
import dev.kobbler.app.castconnect.CastConnect

class KobblerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CastConnect.init(this)
    }
}
