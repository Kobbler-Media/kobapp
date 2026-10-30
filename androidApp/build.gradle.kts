plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidx.baselineprofile)
}

fun readVersionProps(): Map<String, String> =
    file("${rootDir}/version.properties").readLines()
        .filter { it.contains("=") }
        .associate { val (k, v) = it.split("=", limit = 2); k.trim() to v.trim() }

val ver = readVersionProps()

val releaseStoreFile: String? = providers.gradleProperty("kobapp.storeFile").orNull

android {
    namespace = "dev.kobbler.app.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.kobbler.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = ver["versionCode"]!!.toInt()
        versionName = ver["versionName"]!!
        manifestPlaceholders["appLabel"] = "Kobbler"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("kobapp.storePassword").get()
                keyAlias = providers.gradleProperty("kobapp.keyAlias").get()
                keyPassword = providers.gradleProperty("kobapp.keyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dbg"
            manifestPlaceholders["appLabel"] = "Kobbler (Dbg)"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Left off deliberately: mediarouter and the Cast framework pull in themes and
            // drawables this app never names in code, and a stripped one fails at runtime,
            // not at build time. Resource shrinking buys size, not startup.
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Release code, installed over the debug build: same package and key, so it keeps
        // that install's server link and profile. Debug builds run without AOT and are
        // several times slower on the UI thread, which makes them useless for frame timing.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dbg"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isProfileable = true
            manifestPlaceholders["appLabel"] = "Kobbler (Perf)"
        }
    }

    // libvlc ships for all four ABIs and is most of the APK. kobup serves each device the
    // split for its ABI; the universal APK is the fallback for browser downloads and
    // clients that don't report their ABIs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
    // Reorders the release DEX by the startup profile; only works because R8 is on.
    dexLayoutOptimization = true
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":kobplayer"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.session)
    implementation(libs.work.runtime)
    implementation(libs.play.services.cast.framework)
    // Present transitively already, but the profile only installs on API 28-30 if the
    // installer is a direct dependency the app can initialise.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}

// Kobup publish — optional, and only wired up when a sibling kobup checkout is present.
// Needs .kobup.json in the repo root (copy .kobup.json.example) and KOBUP_TOKEN in .env.
// Usage: ./gradlew :androidApp:publishToKobup
val kobupPublishScript = file("${rootDir}/../kobup/gradle/publish-to-kobup.gradle.kts")
if (kobupPublishScript.exists()) apply(from = kobupPublishScript)
tasks.matching { it.name == "publishToKobup" }.configureEach {
    doFirst {
        check(releaseStoreFile != null) { "publishToKobup needs kobapp.storeFile (see README: Release signing)" }
    }
}
