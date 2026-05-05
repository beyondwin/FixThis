# PointPatch for Android Compose

PointPatch lets you point at Jetpack Compose UI in a debug Android app, describe what should change, and export runtime context that an AI coding agent can use.

PointPatch V1 is intentionally narrow:

- Android Jetpack Compose only.
- Debug builds only.
- No required testTags.
- No AccessibilityService.
- MCP feedback console is the primary agent workflow.
- Source candidates are best-effort.
- Screenshots may contain sensitive information.

## What It Does

PointPatch adds a debug-only sidekick to your Compose app. In the sample flow, you open the app, tap the PointPatch button, select UI with Smart Select, enter a short request, and copy Markdown or JSON for an AI coding agent.

Smart Select starts with tap selection, offers scope chips for nearby semantic targets, and falls back to area selection for visual regions such as spacing, Canvas-only UI, or places where no Compose semantics node exists.

The exported annotation includes the tap point, selection kind, selected node when available, nearby nodes, source candidates when the Gradle source index can match them, screenshot metadata, the user comment, and non-fatal errors.

## Setup

Use the Gradle plugin or add the sidekick as a debug dependency. Release builds are not a supported target.

In this repository, the plugin is available through the composite Gradle build and `pluginManagement` wiring in `settings.gradle.kts`, so the sample can apply it directly:

```kotlin
plugins {
    id("io.github.pointpatch.compose")
}
```

External projects need either a published plugin coordinate, for example with a versioned plugin id once PointPatch is published, or explicit composite-build/plugin-management wiring that points at this repository's Gradle plugin build.

The sidekick dependency coordinate below is a future/published artifact placeholder. Until artifacts are published, external projects must wire this repository explicitly with composite-build/project dependencies instead of relying on the coordinate:

```kotlin
dependencies {
    debugImplementation("io.github.pointpatch:pointpatch-compose-sidekick:0.1.0")
}
```

The sample app in this repository is a Compose-only validation app. It replaces the earlier XML/View sample so PointPatch can exercise Compose semantics, Smart Select, screenshots, source candidates, and CLI/MCP bridge flows.

## Repository Layout

The Android Studio sample app is exposed as Gradle project `:app`, while its files live under `sample/`:

```text
:app                         -> sample/
:pointpatch-compose-core     -> pure Kotlin models, selection, formatters, source matching
:pointpatch-compose-overlay  -> Compose toolbar, selection layer, highlight layer, comment sheet
:pointpatch-compose-sidekick -> debug runtime, inspection, screenshots, bridge
:pointpatch-gradle-plugin    -> plugin and source-index generation
:pointpatch-cli              -> desktop CLI
:pointpatch-mcp              -> stdio MCP server
```

`gradle/gradle-daemon-jvm.properties` pins the Gradle daemon JVM toolchain to Java 21. Local Android SDK settings still belong in `local.properties`, which is ignored.

## Try The Sample App

Open this repository root in Android Studio and run the `app` configuration. The Gradle project is exposed as `:app` for Android Studio convention, while its source files live under `sample/`.

Equivalent Gradle commands:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

For the full PointPatch smoke flow, build the CLI/MCP distribution and let the CLI install, launch, and verify the sidekick bridge:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.github.pointpatch.sample
```

## CLI And MCP

The CLI can run diagnostics, launch the debug sample, print MCP setup JSON, and open the feedback console:

```bash
pointpatch doctor
pointpatch run
pointpatch setup --package <applicationId>
pointpatch console --package <applicationId>
```

If `--package` is omitted, `.pointpatch/project.json` must already exist so the CLI can read the application id.

MCP is the primary agent workflow for the feedback console. `pointpatch mcp` runs as a stdio JSON-RPC server and can open a local web console where you review Android screen snapshots, add feedback with a desktop keyboard, and let the agent read the queue. `pointpatch console` opens the same console without requiring an MCP client.

The feedback console has separate Select and Navigate modes. Select mode creates feedback targets from a component click or custom drag area; Navigate mode sends the existing debug-only one-step `back`, `tap`, and `swipe` actions to the app. The browser device picker selects the active ADB device for PointPatch bridge requests, and unavailable, offline, or unauthorized devices are shown as unavailable rather than selectable.

Send Draft to Agent persists the current draft as a local handoff batch that MCP tools can read. It does not call an external AI API. Sent history persists with the feedback session, and the draft is cleared after send. Agent-facing Markdown keeps the package, status, screen labels, target bounds, selected text/content descriptions, source candidates, comments, and screenshot availability/size, while avoiding raw session, screen, item, batch, and screenshot artifact IDs.

Feedback console sessions are resumable. PointPatch saves feedback workspace metadata and screenshot artifacts under `.pointpatch/feedback-sessions/`, so an MCP or console restart does not discard queued feedback.

## Local Artifacts

Legacy annotation screenshot pulls write desktop-readable files under `.pointpatch/artifacts/`. Feedback console sessions write workspace metadata and session-owned screenshots under `.pointpatch/feedback-sessions/`. These are local debug artifacts and are ignored by git. Keep `.pointpatch/project.json` trackable if you intentionally generate project metadata for package discovery.

## Privacy Notes

PointPatch is local-first. The core sidekick does not require network permission, and the bridge is intended for the local development machine through ADB. Editable/password text is redacted by default, but screenshots are pixel captures and may contain sensitive information. Review screenshots before sharing exported artifacts.

More detail:

- [Product requirements](docs/pointpatch_prd.md)
- [Technical design](docs/pointpatch_technical_design.md)
- [Decisions](docs/pointpatch_decisions.md)
- [Output schema](docs/output-schema.md)
- [Privacy](docs/privacy.md)
- [Troubleshooting](docs/troubleshooting.md)
- [MCP](docs/mcp.md)
