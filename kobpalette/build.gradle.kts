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
            // Only Compose UI for Color type
            implementation(libs.compose.ui)
        }
    }
}
