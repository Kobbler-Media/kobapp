package dev.kobbler.app.platform

import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * The completions go to the IME through [InputMethodManager.displayCompletions], which only
 * reaches the view it is serving; and a pick comes back as [InputConnection.commitCompletion],
 * which Compose's own connection ignores, so the connection is wrapped to catch it.
 * Phone keyboards drop app completions outside fullscreen mode; the TV ones show them.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun KeyboardCompletions(completions: List<String>, onPick: (String) -> Unit, content: @Composable () -> Unit) {
    val view = LocalView.current
    val imm = remember(view) { view.context.getSystemService(InputMethodManager::class.java) }
    val pick by rememberUpdatedState(onPick)
    val offered = remember(completions) {
        completions.mapIndexed { i, title -> CompletionInfo(i.toLong(), i, title) }.toTypedArray()
    }
    val latest by rememberUpdatedState(offered)
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(offered, typing) {
        if (typing) imm.displayCompletions(view, offered)
    }
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            typing = true
            try {
                next.startInputMethod(
                    object : PlatformTextInputMethodRequest {
                        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                            val inner = request.createInputConnection(outAttributes)
                            view.post { imm.displayCompletions(view, latest) }
                            return object : InputConnectionWrapper(inner, false) {
                                override fun commitCompletion(text: CompletionInfo): Boolean {
                                    view.post { pick(text.text.toString()) }
                                    return true
                                }
                            }
                        }
                    },
                )
            } finally {
                typing = false
            }
        },
        content = content,
    )
}
