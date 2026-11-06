package dev.kobbler.app.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Outlives the screen that saves: an exit save launched in the screen's own scope was cancelled by the navigation that caused it. */
internal val ProgressSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
