plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.github.beyondwin.fixthis.compose")
}

android {
    namespace = "io.github.beyondwin.fixthis.sample"
    compileSdk =
        libs.versions.androidCompileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "io.github.beyondwin.fixthis.sample"
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.androidTargetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(project(":fixthis-compose-core"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
