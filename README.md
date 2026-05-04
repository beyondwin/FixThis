# PointPatch for Android Compose

PointPatch lets you point at Jetpack Compose UI in a debug Android app, describe what should change, and export runtime context that an AI coding agent can use.

PointPatch V1 is intentionally narrow:

- Android Jetpack Compose only.
- Debug builds only.
- No required testTags.
- No AccessibilityService.
- MCP optional.
- Source candidates are best-effort.
- Screenshots may contain sensitive information.

## What It Does

PointPatch adds a debug-only sidekick to your Compose app. In the sample flow, you open the app, tap the PointPatch button, select UI with Smart Select, enter a short request, and copy Markdown or JSON for an AI coding agent.

Smart Select starts with tap selection, offers scope chips for nearby semantic targets, and falls back to area selection for visual regions such as spacing, Canvas-only UI, or places where no Compose semantics node exists.

The exported annotation includes the tap point, selection kind, selected node when available, nearby nodes, source candidates when the Gradle source index can match them, screenshot metadata, the user comment, and non-fatal errors.

## Setup

Use the Gradle plugin or add the sidekick as a debug dependency. Release builds are not a supported target.

```kotlin
plugins {
    id("io.github.pointpatch.compose")
}
```

or:

```kotlin
dependencies {
    debugImplementation("io.github.pointpatch:pointpatch-compose-sidekick:0.1.0")
}
```

The sample app in this repository is a Compose-only validation app. It replaces the earlier XML/View sample so PointPatch can exercise Compose semantics, Smart Select, screenshots, source candidates, and CLI/MCP bridge flows.

## CLI And MCP

The CLI can run diagnostics, launch the debug sample, and print MCP setup JSON:

```bash
pointpatch doctor
pointpatch run
pointpatch setup
```

MCP is an optional desktop integration. `pointpatch mcp` runs as a stdio JSON-RPC server and connects to the debug app through ADB and the sidekick local bridge. The in-app copy/share workflow works without MCP.

## Privacy Notes

PointPatch is local-first. The core sidekick does not require network permission, and the bridge is intended for the local development machine through ADB. Editable/password text is redacted by default, but screenshots are pixel captures and may contain sensitive information. Review screenshots before sharing exported artifacts.

More detail:

- [Output schema](docs/output-schema.md)
- [Privacy](docs/privacy.md)
- [Troubleshooting](docs/troubleshooting.md)
- [MCP](docs/mcp.md)
