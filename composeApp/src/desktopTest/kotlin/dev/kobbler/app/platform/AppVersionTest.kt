package dev.kobbler.app.platform

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionTest {

    @Test
    fun `desktop reports the version it was built as`() {
        val props = Properties().apply { File("../version.properties").reader().use(::load) }
        assertEquals(props.getProperty("versionName"), AppVersion.versionName)
        assertEquals(props.getProperty("versionCode").toInt(), AppVersion.versionCode)
    }
}
