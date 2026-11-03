plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "dev.kobbler.app.baselineprofile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Profile capture needs the AOT/profileable surface added in API 28, so this
        // module floors higher than the app's minSdk 24.
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The 1.5.0 producer plugin does not inject this under AGP 9. The synthetic
        // benchmark variants keep androidApp's applicationId (no suffix), so name it here.
        testInstrumentationRunnerArguments["targetAppId"] = "dev.kobbler.app"
    }

    // Mirrors androidApp's perf build type: release code over the paired .dbg install, so a
    // device that already runs the debug app profiles without being linked again.
    buildTypes {
        create("perf") {
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":androidApp"
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

baselineProfile {
    // No Gradle Managed Device: the repo's TV loop (tv-emu.sh) drives a hand-started
    // google-tv AVD, and a GMD image would profile a phone the app is not tuned for.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
