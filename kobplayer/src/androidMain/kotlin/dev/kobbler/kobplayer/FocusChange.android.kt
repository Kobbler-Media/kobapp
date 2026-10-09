package dev.kobbler.kobplayer

import android.media.AudioManager

fun focusChangeOf(audioFocusChange: Int): FocusChange? = when (audioFocusChange) {
    AudioManager.AUDIOFOCUS_LOSS -> FocusChange.Loss
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LossTransient
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LossTransientCanDuck
    AudioManager.AUDIOFOCUS_GAIN -> FocusChange.Gain
    else -> null
}
