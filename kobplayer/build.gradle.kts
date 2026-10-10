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
        namespace = "dev.kobbler.kobplayer"
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
                baseName = "KobPlayer"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.coil.compose)
            api(libs.kotlinx.coroutines.core)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.vlcj)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.libvlc.android)
            implementation(libs.media3.common)
        }

        if (hostIsMac) {
            iosMain.dependencies {
                // MobileVLCKit 3.3.15 via CocoaPods — configured in the iOS app's Podfile
            }
        }
    }
}

val kobvaapiDir = layout.buildDirectory.dir("kobvaapi")
val buildKobvaapi by tasks.registering(Exec::class) {
    val script = file("native/kobvaapi/build.sh")
    onlyIf { org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux }
    inputs.dir(script.parentFile)
    outputs.dir(kobvaapiDir)
    commandLine(script, kobvaapiDir.get().asFile)
}
tasks.named<Test>("desktopTest") {
    dependsOn(buildKobvaapi)
    systemProperty("kobplayer.vlc.plugins", kobvaapiDir.get().asFile.path)
}
