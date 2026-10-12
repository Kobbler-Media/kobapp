package dev.kobbler.kobplayer.ui

/**
 * The input a player is driven with, which decides its chrome. [Pointer] has arrow keys
 * as well as a mouse, so it shares the D-pad seeking of [Tv] but keeps a way out by mouse.
 */
enum class PlayerChrome { Touch, Pointer, Tv }
