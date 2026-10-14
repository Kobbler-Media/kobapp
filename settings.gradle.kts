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
        // libvlc as github.com/Kobbler-Media/kobvlc builds it, from that repo's releases.
        ivy("https://github.com/Kobbler-Media/kobvlc/releases/download") {
            patternLayout {
                artifact("v[revision]/[artifact]-[revision].[ext]")
                ivy("v[revision]/ivy-[revision].xml")
            }
            metadataSources { ivyDescriptor() }
            content { includeModule("dev.kobbler.vlc", "libvlc-all") }
        }
    }
}

rootProject.name = "kobbler-app"
include(":kobplayer")
include(":kobpalette")
include(":kobfocus")
include(":composeApp")
include(":androidApp")
include(":baselineprofile")
