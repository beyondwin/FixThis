plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "fixthis-compose-core"

            pom {
                name.set("FixThis Compose Core")
                description.set("Pure Kotlin domain model and serialization support for FixThis Compose debug tooling.")
                url.set("https://github.com/beyondwin/FixThis")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/beyondwin/FixThis.git")
                    developerConnection.set("scm:git:https://github.com/beyondwin/FixThis.git")
                    url.set("https://github.com/beyondwin/FixThis")
                }
            }
        }
    }
}
