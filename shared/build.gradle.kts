plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.pluralwatch.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 30 // Wear OS 3 = API 30.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Plural.kt wrapper (the whole point of this module for now).
    api(libs.pluralkot)

    // Coroutines — Plural.kt exposes suspend functions; we re-export so callers don't need to import separately.
    api(libs.kotlinx-coroutines-core)
    implementation(libs.kotlinx-coroutines-android)

    // For our own DTOs / future hand-rolled client.
    implementation(libs.kotlinx-serialization-json)

    // Encrypted token storage. API-side abstraction lives here; both apps consume it.
    implementation(libs.androidx-security-crypto)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx-coroutines-test)
}
