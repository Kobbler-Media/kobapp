package dev.kobbler.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class KobappColors(
    val bg: Color,
    val elev1: Color,
    val elev2: Color,
    val elev3: Color,
    val line: Color,
    val lineSoft: Color,

    val textHi: Color,
    val textMd: Color,
    val textLo: Color,
    val textXlo: Color,

    val accHome: Color,
    val accMovies: Color,
    val accTv: Color,
    val accSports: Color,
    val accBooks: Color,
    val accAudio: Color,
    val accLive: Color,
    val accIptv: Color,
    val accWanted: Color,
    val accDown: Color,
    val accNotif: Color,
    val accCal: Color,
    val accSet: Color,

    val ok: Color,
    val warn: Color,
    val err: Color,
)

val KobappColorsDefault = KobappColors(
    bg = Color(0xFF1F1D19),
    elev1 = Color(0xFF272521),
    elev2 = Color(0xFF302D28),
    elev3 = Color(0xFF3B3832),
    line = Color(0xFF45413B),
    lineSoft = Color(0xFF332F2A),

    textHi = Color(0xFFF5F2EC),
    textMd = Color(0xFFB6AFA3),
    // The four surfaces span only 2.6:1, so a dim tier does not read as subtle, it reads
    // as absent. textLo sat under AA on every surface and textXlo under 2.4:1 on bg while
    // carrying real labels. Both are lifted to the floor TextContrastTest holds them to,
    // keeping the warm hue and the gaps between tiers.
    textLo = Color(0xFF9A9389),
    textXlo = Color(0xFF888077),

    accHome = Color(0xFFF0C66A),
    accMovies = Color(0xFFE8745A),
    accTv = Color(0xFF6DC3D6),
    accSports = Color(0xFF5FCF8A),
    accBooks = Color(0xFFD9B985),
    accAudio = Color(0xFFD977C5),
    accLive = Color(0xFFEE7D4F),
    accIptv = Color(0xFF7FA8B8),
    accWanted = Color(0xFFA98CE5),
    accDown = Color(0xFF5FD5CF),
    accNotif = Color(0xFFDCD070),
    accCal = Color(0xFF82A4E8),
    accSet = Color(0xFFB1ACA5),

    ok = Color(0xFF5FCC8A),
    warn = Color(0xFFE3C36C),
    err = Color(0xFFE26B57),
)

val LocalKobappColors = compositionLocalOf { KobappColorsDefault }
