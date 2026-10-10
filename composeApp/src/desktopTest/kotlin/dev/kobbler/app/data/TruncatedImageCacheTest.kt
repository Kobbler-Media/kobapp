package dev.kobbler.app.data

import coil3.PlatformContext
import coil3.request.ImageRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath

class TruncatedImageCacheTest {
    private val url = "http://kobbler.test/poster.png"
    private val png = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    // Ktor's OkHttp engine stops pumping the body once its call is cancelled and closes the
    // channel cleanly rather than with an error, so a scrolled-away load hands Coil a short
    // body that looks complete. This engine does the same thing on demand.
    private fun cached(body: ByteArray, contentLength: Int): Boolean {
        val engine = MockEngine {
            respond(
                ByteReadChannel(body),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("image/png"), HttpHeaders.ContentLength to listOf("$contentLength")),
            )
        }
        val loader = kobImageLoader(PlatformContext.INSTANCE, HttpClient(engine), Files.createTempDirectory("kob-images").toOkioPath())
        runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(url).build()) }
        return loader.diskCache!!.openSnapshot(url)?.also { it.close() } != null
    }

    @Test
    fun `a body that ends short of its length is not cached`() {
        assertFalse(cached(png.copyOf(png.size / 2), png.size), "truncated body was cached")
    }

    @Test
    fun `a complete body is cached`() {
        assertTrue(cached(png, png.size))
    }
}
