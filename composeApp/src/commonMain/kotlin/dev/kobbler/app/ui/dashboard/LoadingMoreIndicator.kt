package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Takes the flag as a lambda so a page fetch flipping it recomposes this spinner alone,
// not the browse grid it sits over.
@Composable
internal fun BoxScope.LoadingMoreIndicator(loading: () -> Boolean, accent: Color) {
    if (!loading()) return
    Box(
        Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomCenter),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = accent, modifier = Modifier.size(24.dp))
    }
}
