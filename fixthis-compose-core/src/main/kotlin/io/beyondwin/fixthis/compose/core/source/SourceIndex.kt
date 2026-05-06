package io.beyondwin.fixthis.compose.core.source

import kotlinx.serialization.Serializable

@Serializable
data class SourceIndex(
    val schemaVersion: String = "1.0",
    val entries: List<SourceIndexEntry> = emptyList()
)

@Serializable
data class SourceIndexEntry(
    val file: String,
    val line: Int? = null,
    val symbols: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val testTags: List<String> = emptyList(),
    val stringResources: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val excerpt: String? = null
)
