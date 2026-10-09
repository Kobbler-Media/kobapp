package dev.kobbler.app.platform

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberVoiceInputLauncher(onResult: (String) -> Unit): VoiceInputLauncher? {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
        if (text != null) callback(text)
    }

    val available = remember(context) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.resolveActivity(context.packageManager) != null
    }

    return remember(launcher, available) {
        object : VoiceInputLauncher {
            override val isAvailable: Boolean = available
            override fun start() {
                if (!available) return
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Kobbler")
                }
                launcher.launch(intent)
            }
        }
    }
}
