import com.android.build.api.variant.BuildConfigField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    // Consumer side of baseline profile generation. Together with the
    // `baselineProfile` dependency below, this plugin (a) wires the
    // :baselineprofile producer into our release variant and (b) auto-creates
    // helper variants like `nonMinifiedRelease` that the producer actually
    // runs against.
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "me.pluralware.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.pluralware"
        minSdk = 30 // Wear OS 3 (API 30) per project decision.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // BENCHMARK is off in production builds. The baselineprofile plugin
        // generates a `nonMinifiedRelease` variant that the profile producer
        // runs against; we flip BENCHMARK on for just that variant below so
        // the producer drives the real screens (MockPluralKitClient +
        // pre-seeded InMemoryTokenStore) without a paired phone.
        buildConfigField("boolean", "BENCHMARK", "false")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

androidComponents {
    // The producer (:baselineprofile) runs against `nonMinifiedRelease`, a
    // release-without-R8 variant the baselineprofile plugin auto-creates.
    // Flip BENCHMARK on there so MainActivity short-circuits to mock data and
    // the macrobenchmark can actually exercise the scroll paths.
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.buildConfigFields?.put(
            "BENCHMARK",
            BuildConfigField(
                "boolean",
                "true",
                "Enabled in the baseline-profile producer variant.",
            ),
        )
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Wear Compose.
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.tooling.preview)

    // Token handoff via Wearable Data Layer.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Complication data source + update requester (LONG_TEXT fronter complication).
    implementation(libs.androidx.wear.watchface.complications.data.source)

    // Tiles (fronter tile) — ProtoLayout layout + Material components.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.tiles.material)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    // SuspendToFutureAdapter: lets the TileService answer requests from a coroutine.
    implementation(libs.androidx.concurrent.futures.ktx)

    // Encrypted token storage.
    implementation(libs.androidx.security.crypto)

    // Installs the AOT-compiled baseline profile recorded by :baselineprofile.
    // Required for ART to apply the profile on first launch.
    implementation(libs.androidx.profileinstaller)

    // Producer hookup: tells the consumer plugin where the profile comes from.
    "baselineProfile"(project(":baselineprofile"))

    // (Tiles added when we get to that phase.)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
