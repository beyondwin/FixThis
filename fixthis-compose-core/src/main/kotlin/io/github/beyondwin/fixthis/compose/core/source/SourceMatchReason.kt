package io.github.beyondwin.fixthis.compose.core.source

internal enum class SourceMatchReason(val wireLabel: String) {
    SELECTED_TEXT("selected text"),
    SELECTED_CONTENT_DESCRIPTION("selected contentDescription"),
    SELECTED_STATE_DESCRIPTION("selected stateDescription"),
    SELECTED_TEST_TAG("selected testTag"),
    SELECTED_TEST_TAG_CONVENTION_COMPOSABLE("selected testTag convention composable"),
    SELECTED_OWNER_FUNCTION("selected owner composable"),
    SELECTED_ROLE("selected role"),
    SELECTED_STRING_RESOURCE("selected stringResource"),
    SELECTED_RESOLVED_STRING_RESOURCE("selected resolved stringResource"),
    NEARBY_TEXT("nearby text"),
    NEARBY_CONTENT_DESCRIPTION("nearby contentDescription"),
    NEARBY_TEST_TAG("nearby testTag"),
    NEARBY_ROLE("nearby role"),
    LAYOUT_RENDERER_CONTEXT("layout renderer context"),
    ACTIVITY("activity"),
    ARBITRARY_LITERAL("arbitrary literal"),
    UNTYPED_FALLBACK("legacy fallback"),
    ;

    companion object {
        fun fromWireLabel(label: String): SourceMatchReason = entries.first { it.wireLabel == label }
    }
}
