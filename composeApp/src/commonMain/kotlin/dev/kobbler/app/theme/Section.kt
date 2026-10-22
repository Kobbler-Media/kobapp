package dev.kobbler.app.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

enum class Section {
    Home, Movies, Tv, Sports, Books, Audio, LiveTv, Iptv,
    Downloads, Calendar, Settings,
    Activity,
}

fun KobappColors.accentFor(section: Section): Color = when (section) {
    Section.Home -> accHome
    Section.Movies -> accMovies
    Section.Tv -> accTv
    Section.Sports -> accSports
    Section.Books -> accBooks
    Section.Audio -> accAudio
    Section.LiveTv -> accLive
    Section.Iptv -> accIptv
    Section.Downloads -> accDown
    Section.Calendar -> accCal
    Section.Settings -> accSet
    Section.Activity -> accNotif
}

val LocalSection = compositionLocalOf { Section.Home }
