plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.multiplatform.library)
}

// iOS needs an Apple host (the Xcode toolchain) to build at all. Declaring the targets
// off-Mac makes `gradle build` fail on :compileIosMainKotlinMetadata, so they are simply
// absent unless the host can build them.
val hostIsMac = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac

kotlin {
    android {
        namespace = "dev.kobbler.kobfocus"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm("desktop")

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    if (hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
        }

        getByName("desktopTest") {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
