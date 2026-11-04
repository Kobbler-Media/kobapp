package dev.kobbler.app.platform

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class BrowserFullscreen {
    var active by mutableStateOf(false)
        private set

    init {
        onFullscreenExit { active = false }
    }

    fun toggle() {
        active = !active
        if (active) enterLandscapeFullscreen() else exitLandscapeFullscreen()
    }
}

// Both calls need the tap that raised the player to still count as a user gesture, and
// either may be refused (iPhone Safari has no element fullscreen, desktops cannot lock
// orientation): a refusal leaves the in-app fullscreen player as it is.
private fun enterLandscapeFullscreen(): Unit = js(
    """{
    const el = document.documentElement;
    const lock = () => {
        const o = screen.orientation;
        if (o && o.lock) o.lock('landscape').catch(() => {});
    };
    const request = el.requestFullscreen || el.webkitRequestFullscreen;
    if (!request || document.fullscreenElement || document.webkitFullscreenElement) { lock(); return; }
    try { Promise.resolve(request.call(el, { navigationUI: 'hide' })).then(lock, () => {}); } catch (e) {}
}""",
)

private fun exitLandscapeFullscreen(): Unit = js(
    """{
    const o = screen.orientation;
    if (o && o.unlock) { try { o.unlock(); } catch (e) {} }
    const exit = document.exitFullscreen || document.webkitExitFullscreen;
    if (exit && (document.fullscreenElement || document.webkitFullscreenElement)) {
        try { Promise.resolve(exit.call(document)).catch(() => {}); } catch (e) {}
    }
}""",
)

private fun onFullscreenExit(callback: () -> Unit): Unit = js(
    """{
    const changed = () => { if (!document.fullscreenElement && !document.webkitFullscreenElement) callback(); };
    document.addEventListener('fullscreenchange', changed);
    document.addEventListener('webkitfullscreenchange', changed);
}""",
)
