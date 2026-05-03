pluginManagement {
    includeBuild("pointpatch-gradle-plugin") {
        name = "pointpatch-gradle-plugin-build"
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

include(":sample")
include(":pointpatch-compose-core")
include(":pointpatch-compose-overlay")
include(":pointpatch-compose-sidekick")
include(":pointpatch-gradle-plugin")
include(":pointpatch-cli")
include(":pointpatch-mcp")
