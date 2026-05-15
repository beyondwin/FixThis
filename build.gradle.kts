import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.gradle.plugin.publish) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.versions)
}

val fixthisGroup = providers.gradleProperty("FIXTHIS_GROUP").orElse("io.github.beyondwin")
val fixthisVersion = providers.gradleProperty("FIXTHIS_VERSION").orElse("0.2.2-SNAPSHOT")

// Filter unstable releases (alpha/beta/RC/snapshot/milestone) out of the
// `dependencyUpdates` report so we only see candidates we'd actually adopt.
tasks.named<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>(
    "dependencyUpdates",
) {
    rejectVersionIf {
        val candidate = candidate.version.lowercase()
        val unstableMarkers = listOf("alpha", "beta", "rc", "m", "snapshot", "dev", "pr")
        unstableMarkers.any { marker -> candidate.contains("-$marker") || candidate.endsWith("-$marker") }
    }
}

val ktlintVersion = libs.versions.ktlint.get()

fun requestedDetektTask(taskName: String): Boolean {
    val task = taskName.substringAfterLast(":")
    return task == "build" ||
        task == "check" ||
        task.startsWith("detekt", ignoreCase = true)
}

val requestedDetekt = gradle.startParameter.taskNames.any(::requestedDetektTask)

allprojects {
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("**/*.kt")
            // Exclude build outputs, generated sources, and on-disk git worktrees from
            // sibling executor runs (.worktrees/* and .claude/worktrees/* are gitignored
            // but the root project's Spotless target glob would otherwise walk them and
            // fail on stale formatting in those checkouts).
            targetExclude(
                "**/build/**",
                "**/generated/**",
                "**/.worktrees/**",
                "**/.claude/worktrees/**",
                "**/worktrees/**",
            )
            ktlint(ktlintVersion).editorConfigOverride(
                mapOf(
                    "ktlint_standard_no-wildcard-imports" to "enabled",
                    // existing tests use backtick-quoted method names with descriptive identifiers
                    "ktlint_standard_function-naming" to "disabled",
                    // codebase uses camelCase for private/internal `val` constants intentionally
                    "ktlint_standard_property-naming" to "disabled",
                    // not auto-fixable; codebase has long descriptive strings/URLs
                    "ktlint_standard_max-line-length" to "disabled",
                    // not auto-fixable; one inline block comment we keep as-is
                    "ktlint_standard_comment-wrapping" to "disabled",
                    // inline `/* name */` comments inside generic argument lists are kept
                    "ktlint_standard_type-argument-comment" to "disabled",
                ),
            )
        }
        kotlinGradle {
            target("**/*.gradle.kts")
            targetExclude(
                "**/build/**",
                "**/.worktrees/**",
                "**/.claude/worktrees/**",
                "**/worktrees/**",
            )
            ktlint(ktlintVersion)
        }
    }
}

subprojects {
    group = fixthisGroup.get()
    version = fixthisVersion.get()

    plugins.withId("maven-publish") {
        apply(plugin = "signing")

        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "CentralPortal"
                    url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                    credentials {
                        username =
                            providers
                                .gradleProperty("mavenCentralUsername")
                                .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                                .orNull
                        password =
                            providers
                                .gradleProperty("mavenCentralPassword")
                                .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                                .orNull
                    }
                }
            }

            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("FixThis ${artifactId.orEmpty()}")
                    description.set("Debug-only Jetpack Compose feedback sidekick for AI coding agents.")
                    url.set("https://github.com/beyondwin/FixThis")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("beyondwin")
                            name.set("BeyondWin")
                            url.set("https://github.com/beyondwin")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/beyondwin/FixThis.git")
                        developerConnection.set("scm:git:ssh://git@github.com/beyondwin/FixThis.git")
                        url.set("https://github.com/beyondwin/FixThis")
                    }
                }
            }
        }

        extensions.configure<SigningExtension> {
            val signingKey =
                providers
                    .gradleProperty("signingKey")
                    .orElse(providers.environmentVariable("SIGNING_KEY"))
            val signingPassword =
                providers
                    .gradleProperty("signingPassword")
                    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
            if (signingKey.isPresent && signingPassword.isPresent) {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }

    if (requestedDetekt) {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
            // Per-module baseline files share the same directory. Detekt does not
            // merge baselines across modules, so each subproject owns its own
            // file. config/detekt/baseline.xml is a placeholder that points to
            // the per-module files (see README at the top of that file).
            baseline = file("$rootDir/config/detekt/baseline-$name.xml")
            parallel = true
        }
    }
}
