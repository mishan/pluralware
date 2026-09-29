plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "me.pluralware.mobile"
    compileSdk = 36

    defaultConfig {
        // MUST match the watch app's applicationId. The Wearable Data Layer
        // scopes DataItems by package name — if these diverge, the watch never
        // sees what the phone writes.
        applicationId = "me.pluralware"
        minSdk = 26
        targetSdk = 36
        versionCode = rootProject.extra["mobileVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    // Release signing comes from PLURALWARE_KEYSTORE (an absolute path),
    // PLURALWARE_KEYSTORE_PASSWORD and PLURALWARE_KEY_ALIAS. Both apps
    // must use the same key, or the Data Layer won't connect them. Without it,
    // release builds come out unsigned instead of failing.
    val keystore = providers.environmentVariable("PLURALWARE_KEYSTORE").orNull
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("PLURALWARE_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("PLURALWARE_KEY_ALIAS").get()
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // For sending the token to the watch.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.security.crypto)

    // Receiving friends' switch notifications. Same `tink` exclusion as :shared.
    implementation(libs.unifiedpush.connector) {
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    implementation(libs.tink.android)

    // Drawing invite links as QR codes.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
}
