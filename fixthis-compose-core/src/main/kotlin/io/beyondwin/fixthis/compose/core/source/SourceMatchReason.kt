package io.beyondwin.fixthis.compose.core.source

internal enum class SourceMatchReason(val wireLabel: String) {
    SELECTED_TEXT("selected text"),
    SELECTED_CONTENT_DESCRIPTION("selected contentDescription"),
    SELECTED_TEST_TAG("selected testTag"),
    SELECTED_TEST_TAG_CONVENTION_COMPOSABLE("selected testTag convention composable"),
    SELECTED_ROLE("selected role"),
    SELECTED_STRING_RESOURCE("selected stringResource"),
    NEARBY_TEXT("nearby text"),
    NEARBY_CONTENT_DESCRIPTION("nearby contentDescription"),
    NEARBY_TEST_TAG("nearby testTag"),
    NEARBY_ROLE("nearby role"),
    ACTIVITY("activity"),
    ARBITRARY_LITERAL("arbitrary literal"),
    LEGACY_FALLBACK("legacy fallback"),
    ;

    companion object {
        fun fromWireLabel(label: String): SourceMatchReason = entries.first { it.wireLabel == label }
    }
}
