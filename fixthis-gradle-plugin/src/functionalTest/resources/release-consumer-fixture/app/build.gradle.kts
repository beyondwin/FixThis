plugins {
    id("com.android.application")
}

val fixthisVersion =
    providers.gradleProperty("fixthisVersion").orNull
        ?: error("Missing required Gradle property: fixthisVersion")

android {
    namespace = "com.example.fixthisfixture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fixthisfixture"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation("io.github.beyondwin:fixthis-compose-sidekick:$fixthisVersion")
}
