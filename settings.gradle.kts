pluginManagement {
    includeBuild("fixthis-gradle-plugin") {
        name = "fixthis-gradle-plugin"
    }

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

rootProject.name = "FixThis"

include(":app")
project(":app").projectDir = file("sample")
include(":fixthis-compose-core")
include(":fixthis-compose-overlay")
include(":fixthis-compose-sidekick")
include(":fixthis-cli")
include(":fixthis-mcp")
