package dev.kobbler.app.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.RosterUser
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import kotlinx.coroutines.launch

/**
 * Provisions every other Kobbler user onto this device in one pass, using the active admin
 * token to mint a token per user. Without it, each profile costs its own PIN pairing.
 */
@Composable
fun HouseholdProfilesDialog(
    auth: AuthState,
    onDismiss: () -> Unit,
    onAdded: (Int) -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val scope = rememberCoroutineScope()

    var roster by remember { mutableStateOf<List<RosterUser>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val linked = auth.linkedUserIds
        roster = auth.fetchRoster().filter { it.id !in linked }
    }

    val available = roster

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Add household profiles", color = colors.textHi, style = type.h3) },
        text = {
            when {
                available == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.textLo)
                    Text("Loading users…", color = colors.textLo, style = type.body)
                }

                available.isEmpty() -> Text(
                    "Every Kobbler user is already on this device.",
                    color = colors.textMd,
                    style = type.body,
                )

                else -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Each one gets its own key on this device. Profile PINs still apply.",
                        color = colors.textLo,
                        style = type.caption,
                    )
                    Spacer(Modifier.height(12.dp))
                    available.forEach { user ->
                        val checked = user.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !working) {
                                    selected = if (checked) selected - user.id else selected + user.id
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = colors.accSet),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    user.display_name?.takeIf { it.isNotBlank() } ?: user.email,
                                    color = colors.textHi,
                                    style = type.body,
                                )
                                Text(
                                    "${user.email} · ${user.role}",
                                    color = colors.textLo,
                                    style = type.caption,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!available.isNullOrEmpty()) {
                KobButton(
                    onClick = {
                        if (working || selected.isEmpty()) return@KobButton
                        working = true
                        scope.launch {
                            val added = auth.addHouseholdProfiles(selected.toList())
                            working = false
                            onAdded(added)
                        }
                    },
                    style = KobButtonStyle.Accent,
                    accent = colors.accSet,
                ) {
                    Text(
                        if (working) "Adding…" else "Add ${selected.size} profile${if (selected.size == 1) "" else "s"}",
                        color = colors.bg,
                        style = type.body,
                    )
                }
            }
        },
        dismissButton = {
            KobButton(onClick = { if (!working) onDismiss() }, style = KobButtonStyle.Ghost) {
                Text(if (available.isNullOrEmpty()) "Close" else "Cancel", color = colors.textHi, style = type.body)
            }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}
