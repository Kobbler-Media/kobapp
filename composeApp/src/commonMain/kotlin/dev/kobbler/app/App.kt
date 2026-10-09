@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package dev.kobbler.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import dev.kobbler.app.data.AuthScreen
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.defaultFormFactor
import dev.kobbler.app.platform.imageCacheDir
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.LocalFocusScopeStore
import dev.kobbler.kobfocus.rememberFocusScopeStore
import dev.kobbler.app.ui.auth.PinLoginScreen
import dev.kobbler.app.ui.auth.ProfilePickerScreen
import dev.kobbler.app.ui.components.RefreshWatchStateOnFocus
import dev.kobbler.app.ui.navigation.AdaptiveScaffold
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import okio.Path.Companion.toPath

@Composable
fun App(
    formFactor: FormFactor = defaultFormFactor(),
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val auth = remember { AuthState(scope, formFactor) }

    // Use a shared HttpClient whose auth header updates when token changes.
    // Falls back to any linked profile's token so the picker can load avatars
    // before a profile is confirmed.
    val imageHttpClient = remember {
        HttpClient().also { client ->
            client.requestPipeline.intercept(HttpRequestPipeline.State) {
                val tok = dev.kobbler.app.data.imageAuthToken(
                    context.url.buildString(),
                    auth.currentProfile,
                    auth.profiles,
                )
                tok?.let { context.header(HttpHeaders.Authorization, "Bearer $it") }
            }
        }
    }

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = imageHttpClient)) }
            .apply { imageCacheDir?.let { dir -> diskCache { DiskCache.Builder().directory(dir.toPath()).build() } } }
            .build()
    }

    RefreshWatchStateOnFocus(LocalWindowInfo.current.isWindowFocused)

    KobappTheme(formFactor = formFactor) {
        // The one full-screen background under every screen; the window draws none.
        Box(Modifier.fillMaxSize().background(KobappTheme.colors.bg)) {
            androidx.compose.runtime.CompositionLocalProvider(
                dev.kobbler.app.data.LocalAuthState provides auth,
                LocalFocusScopeStore provides rememberFocusScopeStore(),
                dev.kobbler.app.ui.navigation.LocalBackInterceptors provides
                    androidx.compose.runtime.remember { dev.kobbler.app.ui.navigation.BackInterceptors() },
            ) {
                when (auth.screen) {
                    AuthScreen.Loading -> {} // Brief flash while checking stored token
                    AuthScreen.Login -> FocusScopeHost(id = AuthScreen.Login) { PinLoginScreen(auth) }
                    AuthScreen.Picker -> FocusScopeHost(id = AuthScreen.Picker) { ProfilePickerScreen(auth, formFactor) }
                    AuthScreen.LoggedIn -> AdaptiveScaffold(auth, formFactor, onToggleFullscreen, isFullscreen)
                }
            }
        }
    }
}
