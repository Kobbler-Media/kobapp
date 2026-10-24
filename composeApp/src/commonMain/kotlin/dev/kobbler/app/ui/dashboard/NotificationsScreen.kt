package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.components.ListSkeleton
import dev.kobbler.app.ui.PinnedHeader
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.NotificationDto
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun NotificationsScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    tabSwitch: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val (state, retry) = rememberCached("notifications") { api.getNotifications() }
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val compact = formFactor == FormFactor.Phone
    val accent = colors.accNotif
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }

    AsyncContent(state, skeleton = { ListSkeleton() }, onRetry = retry, modifier = modifier) { data ->
        var notifications by remember(data) { mutableStateOf(data.notifications) }
        val unreadCount = notifications.count { !it.read }

        // The header stays when the inbox is empty: it carries the switch back to Activity.
        PinnedHeader(
            header = {
                Box(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                    BrowseHeader(
                        title = "Notifications",
                        accent = accent,
                        countLine = if (unreadCount > 0) "$unreadCount unread" else "all caught up",
                        compact = compact,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(dimens.s3), verticalAlignment = Alignment.CenterVertically) {
                                if (unreadCount > 0) {
                                    KobButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    api.markAllNotificationsRead()
                                                    notifications = notifications.map { it.copy(read = true) }
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        style = KobButtonStyle.Ghost,
                                        modifier = Modifier.focusKey("mark-all-read"),
                                    ) {
                                        Icon(Icons.Outlined.DoneAll, null, modifier = Modifier.size(16.dp))
                                        Text("Mark all read", style = KobappTheme.type.caption)
                                    }
                                }
                                tabSwitch?.invoke()
                            }
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (notifications.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.NotificationsNone,
                    title = "No notifications",
                    subtitle = "You're all caught up",
                )
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = gutter, end = gutter, top = dimens.s2 + LocalTopChromeOverlap.current, bottom = dimens.s2),
                    verticalArrangement = Arrangement.spacedBy(dimens.s1),
                    modifier = Modifier.scrollsUnderTopChrome(listState).fillMaxSize(),
                ) {
                    items(notifications, key = { it.id }) { notification ->
                        var isRead by remember(notification.id) { mutableStateOf(notification.read) }
                        NotificationItem(
                            notification = notification,
                            isRead = isRead,
                            accent = accent,
                            onClick = {
                                if (!isRead) {
                                    isRead = true
                                    scope.launch {
                                        try { api.markNotificationRead(notification.id) } catch (_: Exception) {}
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    notification: NotificationDto,
    isRead: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Row(
        modifier = Modifier.fillMaxWidth()
            .focusKey("notification.${notification.id}")
            .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.02f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elev1)
            .tvClickable(focusState, onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (!isRead) {
            Box(
                Modifier
                    .padding(top = 6.dp, end = 10.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                notification.title,
                style = KobappTheme.type.body.copy(
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                ),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (notification.message.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    notification.message,
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
