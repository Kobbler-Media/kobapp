package dev.kobbler.app.data

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.network.ktor3.asNetworkClient
import dev.kobbler.app.platform.applyPlatformImageOptions
import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.IOException
import okio.Path

fun kobImageLoader(context: PlatformContext, httpClient: HttpClient, cacheDir: Path?): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(NetworkFetcher.Factory(networkClient = { CompleteBodyNetworkClient(httpClient.asNetworkClient()) })) }
        .apply { cacheDir?.let { dir -> diskCache { DiskCache.Builder().directory(dir).build() } } }
        .applyPlatformImageOptions()
        .build()

// Ktor's OkHttp engine stops pumping a body once its call is cancelled and closes the channel
// cleanly instead of with an error, and Coil commits whatever arrived to the disk cache as the
// whole image — a load cancelled by scrolling past leaves a half-drawn poster for good.
private class CompleteBodyNetworkClient(private val delegate: NetworkClient) : NetworkClient {
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T =
        delegate.executeRequest(request) { response ->
            val body = response.body ?: return@executeRequest block(response)
            val bytes = Buffer().also { body.writeTo(it) }
            currentCoroutineContext().ensureActive()
            val expected = response.headers["Content-Length"]?.toLongOrNull()
            if (expected != null && bytes.size != expected) {
                throw IOException("${request.url}: received ${bytes.size} of $expected bytes")
            }
            block(response.copy(body = NetworkResponseBody(bytes)))
        }
}
