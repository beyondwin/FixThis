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

@Serializable
enum class SourceSignalKind(val baseMatchWeight: Double) {
    COMPOSABLE_SYMBOL(1.0),
    UI_TEXT(1.0),
    STRING_RESOURCE(0.85),
    TEST_TAG(1.0),
    STRICT_COMP_TEST_TAG(1.15),
    CONTENT_DESCRIPTION(1.0),
    ROLE(0.85),
    ACTIVITY_NAME(0.85),
    ARBITRARY_STRING_LITERAL(0.35),
    STRING_RESOURCE_RESOLVED(1.0),
    LAMBDA_OWNER_FUNCTION(1.0),
    LAZY_ITEM_OWNER(1.0),
    NAV_DESTINATION_OWNER(1.0),
    MODIFIER_TARGET(1.0),
    LAYOUT_RENDERER(0.75),
    SHARED_COMPONENT(0.0),
    SHARED_COMPONENT_CALL_SITE(0.0),
}
