package dev.kobbler.kobplayer

enum class FocusChange { Loss, LossTransient, LossTransientCanDuck, Gain }

enum class FocusAction { None, Pause, Resume, Duck, Restore }

/**
 * Resumes on focus gain only what a transient loss paused. A viewer's own pause, or any
 * transport the viewer touched while focus was away, must survive the interruption.
 */
class FocusResume(private val canDuck: Boolean = true) {
    private var pausedByLoss = false

    fun onChange(change: FocusChange, playing: Boolean): FocusAction = when (change) {
        FocusChange.Loss -> {
            pausedByLoss = false
            if (playing) FocusAction.Pause else FocusAction.None
        }
        FocusChange.LossTransientCanDuck if canDuck ->
            if (playing) FocusAction.Duck else FocusAction.None
        FocusChange.LossTransient, FocusChange.LossTransientCanDuck -> {
            if (playing) pausedByLoss = true
            if (playing) FocusAction.Pause else FocusAction.None
        }
        FocusChange.Gain -> {
            val resume = pausedByLoss && !playing
            pausedByLoss = false
            if (resume) FocusAction.Resume else FocusAction.Restore
        }
    }

    fun forget() {
        pausedByLoss = false
    }
}
