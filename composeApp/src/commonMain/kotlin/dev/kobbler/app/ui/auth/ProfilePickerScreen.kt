package dev.kobbler.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.PinLoginMode
import dev.kobbler.app.data.Profile
import dev.kobbler.app.platform.BackHandler
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.FocusEdgeSlack
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

private val TilePadding = 22.dp

@Composable
fun ProfilePickerScreen(
    auth: AuthState,
    formFactor: FormFactor,
) {
    var pinTarget by remember { mutableStateOf<Profile?>(null) }
    // Driven by the link flow's own state, so cancelling, failing out of or finishing a
    // link lands back on the profiles without a second copy of that state here.
    val linking = auth.pinPolling || auth.pin != null || auth.pinError != null

    val dismiss: () -> Unit = when {
        linking -> auth::cancelPinLogin
        pinTarget != null -> { { pinTarget = null } }
        else -> auth::dismissPicker
    }
    BackHandler(enabled = linking || pinTarget != null || auth.pickerDismissible, onBack = dismiss)

    val target = pinTarget
    when {
        linking -> PinLoginScreen(auth = auth, mode = auth.pinLoginMode)
        target != null -> ProfilePinEntryScreen(
            auth = auth,
            profile = target,
            onCancel = { pinTarget = null },
            onSuccess = {
                auth.confirmProfile(target.id)
                pinTarget = null
            },
        )
        else -> ProfileRow(
            auth = auth,
            formFactor = formFactor,
            onDismiss = dismiss,
            onProfileSelected = { profile ->
                when {
                    profile.needsRelink -> auth.startPinLogin(PinLoginMode.Relink, relinkProfileId = profile.id)
                    profile.hasPin -> pinTarget = profile
                    else -> auth.confirmProfile(profile.id)
                }
            },
            onAddProfile = { auth.startPinLogin(PinLoginMode.Add) },
        )
    }
}

@Composable
private fun ProfileRow(
    auth: AuthState,
    formFactor: FormFactor,
    onDismiss: () -> Unit,
    onProfileSelected: (Profile) -> Unit,
    onAddProfile: () -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accHome
    val avatarSize: Dp = if (formFactor == FormFactor.Phone) 96.dp else 124.dp
    val firstTileFocus = remember { FocusRequester() }

    LaunchedEffect(auth.profiles.size) {
        kotlinx.coroutines.delay(100)
        runCatching { firstTileFocus.requestFocus() }
    }

    PickerBackdrop(auth = auth, accent = accent) {
        Box(Modifier.fillMaxSize()) {
            if (auth.pickerDismissible) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusKey("back").align(Alignment.TopStart).padding(16.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = colors.textMd)
                }
            }
            // A band rather than a card: the picture keeps the whole width around it, and
            // the title and tiles stay legible over whatever arrival is showing.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .background(colors.bg.copy(alpha = 0.6f))
                    .padding(vertical = 40.dp),
            ) {
                Text(
                    "Choose your profile",
                    color = colors.textLo,
                    style = type.bodySm,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Who’s watching tonight?",
                    color = colors.textHi,
                    style = type.display.copy(fontSize = type.h1.fontSize),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
                Spacer(Modifier.height(48.dp))

                FocusEdgeSlack {
                    LazyRow(
                        modifier = Modifier.focusKeyGroup("profiles").fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                        // The focus ring draws outside a tile's bounds and the focus scale
                        // grows it further; a lazy layout clips to its own.
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                    ) {
                        itemsIndexed(auth.profilesByRecentUse, key = { _, profile -> profile.id }) { index, profile ->
                            ProfileTile(
                                profile = profile,
                                size = avatarSize,
                                accent = accent,
                                modifier = Modifier
                                    .focusKey(profile.id)
                                    .then(if (index == 0) Modifier.focusRequester(firstTileFocus) else Modifier),
                                onClick = { onProfileSelected(profile) },
                            )
                        }
                        item(key = "__add__") {
                            AddProfileTile(
                                size = avatarSize,
                                modifier = Modifier
                                    .focusKey("add")
                                    .then(if (auth.profiles.isEmpty()) Modifier.focusRequester(firstTileFocus) else Modifier),
                                onClick = onAddProfile,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile, size: Dp, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .tvAutoGlow(cornerRadiusDp = 18f, scale = 1.04f)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(TilePadding),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            ProfileAvatar(profile, size = size)
            if (profile.needsRelink) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.err),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = colors.bg, modifier = Modifier.size(15.dp))
                }
            } else if (profile.hasPin) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(lerp(colors.elev1, accent, 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.bg, modifier = Modifier.size(15.dp))
                }
            }
        }
        TileLabels(
            title = profile.displayName,
            caption = when {
                profile.needsRelink -> "Needs relink"
                profile.hasPin -> "PIN locked"
                else -> null
            },
            captionColor = if (profile.needsRelink) colors.err else colors.textLo,
            width = size,
        )
    }
}

@Composable
private fun AddProfileTile(size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .tvAutoGlow(cornerRadiusDp = 18f, scale = 1.04f)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(TilePadding),
    ) {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).border(2.dp, colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add profile", tint = colors.textLo, modifier = Modifier.size(size / 2))
        }
        TileLabels(title = "Add profile", caption = null, captionColor = colors.textLo, width = size)
    }
}

@Composable
private fun TileLabels(title: String, caption: String?, captionColor: Color, width: Dp) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    Spacer(Modifier.height(16.dp))
    Text(
        title,
        color = colors.textHi,
        style = type.h3,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
    )
    Spacer(Modifier.height(4.dp))
    // An empty caption still takes its line, so tiles with and without one stay the same height.
    Text(caption.orEmpty(), color = captionColor, style = type.caption)
}
