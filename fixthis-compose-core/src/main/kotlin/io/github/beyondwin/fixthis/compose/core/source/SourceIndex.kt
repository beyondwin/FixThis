package io.github.beyondwin.fixthis.compose.core.source

import kotlinx.serialization.Serializable

@Serializable
data class SourceIndex(
    val schemaVersion: String = "1.2",
    val sourceRoot: SourceRoot? = null,
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
enum class SourceSignalKind {
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
