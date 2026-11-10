import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.hot.reload)
}

fun readVersionProps(): Map<String, String> =
    file("${rootDir}/version.properties").readLines()
        .filter { it.contains("=") }
        .associate { val (k, v) = it.split("=", limit = 2); k.trim() to v.trim() }

// Desktop has no manifest to carry the version the way Android's does, so it ships as a
// resource the app reads at runtime.
val desktopVersionResource = tasks.register("desktopVersionResource") {
    val props = file("${rootDir}/version.properties")
    val out = layout.buildDirectory.dir("generated/desktopVersion")
    inputs.file(props)
    outputs.dir(out)
    doLast { props.copyTo(out.get().file("kobapp-version.properties").asFile, overwrite = true) }
}

val pinServiceSource = tasks.register("pinServiceSource") {
    val url = providers.gradleProperty("pinServiceUrl").orElse("https://pin.biboop.com")
    val out = layout.buildDirectory.dir("generated/pinService")
    inputs.property("pinServiceUrl", url)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/kobbler/app/data/PinServiceUrl.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package dev.kobbler.app.data\n\n" +
                "internal const val PIN_SERVICE = \"${url.get().trimEnd('/')}\"\n",
        )
    }
}

// jpackage requires plain MAJOR.MINOR.PATCH; strip the +<sha> suffix.
val pkgVersion: String = readVersionProps()["versionName"]!!.substringBefore('+')

// macOS jpackage rejects MAJOR=0; promote 0.x.y → 1.x.y for mac targets only.
val macPkgVersion: String = pkgVersion.split('.').toMutableList().also {
    if (it.firstOrNull() == "0") it[0] = "1"
}.joinToString(".")

// iOS needs an Apple host to build at all (Xcode toolchain, and MobileVLCKit via
// CocoaPods for kobplayer). Declaring the targets off-Mac makes `gradle build` fail on
// :compileIosMainKotlinMetadata, so they are simply absent unless the host can build them.
val hostIsMac = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac

kotlin {
    // Suppress expect/actual class beta warning
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    android {
        namespace = "dev.kobbler.app"
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
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    // Android and desktop share a JVM: java.io for offline storage and a socket for its proxy.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withCompilations {
                    it.target.platformType in setOf(
                        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
                        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm,
                    )
                }
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(pinServiceSource)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.coil.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(project(":kobplayer"))
            implementation(project(":kobpalette"))
            implementation(project(":kobfocus"))
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.play.services.cast.framework)
            implementation(libs.mediarouter)
            // The route dialogs extend AppCompatDialog, which mediarouter does not expose.
            implementation(libs.appcompat)
        }

        getByName("desktopMain") {
            resources.srcDir(desktopVersionResource)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.java)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport.unixsocket)
            }
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }

        if (hostIsMac) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.ui.test)
        }

        getByName("desktopTest") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

val appResources = layout.buildDirectory.dir("appResources")
val bundleLibvlc = tasks.register<Sync>("bundleLibvlc") {
    from(project(":kobplayer").tasks.named("unpackLibvlc"))
    into(appResources.map { it.dir("linux-x64/libvlc") })
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundleLibvlc) }
tasks.withType<JavaExec>().matching { it.name.startsWith("hotRun") }.configureEach {
    dependsOn(bundleLibvlc)
    systemProperty("kobplayer.libvlc.dir", appResources.get().dir("linux-x64/libvlc/lib").asFile.path)
}
// Compose's desktop test runner wraps every test in a runTest that takes kotlinx-coroutines-test's
// default timeout rather than the one it is handed, and on a machine busy with another build a
// minute is not always enough for a test that loads a page twice.
tasks.named<Test>("desktopTest") {
    systemProperty("kotlinx.coroutines.test.default_timeout", "5m")
    // The app's offline store lives under XDG_DATA_HOME; a test must never write into the real one.
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    // Settings are java.util.prefs, ~/.java by default: tests would rewrite the real app's
    // settings and inherit whatever a previous run left behind.
    val prefsRoot = layout.buildDirectory.dir("test-prefs").get().asFile
    systemProperty("java.util.prefs.userRoot", prefsRoot.absolutePath)
    doFirst { prefsRoot.deleteRecursively() }
}

// Packaged launcher only: $APPDIR means nothing to `run`/`hotRun`. install-desktop.sh trains
// this AOT cache (preloaded, linked classes plus method profiles); the jlinked runtime has no
// base CDS archive, so without the cache the app starts with no class-data sharing at all.
// A missing or stale cache only costs that speed-up. install-desktop.sh rewrites this exact
// line for its training runs.
// afterEvaluate because the Compose plugin `set`s launcherJvmArgs from its own afterEvaluate.
afterEvaluate {
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
        launcherJvmArgs.add("-XX:AOTCache=\$APPDIR/kobapp.aot")
    }
}

compose.desktop {
    application {
        mainClass = "dev.kobbler.app.MainKt"
        jvmArgs += listOf(
            // ~10-20% smaller heap for an object-heavy UI; production in JDK 25.
            "-XX:+UseCompactObjectHeaders",
            // skiko, JNA (vlcj) and dbus-java load native code; JDK 24+ warns and will block.
            "--enable-native-access=ALL-UNNAMED",
        )

        buildTypes.release.proguard {
            // 7.7.0 (the plugin default) cannot read Kotlin's JVM 25 bytecode.
            version.set("7.10.0")
            configurationFiles.from(project.file("proguard-desktop.pro"))
        }

        nativeDistributions {
            appResourcesRootDir.set(appResources)
            packageName = "kobapp"
            packageVersion = pkgVersion
            description = "Kobbler — self-hosted media library client"
            vendor = "Kobbler"

            targetFormats(
                TargetFormat.AppImage,
                TargetFormat.Deb,
                TargetFormat.Rpm,
                TargetFormat.Msi,
                TargetFormat.Dmg,
            )

            modules(
                "java.instrument",
                "java.xml",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "jdk.crypto.ec",
                "jdk.net",
                "jdk.security.auth",
                "jdk.unsupported",
            )

            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
                debPackageVersion = pkgVersion
                rpmPackageVersion = pkgVersion
                appCategory = "AudioVideo"
                debMaintainer = "kobbler@localhost"
                menuGroup = "Kobbler"
            }
            macOS {
                bundleID = "dev.kobbler.app"
                packageVersion = macPkgVersion
                dmgPackageVersion = macPkgVersion
                pkgPackageVersion = macPkgVersion
            }
            windows {
                menuGroup = "Kobbler"
                upgradeUuid = "8a1f8b3e-2c4d-4f9b-9e7a-1b2c3d4e5f60"
            }
        }
    }
}

// The Linux download on a GitHub release: the release distributable beside install-desktop.sh,
// which installs a `kobapp/` it finds next to itself instead of building one. x86_64 only, as the
// bundled libvlc is.
tasks.register<Tar>("packageLinuxTarball") {
    dependsOn("createReleaseDistributable")
    val name = "kobbler-$pkgVersion-linux-x86_64"
    archiveFileName.set("$name.tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("github"))
    into(name) {
        from(rootProject.file("install-desktop.sh"))
        into("kobapp") { from(layout.buildDirectory.dir("compose/binaries/main-release/app/kobapp")) }
    }
    // Archives normalise every mode to 644; the launcher, the jlinked JVM and the installer
    // must stay executable.
    eachFile { if (file.canExecute()) permissions { unix("rwxr-xr-x") } }
}
