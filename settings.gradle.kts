pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Plural.kt is hosted here, not on Maven Central.
        maven("https://maven.kotlindiscord.com/repository/maven-public/")
    }
}

rootProject.name = "PluralWatch"

include(":shared")
include(":wear")
include(":mobile")
