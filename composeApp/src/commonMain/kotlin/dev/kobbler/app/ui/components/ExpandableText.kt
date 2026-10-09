package dev.kobbler.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 4,
) {
    val colors = KobappTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var hasOverflow by remember { mutableStateOf(false) }

    Column(modifier.animateContentSize()) {
        Text(
            text,
            style = KobappTheme.type.body,
            color = colors.textHi.copy(alpha = 0.85f),
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) hasOverflow = result.hasVisualOverflow
            },
        )
        if (hasOverflow || expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) "Show less" else "Show more",
                style = KobappTheme.type.caption,
                color = colors.accWanted,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}
