# FixThis for Android Compose

FixThis lets you point at Jetpack Compose UI in a debug Android app, describe what should change, and export runtime context that an AI coding agent can use.

> Migration note: FixThis was previously named PointPatch. This repository uses
> the new `fixthis` CLI, `io.beyondwin.fixthis.*` packages, `fixthis_*` MCP
> tools, and `.fixthis/` local storage paths. Old PointPatch public contracts are
> not preserved in this breaking rename.

FixThis V1 is intentionally narrow:

- Android Jetpack Compose only.
- Debug builds only.
- No required testTags.
- No AccessibilityService.
- MCP feedback console is the primary agent workflow.
- Source candidates are best-effort.
- Screenshots may contain sensitive information.

## What It Does

FixThis adds a debug-only sidekick to your Compose app. In the sample flow, you open the app, tap the FixThis button, select UI with Smart Select, enter a short request, and copy Markdown or JSON for an AI coding agent.

Smart Select starts with tap selection, offers scope chips for nearby semantic targets, and falls back to area selection for visual regions such as spacing, Canvas-only UI, or places where no Compose semantics node exists.

The exported annotation includes the tap point, selection kind, selected node when available, nearby nodes, source candidates when the Gradle source index can match them, screenshot metadata, the user comment, and non-fatal errors.

## Setup

Use the Gradle plugin or add the sidekick as a debug dependency. Release builds are not a supported target.

In this repository, the plugin is available through the composite Gradle build and `pluginManagement` wiring in `settings.gradle.kts`, so the sample can apply it directly:

```kotlin
plugins {
    id("io.beyondwin.fixthis.compose")
}
```

External projects need either a published plugin coordinate, for example with a versioned plugin id once FixThis is published, or explicit composite-build/plugin-management wiring that points at this repository's Gradle plugin build.

The sidekick dependency coordinate below is a future/published artifact placeholder. Until artifacts are published, external projects must wire this repository explicitly with composite-build/project dependencies instead of relying on the coordinate:

```kotlin
dependencies {
    debugImplementation("io.beyondwin.fixthis:fixthis-compose-sidekick:0.1.0")
}
```

The sample app in this repository is the branded FixThis Studio Compose app, exposed as application id `io.beyondwin.fixthis.sample` with launcher label `FixThis`. Its five bottom navigation tabs, Home, Queue, Project, Review, and Diagnostics, use a compact Calm Product Studio UI while preserving deterministic FixThis coverage for Smart Select, screenshots, source candidates, form controls, dropdown/menu, dialog, Canvas, disabled controls, repeated cards, long text, weak-semantics fallbacks, and CLI/MCP bridge smoke flows.

## Repository Layout

The Android Studio sample app is exposed as Gradle project `:app`, while its files live under `sample/`:

```text
:app                         -> sample/
:fixthis-compose-core     -> pure Kotlin domain contracts, use cases, models, selection, formatters, source matching
:fixthis-compose-overlay  -> Compose overlay UI and public feedback console Studio shell
:fixthis-compose-sidekick -> debug runtime, inspection, screenshots, bridge
:fixthis-gradle-plugin    -> plugin and source-index generation
:fixthis-cli              -> desktop CLI
:fixthis-mcp              -> stdio MCP server and local feedback console
```

The public Compose feedback console entrypoint is `io.beyondwin.fixthis.compose.console.studio.FeedbackConsoleScreen` in `:fixthis-compose-overlay`. The existing CLI/MCP browser console still uses its HTML asset surface, loaded from packaged resources under `fixthis-mcp/src/main/resources/console`.

`gradle/gradle-daemon-jvm.properties` pins the Gradle daemon JVM toolchain to Java 21. Local Android SDK settings still belong in `local.properties`, which is ignored.

## Try The Sample App

Open this repository root in Android Studio and run the `app` configuration. The Gradle project is exposed as `:app` for Android Studio convention, while its source files live under `sample/`.

Equivalent Gradle commands:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Connected instrumentation tests require an unlocked interactive emulator or device. A physical device that reports `device` in `adb devices` can still fail Compose hierarchy discovery if it remains behind a secure lockscreen.

For the full FixThis smoke flow, build the CLI/MCP distribution and let the CLI install, launch, and verify the sidekick bridge:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.beyondwin.fixthis.sample
```

## CLI And MCP

The CLI can run diagnostics, launch the debug sample, print MCP setup JSON, and open the feedback console:

```bash
fixthis doctor
fixthis run
fixthis setup --package <applicationId>
fixthis console --package <applicationId>
```

If `--package` is omitted, `.fixthis/project.json` must already exist so the CLI can read the application id.

MCP is the primary agent workflow for the feedback console. `fixthis mcp` runs as a stdio JSON-RPC server and can open a local web console where you review a live Android screen preview, add feedback with a desktop keyboard, and let the agent read the queue. `fixthis console` opens the same console without requiring an MCP client.

The feedback console is a dark Studio workspace: persisted sessions on the left, live or frozen Android preview in the center, and a mode-aware Inspector on the right. It defaults to navigation. There is no Select/Navigate toggle: normal preview clicks navigate the app, while Add freezes the latest preview so you can mark feedback targets. Navigation remains debug-only and limited to one-step `back`, `tap`, and `swipe` actions. The compact device control selects the active ADB device for FixThis bridge requests, shows `No device`, `Connecting`, `Connected`, or `Unavailable`, and keeps unavailable, offline, or unauthorized devices visible but not selectable.

Top bar actions are short session-level controls: Refresh, Add, Save, Copy, Send, New, and Close. The device refresh control is the `Refresh devices` icon button, and `Clear selection` clears only FixThis's active device selection. Live preview interval options are Manual, 1s, 2s, and 5s; the default is 1s. Preview polling pauses while the browser tab is hidden, while the Add/frozen-preview flow is active, and when the selected device becomes unavailable.

Feedback console flow:

1. Select device.
2. Use the app normally from the console preview.
3. Click Add when ready to leave feedback on the current screen.
4. Select a UI target or drag a visual area and write a comment.
5. Click Add to Pending; numbered overlay markers and pending rows stay in sync.
6. Click Save once to store one evidence snapshot and all pending items.
7. Review the saved evidence group in the Inspector Draft view, including the persisted screenshot, numbered overlay, and comments.
8. Click Copy for compact Markdown or Send when ready to create a local handoff batch.

Add freezes the latest preview only; it does not save. You can add multiple pending feedback items to one frozen preview. Pending items support Focus and Delete before Save; deleting renumbers pending items so the pending list numbers and overlay numbers match. Save promotes the frozen preview once into one persisted evidence snapshot and connects all pending items to that same `screenId`. Later Add on the same visible app screen creates a new evidence snapshot after Save.

Saved evidence groups can be expanded to review the persisted screenshot, numbered overlay, and saved comments. Send creates a persisted local handoff batch that MCP tools can read. It does not call an external AI API. Agent-facing Markdown is compact and focused on the request, target evidence, and likely source; it intentionally omits internal IDs and repeated storage metadata. JSON remains complete for tools and preserves IDs, paths, and MCP contracts.

Feedback console sessions are resumable. FixThis saves feedback workspace metadata and screenshot artifacts under `.fixthis/feedback-sessions/`, so an MCP or console restart does not discard queued feedback.

## Local Artifacts

Legacy annotation screenshot pulls write desktop-readable files under `.fixthis/artifacts/`. Feedback console sessions write workspace metadata and session-owned screenshots under `.fixthis/feedback-sessions/`. These are local debug artifacts and are ignored by git. The current `.gitignore` ignores `.fixthis` as a whole; if your team wants to share `.fixthis/project.json` for package discovery, add an explicit unignore rule for that file.

## Privacy Notes

FixThis is local-first. The Android app sidekick is debug-only and intended for the local development machine through ADB. Feedback console artifacts are local `.fixthis/feedback-sessions/` data and can include comments, source hints, screenshots, session metadata, and handoff batches. Editable/password text is redacted by default, but screenshots are pixel captures and may contain sensitive information. Review screenshots before sharing exported artifacts.

More detail:

- [Project overview](docs/project-overview.md)
- [Product requirements](docs/fixthis_prd.md)
- [Technical design](docs/fixthis_technical_design.md)
- [Decisions](docs/fixthis_decisions.md)
- [Architecture decisions](docs/adr/README.md)
- [Output schema](docs/output-schema.md)
- [Privacy](docs/privacy.md)
- [Troubleshooting](docs/troubleshooting.md)
- [MCP](docs/mcp.md)
- [Feedback console UX status](docs/design-feedback-console-ux.md)
- [Feedback console redesign brief](docs/design-claude-redesign-brief.md)
- [Zero-setup agent configuration proposal](docs/design-zero-setup.md)
