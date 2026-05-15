# Consumer ProGuard rules for the FixThis Compose sidekick.
#
# These rules apply to downstream consumers that enable R8/minification on a
# release variant that pulls in this AAR. They protect symbols the sidekick
# reflects on at runtime as well as the public bridge surface referenced by
# protocol clients.
#
# Audit context: the sidekick currently reflects on Compose-internal test
# entry points (RootForTest, SemanticsOwner.getAllSemanticsNodes). The bridge
# protocol DTOs are accessed via kotlinx.serialization, which ships its own
# consumer rules — the rules below are defence-in-depth for the public API.

# --- Compose test entry points (reflected on by the sidekick) ---------------
# ComposeRootFinder.kt: `view is RootForTest`
# SemanticsInspector.kt: `root.rootForTest.semanticsOwner.getAllSemanticsNodes(...)`
-keep class androidx.compose.ui.node.RootForTest { *; }
-keep interface androidx.compose.ui.node.RootForTest { *; }
-keep class androidx.compose.ui.semantics.SemanticsOwner { *; }
-keep class androidx.compose.ui.semantics.SemanticsOwnerKt { *; }
-keep class androidx.compose.ui.platform.AndroidComposeView { *; }

# --- Public sidekick bridge surface -----------------------------------------
# The bridge protocol DTOs are serialized/deserialized over the wire. The
# kotlinx-serialization plugin already ships keep rules for `$serializer`
# companions, but we keep the public types themselves so reflective access
# from consumer integrations (and stack-trace class names) survives minify.
-keep class io.github.beyondwin.fixthis.compose.sidekick.bridge.** { *; }
-keep interface io.github.beyondwin.fixthis.compose.sidekick.bridge.** { *; }

# --- Sidekick entry point ---------------------------------------------------
# FixThisInitializer is referenced from the merged manifest's androidx.startup
# provider. Keep its no-arg constructor so reflection-based instantiation by
# androidx.startup continues to work when consumers minify their app.
-keep class io.github.beyondwin.fixthis.compose.sidekick.init.FixThisInitializer {
    public <init>();
}
