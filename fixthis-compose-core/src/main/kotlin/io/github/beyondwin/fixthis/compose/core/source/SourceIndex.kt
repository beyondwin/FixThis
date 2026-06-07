package io.github.beyondwin.fixthis.compose.core.source

import kotlinx.serialization.Serializable

@Serializable
data class SourceIndex(
    val schemaVersion: String = "1.3",
    val sourceRoot: SourceRoot? = null,
    val testTagConventions: List<String> = emptyList(),
    val entries: List<SourceIndexEntry> = emptyList(),
)

@Serializable
data class SourceRoot(
    val kind: String = "gradle-project",
    val gradlePath: String? = null,
    val projectDir: String? = null,
)

@Serializable
data class SourceIndexEntry(
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
    val signals: List<SourceSignal> = emptyList(),
    val packageName: String? = null,
    val className: String? = null,
)

@Serializable
data class SourceSignal(
    val kind: SourceSignalKind,
    val value: String,
    val confidenceWeight: Double = 1.0,
)

internal object SourceSignalWeights {
    const val EXACT: Double = 1.0
    const val STRING_RESOURCE: Double = 0.85
    const val STRICT_COMP_TEST_TAG: Double = 1.15
    const val ROLE: Double = 0.85
    const val ACTIVITY_NAME: Double = 0.85
    const val ARBITRARY_STRING_LITERAL: Double = 0.35
    const val LAYOUT_RENDERER: Double = 0.75
    const val STRUCTURAL_MARKER: Double = 0.0
}

@Serializable
enum class SourceSignalKind(val baseMatchWeight: Double) {
    COMPOSABLE_SYMBOL(SourceSignalWeights.EXACT),
    UI_TEXT(SourceSignalWeights.EXACT),
    STRING_RESOURCE(SourceSignalWeights.STRING_RESOURCE),
    TEST_TAG(SourceSignalWeights.EXACT),
    STRICT_COMP_TEST_TAG(SourceSignalWeights.STRICT_COMP_TEST_TAG),
    CONTENT_DESCRIPTION(SourceSignalWeights.EXACT),
    ROLE(SourceSignalWeights.ROLE),
    ACTIVITY_NAME(SourceSignalWeights.ACTIVITY_NAME),
    ARBITRARY_STRING_LITERAL(SourceSignalWeights.ARBITRARY_STRING_LITERAL),
    STRING_RESOURCE_RESOLVED(SourceSignalWeights.EXACT),
    LAMBDA_OWNER_FUNCTION(SourceSignalWeights.EXACT),
    LAZY_ITEM_OWNER(SourceSignalWeights.EXACT),
    NAV_DESTINATION_OWNER(SourceSignalWeights.EXACT),
    MODIFIER_TARGET(SourceSignalWeights.EXACT),
    LAYOUT_RENDERER(SourceSignalWeights.LAYOUT_RENDERER),
    SHARED_COMPONENT(SourceSignalWeights.STRUCTURAL_MARKER),
    SHARED_COMPONENT_CALL_SITE(SourceSignalWeights.STRUCTURAL_MARKER),
}
