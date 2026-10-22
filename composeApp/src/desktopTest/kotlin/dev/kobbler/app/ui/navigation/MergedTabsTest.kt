package dev.kobbler.app.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/** Wanted folds into Downloads and Notifications into Activity: one entry for each pair. */
class MergedTabsTest {

    @Test
    fun `every menu lists the merged pages once and the old ones not at all`() {
        for (config in listOf(navConfigFor(isKids = false), navConfigFor(isKids = true))) {
            for (tabs in listOf(config.drawerTabs, config.sidebarTabs)) {
                val titles = tabs.map { it.title }
                assertEquals(1, titles.count { it == "Downloads" }, "$titles")
                assertEquals(1, titles.count { it == "Activity" }, "$titles")
                assertEquals(0, titles.count { it == "Wanted" || it == "Notifications" }, "$titles")
            }
        }
    }
}
