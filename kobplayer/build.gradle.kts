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

// Desktop plays through the libvlc kobvlc builds for Linux. -Pkobvlc.linux=<kobvlc>/work/linux/pkg
// tries a local kobvlc build instead of the published one.
val libvlcLinux by configurations.creating { isCanBeConsumed = false }
dependencies { libvlcLinux("${libs.libvlc.linux.get()}@tar.gz") }
val libvlcDir = layout.buildDirectory.dir("libvlc")
val unpackLibvlc by tasks.registering(Sync::class) {
    onlyIf { org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux }
    val local = providers.gradleProperty("kobvlc.linux")
    from(provider<Any> { local.orNull?.let(::file) ?: tarTree(libvlcLinux.singleFile) })
    into(libvlcDir)
}
tasks.named<Test>("desktopTest") {
    dependsOn(unpackLibvlc)
    systemProperty("kobplayer.libvlc.dir", libvlcDir.get().dir("lib").asFile.path)
}
