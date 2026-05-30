plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    // Producer side. With `productFlavors`/`targetProjectPath` below this
    // module emits a baseline-profile artifact consumed by :wear.
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "me.pluralware.baselineprofile"
    compileSdk = 35

    defaultConfig {
        // Wear OS 3+ matches :wear's minSdk; the producer doesn't have to be
        // any lower since it only runs against the target app.
        minSdk = 30
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // The app whose start-up + scroll paths we're profiling.
    targetProjectPath = ":wear"
}

// Run the generator a single time. Profiles are stable enough that the default
// 3-iteration loop is overkill for an app this small; bump back up later if
// the resulting profile is noisy.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit)
}
