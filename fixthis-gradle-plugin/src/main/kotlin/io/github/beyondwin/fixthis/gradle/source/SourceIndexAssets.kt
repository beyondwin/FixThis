package io.github.beyondwin.fixthis.gradle.source

import kotlinx.serialization.Serializable

@Serializable
internal data class SourceIndexAsset(
    val schemaVersion: String = "1.2",
    val sourceRoot: SourceRootAsset? = null,
    val entries: List<SourceIndexEntryAsset> = emptyList(),
)

@Serializable
internal data class SourceRootAsset(
    val kind: String = "gradle-project",
    val gradlePath: String? = null,
    val projectDir: String? = null,
)

@Serializable
internal data class SourceIndexEntryAsset(
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val symbols: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val testTags: List<String> = emptyList(),
    val stringResources: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val excerpt: String? = null,
    val signals: List<SourceSignalAsset> = emptyList(),
    val packageName: String? = null,
    val className: String? = null,
)

@Serializable
internal data class SourceSignalAsset(
    val kind: SourceSignalKindAsset,
    val value: String,
    val confidenceWeight: Double = 1.0,
)

@Serializable
internal enum class SourceSignalKindAsset {
    COMPOSABLE_SYMBOL,
    UI_TEXT,
    STRING_RESOURCE,
    TEST_TAG,
    STRICT_COMP_TEST_TAG,
    CONTENT_DESCRIPTION,
    ROLE,
    ACTIVITY_NAME,
    ARBITRARY_STRING_LITERAL,
    STRING_RESOURCE_RESOLVED,
    LAMBDA_OWNER_FUNCTION,
    LAZY_ITEM_OWNER,
    NAV_DESTINATION_OWNER,
    MODIFIER_TARGET,
    LAYOUT_RENDERER,
    SHARED_COMPONENT,
    SHARED_COMPONENT_CALL_SITE,
}

@Serializable
internal data class FixThisBuildInfoAsset(
    val schemaVersion: String = "1.0",
    val projectPath: String,
    val variantName: String,
    val runtimeVersion: String,
    val sourceIndexAsset: String = "fixthis/fixthis-source-index.json",
    val buildInfoAsset: String = "fixthis/fixthis-build-info.json",
    val includeScreenshots: Boolean,
    val redactEditableText: Boolean,
)

internal data class SourceIndexEntryBuilder(
    val file: String,
    val repoFile: String? = null,
    val line: Int,
    val symbols: LinkedHashSet<String> = linkedSetOf(),
    val text: LinkedHashSet<String> = linkedSetOf(),
    val contentDescriptions: LinkedHashSet<String> = linkedSetOf(),
    val testTags: LinkedHashSet<String> = linkedSetOf(),
    val stringResources: LinkedHashSet<String> = linkedSetOf(),
    val roles: LinkedHashSet<String> = linkedSetOf(),
    val signals: LinkedHashSet<SourceSignalAsset> = linkedSetOf(),
    val excerpt: String,
    val packageName: String? = null,
    val className: String? = null,
) {
    fun addSignal(kind: SourceSignalKindAsset, value: String) {
        if (value.isNotBlank()) {
            signals += SourceSignalAsset(kind = kind, value = value)
        }
    }

    fun toAsset(): SourceIndexEntryAsset = SourceIndexEntryAsset(
        file = file,
        repoFile = repoFile,
        line = line,
        symbols = symbols.toList(),
        text = text.toList(),
        contentDescriptions = contentDescriptions.toList(),
        testTags = testTags.toList(),
        stringResources = stringResources.toList(),
        roles = roles.toList(),
        signals = signals.toList(),
        excerpt = excerpt,
        packageName = packageName,
        className = className,
    )
}
