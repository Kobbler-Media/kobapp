package dev.kobbler.app.watchnext

import dev.kobbler.app.data.AuthScreen
import dev.kobbler.app.data.ContinueItem
import dev.kobbler.app.data.MediaType
import dev.kobbler.app.data.Profile
import io.ktor.http.URLBuilder
import io.ktor.http.Url

private const val Scheme = "kobapp"
private const val Path = "/watch-next"

data class WatchNextLaunch(
    val profileId: String,
    val link: String,
    val title: String,
    val subtitle: String?,
) {
    fun toUri(host: String): String = URLBuilder("$Scheme://$host$Path").apply {
        parameters.append("profile", profileId)
        parameters.append("link", link)
        parameters.append("title", title)
        subtitle?.let { parameters.append("subtitle", it) }
    }.buildString()

    fun toContinueItem(): ContinueItem = ContinueItem(
        id = link.substringAfterLast('/'),
        mediaType = when {
            link.startsWith("/movies/") -> MediaType.Movie
            link.startsWith("/sports/") -> MediaType.SportsEvent
            else -> MediaType.TvShow
        },
        title = title,
        subtitle = subtitle,
        imageUrl = "",
        progress = 0f,
        link = link,
        started = true,
    )
}

fun parseWatchNextLaunch(uri: String?): WatchNextLaunch? {
    val url = runCatching { Url(uri ?: return null) }.getOrNull() ?: return null
    if (url.protocol.name != Scheme || url.encodedPath != Path) return null
    val params = url.parameters
    return WatchNextLaunch(
        profileId = params["profile"] ?: return null,
        link = params["link"] ?: return null,
        title = params["title"].orEmpty(),
        subtitle = params["subtitle"],
    )
}

sealed interface LaunchStep {
    data object Wait : LaunchStep
    data object Drop : LaunchStep
    data object Play : LaunchStep
    data class SwitchTo(val profileId: String) : LaunchStep
}

fun launchStep(launch: WatchNextLaunch, profiles: List<Profile>, currentProfileId: String?, screen: AuthScreen): LaunchStep {
    if (screen == AuthScreen.Loading) return LaunchStep.Wait
    val profile = profiles.firstOrNull { it.id == launch.profileId } ?: return LaunchStep.Drop
    return when {
        screen == AuthScreen.LoggedIn && currentProfileId == profile.id -> LaunchStep.Play
        !profile.hasPin && !profile.needsRelink -> LaunchStep.SwitchTo(profile.id)
        screen == AuthScreen.Picker -> LaunchStep.Wait
        else -> LaunchStep.Drop
    }
}
