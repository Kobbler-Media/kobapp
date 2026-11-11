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
        namespace = "dev.kobbler.kobplayer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
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
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.coil.compose)
            api(libs.kotlinx.coroutines.core)
        }

        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.vlcj)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        wasmJsMain.dependencies {
            implementation(npm("hls.js", libs.versions.hlsjs.get()))
        }

        androidMain.dependencies {
            implementation(libs.libvlc.android)
            implementation(libs.media3.common)
            implementation(libs.media3.session)
        }

        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
    }
}

// Desktop plays through the libvlc kobvlc builds for Linux. -Pkobvlc.linux=<kobvlc>/work/linux/pkg
// tries a local kobvlc build instead of the published one.
val libvlcLinux = configurations.create("libvlcLinux") { isCanBeConsumed = false }
dependencies { libvlcLinux("${libs.libvlc.linux.get()}@tar.gz") }
val libvlcDir = layout.buildDirectory.dir("libvlc")
val unpackLibvlc = tasks.register<Sync>("unpackLibvlc") {
    onlyIf { org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux }
    val local = providers.gradleProperty("kobvlc.linux")
    from(provider<Any> { local.orNull?.let(::file) ?: tarTree(libvlcLinux.singleFile) })
    into(libvlcDir)
}
tasks.named<Test>("desktopTest") {
    dependsOn(unpackLibvlc)
    systemProperty("kobplayer.libvlc.dir", libvlcDir.get().dir("lib").asFile.path)
}
