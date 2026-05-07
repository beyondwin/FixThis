plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        create("fixThisCompose") {
            id = "io.beyondwin.fixthis.compose"
            implementationClass = "io.beyondwin.fixthis.gradle.FixThisGradlePlugin"
        }
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
    testImplementation(libs.kotlinx.serialization.json)
}
