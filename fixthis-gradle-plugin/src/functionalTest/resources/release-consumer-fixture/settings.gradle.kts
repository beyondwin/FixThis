pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val fixThisRootDir =
    providers.gradleProperty("fixthisRootDir").orNull
        ?: error("Missing required Gradle property: fixthisRootDir")

includeBuild(fixThisRootDir)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "fixthis-release-consumer-fixture"
include(":app")
