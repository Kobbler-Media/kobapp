package androidx.media3.session

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent

// Robolectric's platform MediaSession drops what a MediaController sends it, so a key goes in
// where the platform session hands one to Media3: a legacy (Bluetooth, headset) controller.
// A false answer is the platform resolving the key itself, as onPlay/onPause.
fun MediaSession.dispatchLegacyMediaButton(event: KeyEvent) {
    val bluetooth = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.android.bluetooth", 0, 0,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        false, Bundle.EMPTY, false,
    )
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)
    if (impl.onMediaButtonEvent(bluetooth, intent) || event.action != KeyEvent.ACTION_DOWN) return
    when (event.keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
        KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
    }
}
