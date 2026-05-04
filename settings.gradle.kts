pluginManagement {
    includeBuild("pointpatch-gradle-plugin") {
        name = "pointpatch-gradle-plugin"
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

rootProject.name = "PointPatch"

include(":app")
project(":app").projectDir = file("sample")
include(":pointpatch-compose-core")
include(":pointpatch-compose-overlay")
include(":pointpatch-compose-sidekick")
include(":pointpatch-cli")
include(":pointpatch-mcp")
