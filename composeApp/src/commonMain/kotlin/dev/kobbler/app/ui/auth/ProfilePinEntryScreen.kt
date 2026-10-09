package dev.kobbler.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.Profile
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.safeContentPadding
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val MIN_PIN_LENGTH = 3
internal const val MAX_PIN_LENGTH = 32

private const val MIN_PIN_CELLS = 4
private const val MAX_PIN_CELLS = 8
private const val PinPeekMs = 900L

// The server's validate_pin and the web admin both accept any 3-32 character
// non-whitespace PIN. Entry has to accept exactly that set or a PIN set from the
// web locks the profile out of this app, with no in-app way to clear it.
internal fun isPinInputAllowed(value: String): Boolean =
    value.length <= MAX_PIN_LENGTH && value.none { it.isWhitespace() }

internal fun isPinComplete(value: String): Boolean =
    value.length >= MIN_PIN_LENGTH && isPinInputAllowed(value)

@Composable
fun ProfilePinEntryScreen(
    auth: AuthState,
    profile: Profile,
    onCancel: () -> Unit,
    onSuccess: () -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accMovies
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    // A masked field with no feedback at all leaves a soft-keyboard typo undetectable.
    // Reveal only the character just added, and only on a keystroke that grew the pin —
    // peeking on backspace would show a character the viewer just deleted.
    var typedLength by remember { mutableIntStateOf(0) }
    var peekIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pin) {
        val grew = pin.length > typedLength
        typedLength = pin.length
        peekIndex = if (grew) pin.length - 1 else -1
        if (!grew) return@LaunchedEffect
        delay(PinPeekMs)
        peekIndex = -1
    }

    val summonKeyboard: () -> Unit = {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    LaunchedEffect(Unit) { summonKeyboard() }

    val onSubmit: () -> Unit = {
        if (!submitting && isPinComplete(pin)) {
            submitting = true
            scope.launch {
                val ok = auth.verifyPin(profile.id, pin)
                submitting = false
                if (ok) onSuccess()
                else {
                    pin = ""
                    error = "Incorrect PIN"
                }
            }
        }
    }

    AuthCenteredColumn(accent = accent, maxWidth = 560.dp) {
        ProfileAvatar(profile = profile, size = 140.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            profile.displayName,
            color = colors.textHi,
            style = type.display.copy(fontSize = type.h1.fontSize),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your PIN",
            color = colors.textLo,
            style = type.caption,
        )

        Spacer(Modifier.height(28.dp))

        PinCells(
            pin = pin,
            peekIndex = peekIndex,
            accent = accent,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { summonKeyboard() } },
        )

        if (pin.length > MAX_PIN_CELLS) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${pin.length} characters",
                color = colors.textLo,
                style = type.caption,
            )
        }

        BasicTextField(
            value = pin,
            onValueChange = { v ->
                if (isPinInputAllowed(v)) {
                    pin = v
                    error = null
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onAny = { onSubmit() }),
            enabled = !submitting,
            singleLine = true,
            modifier = Modifier
                .focusKey("pin")
                .focusRequester(focusRequester)
                .alpha(0f)
                .size(1.dp),
        )

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error!!, color = colors.err, style = type.caption)
        }

        Spacer(Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KobButton(
                text = "Unlock",
                style = KobButtonStyle.Accent,
                accent = accent,
                enabled = !submitting && isPinComplete(pin),
                modifier = Modifier.focusKey("action.unlock"),
                onClick = onSubmit,
            )
            KobButton(
                text = "Cancel",
                style = KobButtonStyle.Ghost,
                enabled = !submitting,
                modifier = Modifier.focusKey("action.cancel"),
                onClick = onCancel,
            )
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.textLo,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        KobButton(
            text = "Forgot PIN · reset from web admin",
            style = KobButtonStyle.Ghost,
            modifier = Modifier.focusKey("action.forgotPin"),
            onClick = { },
        )

        Spacer(Modifier.height(14.dp))
        Text(
            "↹ change profile · ESC cancel",
            color = colors.textLo,
            style = type.caption,
        )
    }
}

// The PIN length is server-side state the client never sees, so the row grows with
// what has been typed instead of pretending to know how many digits to expect. It only
// grows once the typed pin outruns the four cells, so the common four-digit case never
// sprouts a fifth box under the reader.
@Composable
private fun PinCells(pin: String, peekIndex: Int, accent: Color, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cells = pin.length.coerceIn(MIN_PIN_CELLS, MAX_PIN_CELLS)
        val gap = 10.dp
        val cellWidth: Dp = ((maxWidth - gap * (cells - 1)) / cells).coerceIn(30.dp, 60.dp)
        val cellHeight = cellWidth * 1.25f
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until cells) {
                val filled = i < pin.length
                val isActive = i == pin.length.coerceAtMost(cells - 1)
                Box(
                    modifier = Modifier
                        .size(width = cellWidth, height = cellHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (filled) colors.elev3 else colors.elev1)
                        .border(
                            if (isActive) 2.dp else 1.dp,
                            if (isActive) accent else colors.line,
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) {
                        Text(
                            if (i == peekIndex) pin[i].toString() else "●",
                            color = colors.textHi,
                            style = type.h2,
                        )
                    }
                }
            }
        }
    }
}

// Centres when the content fits and scrolls when it does not: heightIn(min = maxHeight)
// sits inside verticalScroll, so the column is at least a viewport tall (making
// Arrangement.Center meaningful) and grows past it in landscape or with the IME up.
@Composable
internal fun AuthCenteredColumn(
    accent: Color,
    maxWidth: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    StripedBackdrop(accent = accent, cached = true) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().safeContentPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .widthIn(max = maxWidth)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                content = content,
            )
        }
    }
}
