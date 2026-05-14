pluginManagement {
    val overrideKotlinVersion = providers.gradleProperty("overrideKotlinVersion")

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.jetbrains.kotlin.jvm",
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            providers.gradleProperty("overrideAgpVersion").orNull?.let { version("agp", it) }
            providers.gradleProperty("overrideKotlinVersion").orNull?.let { version("kotlin", it) }
        }
    }
}

rootProject.name = "fixthis-gradle-plugin-build"
