package dev.kobbler.app.ui.components

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KobButtonSizeTest {

    private fun buttonHeightDp(size: KobButtonSize): Float {
        var px = 0
        var density = 1f
        runUiTest {
            setContent {
                KobappTheme {
                    density = LocalDensity.current.density
                    Box(Modifier.width(360.dp)) {
                        KobButton(
                            text = "On now",
                            onClick = {},
                            size = size,
                            modifier = Modifier.onGloballyPositioned { px = it.size.height },
                        )
                    }
                }
            }
            waitForIdle()
        }
        return px / density
    }

    @Test
    fun aDefaultButtonIsExactlyTheTouchTargetTall() {
        assertEquals(48f, buttonHeightDp(KobButtonSize.Default), 1f)
    }

    @Test
    fun theTouchTargetFloorDoesNotStackOnTopOfThePadding() {
        val prominent = buttonHeightDp(KobButtonSize.Prominent)
        assertTrue(
            prominent < 60f,
            "a prominent button should grow from its own padding, not the touch target plus it, but was ${prominent}dp",
        )
    }
}
