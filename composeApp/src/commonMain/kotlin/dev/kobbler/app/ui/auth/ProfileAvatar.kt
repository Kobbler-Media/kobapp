package dev.kobbler.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import coil3.compose.AsyncImage
import dev.kobbler.app.data.Profile

private val palette = listOf(
    0xFF6D4AFF, 0xFFE55A5A, 0xFF3FAE7C, 0xFFEB8C2E,
    0xFF2F8FF7, 0xFFC04ED4, 0xFF2BB6A4, 0xFFD8B130,
    0xFF5D6D9E, 0xFFD24E91,
).map { Color(it) }

private fun colorFor(seed: String): Color {
    if (seed.isEmpty()) return palette[0]
    var h = 0
    for (c in seed) h = (h * 31 + c.code)
    return palette[((h % palette.size) + palette.size) % palette.size]
}

private fun initialOf(profile: Profile): String {
    val name = profile.displayName.trim()
    return name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

@Composable
fun ProfileAvatar(
    profile: Profile,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
) {
    val bg = colorFor(profile.avatarSeed)
    val avatarUrl = profile.avatarUrl?.let { path ->
        if (path.startsWith("http")) path else "${profile.apiUrl.trimEnd('/')}$path"
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = profile.displayName,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Text(
                initialOf(profile),
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
