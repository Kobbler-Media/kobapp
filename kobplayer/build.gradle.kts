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

// iOS links the static libvlc.xcframework kobvlc builds. -Pkobvlc.ios=<kobvlc>/work/ios/libvlc.xcframework
// tries a local kobvlc build instead of the published one.
val libvlcIosDir = layout.buildDirectory.dir("libvlc-ios")

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
        mapOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-arm64-simulator").forEach { (target, slice) ->
            target.compilations.getByName("main").cinterops.create("libvlc") {
                val dir = libvlcIosDir.get().dir("libvlc.xcframework/$slice")
                includeDirs(dir.dir("Headers"))
                extraOpts("-libraryPath", dir.asFile.path)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.coil.compose)
            implementation(project(":kobfocus"))
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

if (hostIsMac) {
    val libvlcIos = configurations.create("libvlcIos") { isCanBeConsumed = false }
    dependencies { libvlcIos("${libs.libvlc.ios.get()}@tar.gz") }
    val unpackLibvlcIos = tasks.register<Sync>("unpackLibvlcIos") {
        val local = providers.gradleProperty("kobvlc.ios").orNull
        if (local != null) from(local) { into("libvlc.xcframework") } else from(provider { tarTree(libvlcIos.singleFile) })
        into(libvlcIosDir)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach { inputs.files(unpackLibvlcIos) }
}
