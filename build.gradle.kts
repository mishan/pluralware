// Top-level build file. Plugins are declared here but applied in module-level files.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// One version for both apps, set by `pluralware.version` in gradle.properties and
// matched by the `vX.Y.Z` release tag. The watch's versionCode is one past the
// phone's: they share an applicationId, and Play rejects a code reused within an app.
val appVersion = providers.gradleProperty("pluralware.version").get()
val appVersionBase = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersion)
    ?.destructured
    ?.let { (major, minor, patch) ->
        require(minor.toInt() < 100 && patch.toInt() < 100) { "minor and patch must be under 100" }
        (major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()) * 10
    }
    ?: error("pluralware.version must be MAJOR.MINOR.PATCH, got $appVersion")
extra["appVersionName"] = appVersion
extra["mobileVersionCode"] = appVersionBase
extra["wearVersionCode"] = appVersionBase + 1
