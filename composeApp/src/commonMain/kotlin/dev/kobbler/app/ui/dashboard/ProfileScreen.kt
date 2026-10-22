package dev.kobbler.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.auth.MAX_PIN_LENGTH
import dev.kobbler.app.ui.auth.MIN_PIN_LENGTH
import dev.kobbler.app.ui.auth.isPinComplete
import dev.kobbler.app.ui.auth.isPinInputAllowed
import dev.kobbler.app.ui.auth.ProfileAvatar
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.kobFocusRing
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.imePaddingUnlessTv

@Composable
fun ProfileScreen(
    api: KobblerApi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
) {
    val auth = LocalAuthState.current
    val scope = rememberCoroutineScope()
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accSet
    val compact = formFactor == FormFactor.Phone
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }

    val profile = auth.currentProfile
    var pinDialog by remember { mutableStateOf<ProfilePinMode?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePaddingUnlessTv()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter),
    ) {
        Spacer(Modifier.height(dimens.s4))

        BrowseHeader(
            title = "Profile",
            accent = accent,
            countLine = profile?.email,
            compact = compact,
            trailing = {
                KobButton(
                    onClick = onBack,
                    style = KobButtonStyle.Ghost,
                    modifier = Modifier.focusKey("back"),
                    leading = {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textHi,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    text = "Back",
                )
            },
        )

        Spacer(Modifier.height(if (compact) dimens.s4 else dimens.s5))

        if (profile == null) {
            Text(
                "No active profile.",
                color = colors.textMd,
                style = KobappTheme.type.body,
            )
            Spacer(Modifier.height(dimens.s7))
            return@Column
        }

        val contentModifier = if (compact) Modifier.fillMaxWidth()
        else Modifier.fillMaxWidth().widthIn(max = 720.dp)

        Box(modifier = contentModifier) {
            Column(Modifier.fillMaxWidth()) {
                ProfileHeroCardLarge(
                    displayName = profile.displayName,
                    email = profile.email,
                    role = profile.role,
                    canWant = auth.canWant,
                    avatar = { ProfileAvatar(profile, size = 96.dp) },
                )

                Spacer(Modifier.height(dimens.s5))

                ProfileSectionGroup(label = "Editable") {
                    ProfileTextInputRow(
                        icon = Icons.Outlined.Person,
                        title = "Display name",
                        // The stored name, not `displayName` — showing the email-derived
                        // fallback in the editor would save that guess as a real name the
                        // first time anyone opened the field.
                        value = profile.name.orEmpty(),
                        placeholder = profile.displayName,
                        readOnly = false,
                        readOnlyHint = null,
                        onSave = { newName ->
                            scope.launch {
                                runCatching { api.setDisplayName(newName) }
                                    .onSuccess { auth.refreshCurrentProfile() }
                            }
                        },
                    )
                    ProfileDivider()
                    ProfileTextInputRow(
                        icon = Icons.Outlined.Image,
                        title = "Avatar URL",
                        value = profile.avatarUrl.orEmpty(),
                        placeholder = "https://...",
                        keyboardType = KeyboardType.Uri,
                        readOnly = true,
                        readOnlyHint = "Server-managed",
                        onSave = {},
                    )
                    ProfileDivider()
                    ProfileRow(
                        icon = Icons.Outlined.Mail,
                        title = "Email",
                        key = "email",
                        value = profile.email ?: "—",
                    )
                }

                Spacer(Modifier.height(dimens.s5))

                ProfileSectionGroup(label = "Security") {
                    ProfileRow(
                        icon = Icons.Outlined.Lock,
                        title = if (profile.hasPin) "Change profile PIN" else "Set profile PIN",
                        key = "set-pin",
                        value = if (profile.hasPin) "PIN required" else "Optional",
                        onClick = { pinDialog = ProfilePinMode.Set },
                    )
                    if (profile.hasPin) {
                        ProfileDivider()
                        ProfileRow(
                            icon = Icons.Outlined.LockOpen,
                            title = "Remove profile PIN",
                            key = "clear-pin",
                            onClick = { pinDialog = ProfilePinMode.Clear },
                        )
                    }
                }

                Spacer(Modifier.height(dimens.s5))

                ManagedByAdminNotice()

                Spacer(Modifier.height(dimens.s7))
            }
        }
    }

    pinDialog?.let { mode ->
        ProfilePinDialog(
            mode = mode,
            accent = accent,
            onDismiss = { pinDialog = null },
            onConfirm = { pin ->
                scope.launch {
                    val ok = when (mode) {
                        ProfilePinMode.Set -> auth.setPin(pin)
                        ProfilePinMode.Clear -> auth.clearPin()
                    }
                    if (ok) pinDialog = null
                }
            },
        )
    }
}

internal enum class ProfilePinMode { Set, Clear }

@Composable
private fun ProfileHeroCardLarge(
    displayName: String,
    email: String?,
    role: String,
    canWant: Boolean,
    avatar: @Composable () -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                color = colors.textHi,
                style = KobappTheme.type.h2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!email.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    email,
                    color = colors.textMd,
                    style = KobappTheme.type.bodySm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            RoleBadge(role = role, canWant = canWant)
        }
    }
}

@Composable
private fun RoleBadge(role: String, canWant: Boolean) {
    val colors = KobappTheme.colors
    val label = if (canWant) "admin" else "viewer"
    val tint = if (canWant) colors.accSet else colors.textMd
    val icon: ImageVector = if (canWant) Icons.Outlined.AdminPanelSettings else Icons.Outlined.Visibility
    Row(
        modifier = Modifier
            .clip(KobappTheme.shapes.pill)
            .background(colors.elev2)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            label,
            color = tint,
            style = KobappTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
        )
        if (role.isNotBlank() && role != label) {
            Text(
                "· $role",
                color = colors.textLo,
                style = KobappTheme.type.label,
            )
        }
    }
}

@Composable
private fun ProfileSectionGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    val colors = KobappTheme.colors
    Column(Modifier.fillMaxWidth().focusKeyGroup(label.lowercase())) {
        Text(
            label.uppercase(),
            color = colors.textLo,
            style = KobappTheme.type.label,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.elev1)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp)),
        ) {
            content()
        }
    }
}

@Composable
private fun ProfileDivider() {
    val colors = KobappTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 50.dp)
            .background(colors.lineSoft),
    )
}

@Composable
private fun ProfileRow(
    icon: ImageVector,
    title: String,
    key: String,
    value: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val accent = if (danger) colors.err else colors.accSet
    val titleColor = if (danger) colors.err else colors.textHi
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val focusProgress = animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(if (isFocused) 120 else 220),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.focusKey(key) else Modifier)
            .let {
                if (onClick != null) {
                    it.pointerHoverIcon(PointerIcon.Hand)
                        .kobFocusRing(focusProgress, cornerRadiusDp = 0f, accent = accent)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else it
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) colors.err else colors.textMd,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            color = titleColor,
            style = KobappTheme.type.body,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                color = colors.textLo,
                style = KobappTheme.type.bodySm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProfileTextInputRow(
    icon: ImageVector,
    title: String,
    value: String,
    placeholder: String,
    readOnly: Boolean,
    readOnlyHint: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSave: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    val fieldFocus = remember { FocusRequester() }

    // Avatar has no client-side upload yet, so it stays read-only; the display name is
    // editable and saves through /api/v1/admin/me/display-name.
    if (readOnly || !editing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .let {
                    if (!readOnly) {
                        it.pointerHoverIcon(PointerIcon.Hand).clickable {
                            text = value
                            editing = true
                        }
                    } else it
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = colors.textMd, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = colors.textHi,
                    style = KobappTheme.type.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (value.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        value,
                        color = colors.textLo,
                        style = KobappTheme.type.bodySm,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (readOnly && readOnlyHint != null) {
                Text(
                    readOnlyHint,
                    color = colors.textLo,
                    style = KobappTheme.type.label,
                )
            } else {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = colors.textLo,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = colors.textMd, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            val commit = {
                onSave(text.trim())
                editing = false
            }
            LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder, color = colors.textLo) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onAny = { commit() }),
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accSet,
                    unfocusedBorderColor = colors.lineSoft,
                    focusedTextColor = colors.textHi,
                    unfocusedTextColor = colors.textHi,
                ),
            )
            Spacer(Modifier.width(8.dp))
            KobButton(
                onClick = commit,
                style = KobButtonStyle.Accent,
                accent = colors.accSet,
            ) { Text("Save", color = colors.bg, style = KobappTheme.type.body) }
        }
    }
}

@Composable
private fun ManagedByAdminNotice() {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.textMd,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Managed by admin",
                color = colors.textHi,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Identity, email, and role are server-managed. Ask your Kobbler admin to change display name, avatar, or permissions.",
                color = colors.textMd,
                style = KobappTheme.type.bodySm,
            )
        }
        Icon(
            Icons.Outlined.Badge,
            contentDescription = null,
            tint = colors.textLo,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun ProfilePinDialog(
    mode: ProfilePinMode,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    var pin by remember { mutableStateOf("") }
    val title = if (mode == ProfilePinMode.Set) "Set profile PIN" else "Remove profile PIN"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = colors.textHi, style = KobappTheme.type.h3) },
        text = {
            Column {
                Text(
                    if (mode == ProfilePinMode.Set) "Anyone opening this profile on this or any device will be asked for this PIN."
                    else "Confirm you want to remove the PIN from this profile.",
                    style = KobappTheme.type.body,
                    color = colors.textMd,
                )
                if (mode == ProfilePinMode.Set) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (isPinInputAllowed(it)) pin = it },
                        singleLine = true,
                        placeholder = { Text("PIN ($MIN_PIN_LENGTH-$MAX_PIN_LENGTH chars, no spaces)", color = colors.textLo) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onAny = { if (isPinComplete(pin)) onConfirm(pin) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = colors.lineSoft,
                            focusedTextColor = colors.textHi,
                            unfocusedTextColor = colors.textHi,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            val enabled = mode == ProfilePinMode.Clear || isPinComplete(pin)
            KobButton(
                onClick = { onConfirm(pin) },
                enabled = enabled,
                style = KobButtonStyle.Accent,
                accent = if (mode == ProfilePinMode.Clear) colors.err else accent,
            ) {
                Text(
                    if (mode == ProfilePinMode.Set) "Save" else "Remove",
                    color = colors.bg,
                    style = KobappTheme.type.body,
                )
            }
        },
        dismissButton = {
            KobButton(onClick = onDismiss, style = KobButtonStyle.Ghost) {
                Text("Cancel", color = colors.textHi, style = KobappTheme.type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}
