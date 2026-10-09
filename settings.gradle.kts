pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "kobbler-app"
include(":kobplayer")
include(":kobpalette")
include(":kobfocus")
include(":composeApp")
include(":androidApp")
include(":baselineprofile")
