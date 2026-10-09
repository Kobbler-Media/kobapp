package dev.kobbler.app.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

enum class Section {
    Home, Movies, Tv, Sports, Books, Audio, LiveTv, Iptv,
    Wanted, Downloads, Notifications, Calendar, Settings,
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
    Section.Wanted -> accWanted
    Section.Downloads -> accDown
    Section.Notifications -> accNotif
    Section.Calendar -> accCal
    Section.Settings -> accSet
    Section.Activity -> accNotif
}

val LocalSection = compositionLocalOf { Section.Home }
