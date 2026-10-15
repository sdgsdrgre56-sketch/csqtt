// DION Mayak: its own Gradle build, separate from Mayak (build with: ./gradlew -p dion :app:assembleRelease).
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
    }
}
rootProject.name = "DionMayak"
include(":app")
