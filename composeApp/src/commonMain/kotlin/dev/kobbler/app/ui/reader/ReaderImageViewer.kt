package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import dev.kobbler.app.ui.components.rememberZoomState
import dev.kobbler.app.ui.components.zoomable
import dev.kobbler.app.ui.components.zoomed
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.safeTopPadding

/** A book's picture over the whole screen, to pinch and pan around. A tap or BACK closes it. */
@Composable
internal fun ReaderImageViewer(image: ImageRef, book: ReaderBook, onDismiss: () -> Unit) {
    val zoom = rememberZoomState()
    val context = LocalPlatformContext.current
    // Full resolution, not the screen-sized decode the page used: zooming is for the detail.
    val request = remember(image.src) {
        ImageRequest.Builder(context).data(book.assetUrl(image.src)).size(Size.ORIGINAL).build()
    }
    InterceptBack(enabled = true, onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black).zoomable(zoom, onTap = { if (!zoom.zoomed) onDismiss() })) {
        AsyncImage(
            model = request,
            contentDescription = image.alt,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().zoomed(zoom),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).safeTopPadding().padding(8.dp)) {
            Icon(Icons.Outlined.Close, "Close image", tint = Color.White)
        }
    }
}
