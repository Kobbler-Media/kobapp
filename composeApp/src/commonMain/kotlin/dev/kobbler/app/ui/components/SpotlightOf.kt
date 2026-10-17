package dev.kobbler.app.ui.components

import dev.kobbler.app.data.Book
import dev.kobbler.app.data.ContinueItem
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.UpNextItem

fun UpNextItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = mediaArtUrl, title = title)

fun ContinueItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = imageUrl, title = title)

fun RecentlyAddedItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = imageUrl, title = title)

fun DiscoverItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = posterUrl, title = title)

fun Movie.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = posterUrl, title = title)

fun TvShow.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = posterUrl, title = title)

fun Book.spotlight() = Spotlight(posterUrl = coverUrl, title = title)
