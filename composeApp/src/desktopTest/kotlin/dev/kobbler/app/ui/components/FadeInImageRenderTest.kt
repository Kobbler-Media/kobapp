package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** Card art has to reach the screen whether it came off the network or out of memory. */
@OptIn(ExperimentalTestApi::class)
class FadeInImageRenderTest {

    private fun loader(source: DataSource) = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            add(Interceptor { chain ->
                val bitmap = Bitmap().apply {
                    allocPixels(ImageInfo.makeN32(40, 60, ColorAlphaType.PREMUL))
                    erase(0xFF3366CC.toInt())
                }
                SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = source)
            })
        }
        .build()

    @Test
    fun `loaded art is drawn`() {
        for (source in listOf(DataSource.NETWORK, DataSource.DISK, DataSource.MEMORY_CACHE)) {
            runDesktopComposeUiTest(width = 100, height = 150) {
                SingletonImageLoader.setUnsafe(loader(source))
                try {
                    setContent {
                        FadeInImage(model = "http://kobbler.test/p.jpg", contentDescription = null, modifier = Modifier.size(100.dp, 150.dp))
                    }
                    mainClock.advanceTimeBy(2_000)
                    waitForIdle()
                    val pixels = onRoot().captureToImage().toPixelMap()
                    val c = pixels[pixels.width / 2, pixels.height / 2]
                    assertEquals(0x3366CC, argbOf(c) and 0xFFFFFF, "$source")
                } finally {
                    SingletonImageLoader.reset()
                }
            }
        }
    }

    private fun argbOf(c: androidx.compose.ui.graphics.Color): Int {
        fun ch(f: Float) = (f * 255 + 0.5f).toInt()
        return (ch(c.alpha) shl 24) or (ch(c.red) shl 16) or (ch(c.green) shl 8) or ch(c.blue)
    }
}
