pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Official Linphone SDK Maven repository - the "org.linphone:linphone-sdk-android"
        // artifact used in app/build.gradle.kts is published only here, not on Maven Central.
        // Confirmed against the official linphone-android app's own settings.gradle.kts
        // (github.com/BelledonneCommunications/linphone-android).
        maven {
            name = "linphone.org maven repository"
            url = uri("https://download.linphone.org/maven_repository")
        }
    }
}

rootProject.name = "CatSoftFonAndroid"
include(":app")
