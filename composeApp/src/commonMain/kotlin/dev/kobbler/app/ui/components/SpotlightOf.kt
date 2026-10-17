package dev.kobbler.app.ui.components

import dev.kobbler.app.data.Book
import dev.kobbler.app.data.ContinueItem
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.UpNextItem

fun UpNextItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = mediaArtUrl)

fun ContinueItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = imageUrl)

fun RecentlyAddedItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = imageUrl)

fun DiscoverItem.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = posterUrl)

fun Movie.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = posterUrl)

fun TvShow.spotlight() = Spotlight(backdropUrl = backdropUrl, posterUrl = posterUrl)

fun Book.spotlight() = Spotlight(posterUrl = coverUrl)
