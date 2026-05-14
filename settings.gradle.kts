pluginManagement {
    val overrideAgpVersion = providers.gradleProperty("overrideAgpVersion")
    val overrideKotlinVersion = providers.gradleProperty("overrideKotlinVersion")

    includeBuild("fixthis-gradle-plugin") {
        name = "fixthis-gradle-plugin"
    }

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application",
                "com.android.library",
                -> overrideAgpVersion.orNull?.let { useVersion(it) }
                "org.jetbrains.kotlin.android",
                "org.jetbrains.kotlin.jvm",
                "org.jetbrains.kotlin.plugin.compose",
                "org.jetbrains.kotlin.plugin.serialization",
                -> overrideKotlinVersion.orNull?.let { useVersion(it) }
            }
        }
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
    defaultLibrariesExtensionName = "defaultLibs"
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
            providers.gradleProperty("overrideAgpVersion").orNull?.let { version("agp", it) }
            providers.gradleProperty("overrideKotlinVersion").orNull?.let { version("kotlin", it) }
            providers.gradleProperty("overrideComposeBomVersion").orNull?.let { version("composeBom", it) }
            providers.gradleProperty("overrideComposeUiTestVersion").orNull?.let { version("composeUiTest", it) }
        }
    }
}

rootProject.name = "FixThis"

include(":app")
project(":app").projectDir = file("sample")
include(":fixthis-compose-core")
include(":fixthis-compose-sidekick")
include(":fixthis-cli")
include(":fixthis-mcp")
