plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.multiplatform.library)
}

// iOS needs an Apple host to build at all (Xcode toolchain, and MobileVLCKit via
// CocoaPods for kobplayer). Declaring the targets off-Mac makes `gradle build` fail on
// :compileIosMainKotlinMetadata, so they are simply absent unless the host can build them.
val hostIsMac = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    android {
        namespace = "dev.kobbler.kobpalette"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm("desktop")

    wasmJs {
        browser()
        binaries.executable()
    }

    if (hostIsMac) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            it.binaries.framework {
                baseName = "KobPalette"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Only Compose UI for Color type
            implementation(compose.ui)
        }
    }
}
