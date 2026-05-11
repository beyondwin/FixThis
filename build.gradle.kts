plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.gradle.plugin.publish) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
}

val ktlintVersion = libs.versions.ktlint.get()

allprojects {
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("**/*.kt")
            targetExclude("**/build/**", "**/generated/**")
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
            targetExclude("**/build/**")
            ktlint(ktlintVersion)
        }
    }
}

subprojects {
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
