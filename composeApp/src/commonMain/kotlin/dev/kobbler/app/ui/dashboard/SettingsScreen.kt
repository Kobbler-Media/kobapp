package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.data.PinLoginMode
import dev.kobbler.app.data.Profile
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.AppVersion
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.platform.clientKind
import dev.kobbler.app.ui.player.KEY_AUDIO_OUTPUT
import dev.kobbler.app.ui.player.KEY_VIDEO_DECODING
import dev.kobbler.app.ui.player.videoDecodingSetting
import dev.kobbler.app.ui.player.KEY_MATCH_DISPLAY_REFRESH
import dev.kobbler.app.ui.player.matchDisplayRefreshSetting
import dev.kobbler.kobplayer.videoDecoderChoices
import dev.kobbler.app.ui.player.audioOutputModeSetting
import dev.kobbler.kobplayer.AudioOutputMode
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.auth.HouseholdProfilesDialog
import dev.kobbler.app.ui.auth.ProfileAvatar
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.TvFocusScale
import dev.kobbler.app.ui.components.tvAutoGlow
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.imePaddingUnlessTv

const val KEY_DEFAULT_AUDIO_LANG = "pref_default_audio_lang"
const val KEY_DEFAULT_SUBTITLE_LANG = "pref_default_subtitle_lang"
const val KEY_SUBTITLES_ENABLED = "pref_subtitles_enabled"
private const val KEY_DEFAULT_PLAYBACK_SPEED = "pref_default_playback_speed"
private const val KEY_TTS_VOICE = "pref_tts_voice"
private const val KEY_TTS_SPEED = "pref_tts_speed"
const val KEY_STREAMING_QUALITY = "pref_streaming_quality"

private val AUDIO_LANG_OPTIONS = listOf(
    "" to "Auto",
    "eng" to "English",
    "jpn" to "Japanese",
    "spa" to "Spanish",
    "fre" to "French",
    "ger" to "German",
    "ita" to "Italian",
    "por" to "Portuguese",
    "kor" to "Korean",
    "chi" to "Chinese",
)
private val SUBTITLE_LANG_OPTIONS = listOf(
    "" to "None",
    "eng" to "English",
    "jpn" to "Japanese",
    "spa" to "Spanish",
    "fre" to "French",
    "ger" to "German",
)
private val SPEED_OPTIONS = listOf(
    "0.5" to "0.5x",
    "0.75" to "0.75x",
    "1.0" to "1.0x",
    "1.25" to "1.25x",
    "1.5" to "1.5x",
    "1.75" to "1.75x",
    "2.0" to "2.0x",
)
private val TTS_SPEED_OPTIONS = listOf(
    "0.5" to "0.5x",
    "0.75" to "0.75x",
    "1.0" to "1.0x",
    "1.25" to "1.25x",
    "1.5" to "1.5x",
    "2.0" to "2.0x",
)
private val AUDIO_OUTPUT_OPTIONS = listOf(
    AudioOutputMode.AUTO.name to "Auto (Dolby passthrough, else stereo)",
    AudioOutputMode.STEREO.name to "Stereo",
    AudioOutputMode.SURROUND.name to "Surround PCM",
    AudioOutputMode.PASSTHROUGH.name to "Passthrough (surround PCM otherwise)",
)
private val QUALITY_OPTIONS = listOf(
    "auto" to "Auto (direct play when possible)",
    "adaptive" to "Adaptive (force transcode for ABR)",
    "1080" to "Max 1080p (8 Mbps)",
    "720" to "Max 720p (4 Mbps)",
    "480" to "Max 480p (2 Mbps)",
)

@Composable
fun SettingsScreen(
    serverUrl: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onOpenProfile: (() -> Unit)? = null,
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

    var showLogoutConfirm by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf<ProfilePinMode?>(null) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var showHousehold by remember { mutableStateOf(false) }
    var householdMsg by remember { mutableStateOf<String?>(null) }

    var audioLang by remember { mutableStateOf(UserSettings.getString(KEY_DEFAULT_AUDIO_LANG) ?: "") }
    var subtitleLang by remember { mutableStateOf(UserSettings.getString(KEY_DEFAULT_SUBTITLE_LANG) ?: "") }
    var subtitlesEnabled by remember { mutableStateOf(UserSettings.getString(KEY_SUBTITLES_ENABLED) != "false") }
    var audioOutput by remember { mutableStateOf(audioOutputModeSetting().name) }
    val decoderChoices = remember { videoDecoderChoices() }
    var videoDecoding by remember { mutableStateOf(videoDecodingSetting().key) }
    var matchDisplayRefresh by remember { mutableStateOf(matchDisplayRefreshSetting()) }
    var playbackSpeed by remember { mutableStateOf(UserSettings.getString(KEY_DEFAULT_PLAYBACK_SPEED) ?: "1.0") }
    var streamingQuality by remember { mutableStateOf(UserSettings.getString(KEY_STREAMING_QUALITY) ?: "auto") }
    var ttsVoice by remember { mutableStateOf(UserSettings.getString(KEY_TTS_VOICE) ?: "") }
    var ttsSpeed by remember { mutableStateOf(UserSettings.getString(KEY_TTS_SPEED) ?: "1.0") }

    val active = auth.currentProfile
    val displayServer = serverUrl.removePrefix("https://").removePrefix("http://")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePaddingUnlessTv()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter),
    ) {
        Spacer(Modifier.height(dimens.s4))

        BrowseHeader(
            title = "Settings",
            accent = accent,
            countLine = displayServer.takeIf { it.isNotBlank() },
            compact = compact,
        )

        Spacer(Modifier.height(if (compact) dimens.s4 else dimens.s5))

        if (active != null) {
            ProfileHeroCard(
                profile = active,
                meta = active.email ?: displayServer,
                onClick = { onOpenProfile?.invoke() ?: auth.showPicker() },
            )
            Spacer(Modifier.height(dimens.s5))
        }

        SettingsGroup(label = "Profiles") {
            SettingsRow(
                icon = Icons.Outlined.SwitchAccount,
                title = if (auth.profiles.size > 1) "Switch profile" else "Pick profile",
                key = "switch",
                value = if (auth.profiles.size > 1) "${auth.profiles.size} on device" else null,
                onClick = { auth.showPicker() },
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.PersonAdd,
                title = "Add profile",
                key = "add",
                value = "Link Kobbler user",
                onClick = {
                    auth.startPinLogin(PinLoginMode.Add)
                    auth.showPicker()
                },
            )
            if (auth.role == "admin") {
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.Groups,
                    title = "Add household profiles",
                    key = "household",
                    value = householdMsg ?: "Link every Kobbler user at once",
                    onClick = { showHousehold = true },
                )
            }
            SettingsDivider()
            SettingsToggleRow(
                icon = Icons.Outlined.GroupWork,
                title = "Show picker on launch",
                key = "show-picker",
                checked = auth.showPickerOnLaunch,
                onToggle = { auth.updateShowPickerOnLaunch(it) },
            )
            if (active != null) {
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    title = if (active.hasPin) "Change profile PIN" else "Set profile PIN",
                    key = "set-pin",
                    value = if (active.hasPin) "PIN required" else "Optional",
                    onClick = { pinDialog = ProfilePinMode.Set },
                )
                if (active.hasPin) {
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.LockOpen,
                        title = "Remove profile PIN",
                        key = "clear-pin",
                        onClick = { pinDialog = ProfilePinMode.Clear },
                    )
                }
            }
            auth.profilesByRecentUse.filter { it.id != auth.currentProfileId }.forEach { other ->
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.PersonRemove,
                    title = "Remove ${other.displayName}",
                    key = "remove.${other.id}",
                    value = other.email ?: other.apiUrl,
                    danger = true,
                    onClick = { removeTarget = other.id },
                )
            }
        }

        Spacer(Modifier.height(dimens.s5))

        SettingsGroup(label = "Playback") {
            SettingsDropdownRow(
                icon = Icons.Outlined.Audiotrack,
                title = "Default audio language",
                key = "audio-lang",
                valueLabel = AUDIO_LANG_OPTIONS.firstOrNull { it.first == audioLang }?.second ?: "Auto",
                options = AUDIO_LANG_OPTIONS,
                onSelect = { audioLang = it; UserSettings.putString(KEY_DEFAULT_AUDIO_LANG, it) },
            )
            SettingsDivider()
            SettingsDropdownRow(
                icon = Icons.Outlined.Subtitles,
                title = "Default subtitle language",
                key = "subtitle-lang",
                valueLabel = SUBTITLE_LANG_OPTIONS.firstOrNull { it.first == subtitleLang }?.second ?: "None",
                options = SUBTITLE_LANG_OPTIONS,
                onSelect = { subtitleLang = it; UserSettings.putString(KEY_DEFAULT_SUBTITLE_LANG, it) },
            )
            SettingsDivider()
            SettingsToggleRow(
                icon = Icons.Outlined.ClosedCaption,
                title = "Subtitles on by default",
                key = "subtitles-enabled",
                checked = subtitlesEnabled,
                onToggle = {
                    subtitlesEnabled = it
                    UserSettings.putString(KEY_SUBTITLES_ENABLED, it.toString())
                },
            )
            SettingsDivider()
            SettingsDropdownRow(
                icon = Icons.Outlined.Speed,
                title = "Default playback speed",
                key = "playback-speed",
                valueLabel = "${playbackSpeed}x",
                options = SPEED_OPTIONS,
                onSelect = { playbackSpeed = it; UserSettings.putString(KEY_DEFAULT_PLAYBACK_SPEED, it) },
            )
            // Only libVLC on Android exposes the output device.
            if (clientKind.startsWith("android")) {
                SettingsDivider()
                SettingsDropdownRow(
                    icon = Icons.Outlined.Speaker,
                    title = "Audio output",
                    key = "audio-output",
                    valueLabel = AUDIO_OUTPUT_OPTIONS.first { it.first == audioOutput }.second.substringBefore(" ("),
                    options = AUDIO_OUTPUT_OPTIONS,
                    onSelect = { audioOutput = it; Settings.putString(KEY_AUDIO_OUTPUT, it) },
                )
                SettingsDivider()
                SettingsToggleRow(
                    icon = Icons.Outlined.Tv,
                    title = "Match display refresh rate",
                    key = "match-display-refresh",
                    checked = matchDisplayRefresh,
                    onToggle = {
                        matchDisplayRefresh = it
                        Settings.putString(KEY_MATCH_DISPLAY_REFRESH, it.toString())
                    },
                )
            }
            if (decoderChoices.isNotEmpty()) {
                SettingsDivider()
                SettingsDropdownRow(
                    icon = Icons.Outlined.Memory,
                    title = "Video decoding",
                    key = "video-decoding",
                    valueLabel = decoderChoices.firstOrNull { it.decoding.key == videoDecoding }?.label ?: "Auto",
                    options = decoderChoices.map { it.decoding.key to it.label },
                    onSelect = { videoDecoding = it; Settings.putString(KEY_VIDEO_DECODING, it) },
                )
            }
        }

        Spacer(Modifier.height(dimens.s5))

        SettingsGroup(label = "Streaming") {
            SettingsDropdownRow(
                icon = Icons.Outlined.HighQuality,
                title = "Streaming quality",
                key = "quality",
                valueLabel = when (streamingQuality) {
                    "adaptive" -> "Adaptive"
                    "1080" -> "Max 1080p"
                    "720" -> "Max 720p"
                    "480" -> "Max 480p"
                    else -> "Auto"
                },
                options = QUALITY_OPTIONS,
                onSelect = { streamingQuality = it; UserSettings.putString(KEY_STREAMING_QUALITY, it) },
            )
        }

        Spacer(Modifier.height(dimens.s5))

        SettingsGroup(label = "Reader & TTS") {
            SettingsTextInputRow(
                icon = Icons.Outlined.RecordVoiceOver,
                title = "Preferred TTS voice",
                key = "tts-voice",
                value = ttsVoice,
                placeholder = "Voice name or ID",
                onSave = { ttsVoice = it; UserSettings.putString(KEY_TTS_VOICE, it) },
            )
            SettingsDivider()
            SettingsDropdownRow(
                icon = Icons.Outlined.Speed,
                title = "TTS speed",
                key = "tts-speed",
                valueLabel = "${ttsSpeed}x",
                options = TTS_SPEED_OPTIONS,
                onSelect = { ttsSpeed = it; UserSettings.putString(KEY_TTS_SPEED, it) },
            )
        }

        Spacer(Modifier.height(dimens.s5))

        SettingsGroup(label = "Server") {
            SettingsRow(
                icon = Icons.Outlined.Cloud,
                title = "Connected to",
                key = "server",
                value = displayServer.ifBlank { "—" },
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = "Sign out",
                key = "sign-out",
                value = "Return to login",
                danger = true,
                onClick = { showLogoutConfirm = true },
            )
        }

        Spacer(Modifier.height(dimens.s5))

        SettingsGroup(label = "About") {
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "Kobbler",
                key = "version",
                value = "v${AppVersion.versionName}",
            )
            // A TV is the one place a start-up timing is hardest to read and most worth
            // reading, because adb is across the room. Blank until something has played.
            dev.kobbler.app.ui.player.PlaybackTrace.last?.let { trace ->
                SettingsRow(
                    icon = Icons.Outlined.Speed,
                    title = "Last playback start",
                    key = "playbacktrace",
                    value = trace,
                )
            }
        }

        Spacer(Modifier.height(dimens.s7))
    }

    if (showHousehold) {
        HouseholdProfilesDialog(
            auth = auth,
            onDismiss = { showHousehold = false },
            onAdded = { added ->
                showHousehold = false
                householdMsg = if (added == 0) {
                    "Nothing added"
                } else {
                    "Added $added profile${if (added == 1) "" else "s"}"
                }
            },
        )
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

    val pendingRemoveId = removeTarget
    if (pendingRemoveId != null) {
        val target = auth.profiles.firstOrNull { it.id == pendingRemoveId }
        if (target != null) {
            ConfirmDialog(
                title = "Remove ${target.displayName}?",
                body = "This device will no longer stay signed in as ${target.email ?: target.displayName}. Server data is not affected.",
                confirmLabel = "Remove",
                danger = true,
                onConfirm = {
                    auth.removeProfile(pendingRemoveId)
                    removeTarget = null
                },
                onDismiss = { removeTarget = null },
            )
        } else {
            removeTarget = null
        }
    }

    if (showLogoutConfirm) {
        ConfirmDialog(
            title = "Log out?",
            body = "You'll need to re-authenticate with your Kobbler server.",
            confirmLabel = "Log out",
            danger = true,
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}

private val RowFocusInset = 6.dp

@Composable
private fun ProfileHeroCard(
    profile: Profile,
    meta: String,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey("profile-hero")
            .tvAutoGlow(cornerRadiusDp = 14f, scale = TvFocusScale.None)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Active profile",
                color = colors.textLo,
                style = KobappTheme.type.caption,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                profile.displayName,
                color = colors.textHi,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    color = colors.textLo,
                    style = KobappTheme.type.bodySm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Outlined.SwitchAccount,
            contentDescription = null,
            tint = colors.textMd,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingsGroup(
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
                .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
                // The card clips at its own bounds and a focused row's ring paints
                // outside the row's, so every row is inset far enough to keep it whole.
                .padding(RowFocusInset),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
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
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    key: String,
    value: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val titleColor = if (danger) colors.err else colors.textHi
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.focusKey(key) else Modifier)
            .let {
                if (onClick != null) {
                    it.tvAutoGlow(cornerRadiusDp = 8f, scale = TvFocusScale.None)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onClick)
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
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    key: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = 8f, scale = TvFocusScale.None)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onToggle(!checked) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textMd, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            color = colors.textHi,
            style = KobappTheme.type.body,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            // Null keeps the switch out of the focus order: the row is the target and
            // the row's click is what toggles it.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accSet,
                checkedThumbColor = colors.bg,
                uncheckedTrackColor = colors.elev3,
                uncheckedThumbColor = colors.textMd,
                uncheckedBorderColor = colors.line,
            ),
        )
    }
}

@Composable
private fun SettingsDropdownRow(
    icon: ImageVector,
    title: String,
    key: String,
    valueLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .focusKey(key)
                .tvAutoGlow(cornerRadiusDp = 8f, scale = TvFocusScale.None)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = colors.textMd, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                color = colors.textHi,
                style = KobappTheme.type.body,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                valueLabel,
                color = colors.textMd,
                style = KobappTheme.type.bodySm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textLo,
                modifier = Modifier.size(18.dp),
            )
        }
        // The row spans the page, so a menu anchored on it opens against the far edge of
        // the screen — a phone-width row put every option under the title instead of the
        // value it edits. A zero-size anchor at the row's trailing edge drops the menu
        // under the value.
        Box(Modifier.align(Alignment.BottomEnd)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = colors.elev2,
            ) {
                options.forEach { (optionKey, label) ->
                    DropdownMenuItem(
                        text = { Text(label, color = colors.textHi, style = KobappTheme.type.body) },
                        onClick = {
                            onSelect(optionKey)
                            expanded = false
                        },
                        // Padding first: the menu surface clips at its edge, so the ring
                        // has to be drawn inside it.
                        modifier = Modifier
                            .padding(horizontal = RowFocusInset, vertical = 2.dp)
                            .tvAutoGlow(cornerRadiusDp = 6f, scale = TvFocusScale.None),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTextInputRow(
    icon: ImageVector,
    title: String,
    key: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    val fieldFocus = remember { FocusRequester() }

    if (editing) {
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                modifier = Modifier.focusKey("$key.save"),
            ) { Text("Save", color = colors.bg, style = KobappTheme.type.body) }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .focusKey(key)
                .tvAutoGlow(cornerRadiusDp = 8f, scale = TvFocusScale.None)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable {
                    text = value
                    editing = true
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = colors.textMd, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                color = colors.textHi,
                style = KobappTheme.type.body,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                value.ifBlank { "Default" },
                color = colors.textLo,
                style = KobappTheme.type.bodySm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                tint = colors.textLo,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = colors.textHi, style = KobappTheme.type.h3) },
        text = { Text(body, color = colors.textMd, style = KobappTheme.type.body) },
        confirmButton = {
            KobButton(
                onClick = onConfirm,
                style = KobButtonStyle.Accent,
                accent = if (danger) colors.err else colors.accSet,
            ) { Text(confirmLabel, color = colors.bg, style = KobappTheme.type.body) }
        },
        dismissButton = {
            KobButton(
                onClick = onDismiss,
                style = KobButtonStyle.Ghost,
            ) { Text("Cancel", color = colors.textHi, style = KobappTheme.type.body) }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}
