# FixThis Sample App Design

Date: 2026-05-07
Status: approved design, pending implementation plan

## Purpose

Replace the current basic Compose validation sample with a more realistic
sample app called FixThis. The sample should look like a credible productivity
app while remaining a local, hardcoded validation target for PointPatch's
Compose inspection, Smart Select, screenshot, source matching, CLI, and MCP
flows.

This is a sample-app-only change. The PointPatch library, CLI, MCP server, and
Gradle plugin namespaces stay unchanged in this phase.

## Approved Direction

Use the workflow product sample approach.

FixThis Studio is a fictional UI feedback product. It helps teams review UI
feedback, triage issues, write fix requests, and inspect diagnostics. This
domain fits the product being demonstrated: users point at UI, describe what
should change, and hand precise context to an agent.

The app should feel like an operational tool, not a landing page, component
gallery, or marketing mockup.

## Naming

- App label: `FixThis`
- Sample concept name: `FixThis Studio`
- Android `namespace`: `io.beyondwin.fixthis.sample`
- Android `applicationId`: `io.beyondwin.fixthis.sample`
- Kotlin package: `io.beyondwin.fixthis.sample`
- Existing library package prefix retained: `io.github.pointpatch.*`
- Existing CLI and MCP product wording retained unless it specifically names
  the sample app package

README commands and examples that launch the sample app must use
`io.beyondwin.fixthis.sample`.

## Visual Tone

Use a light productivity/studio tone:

- neutral app background
- white or very lightly tinted surfaces
- compact cards with 8dp radius or the closest existing Material equivalent
- teal/green primary accent
- restrained severity/status colors for priority, warning, success, and blocked
  states
- dense but readable screen composition
- Material 3 components where they fit naturally

Avoid a marketing-site feel, oversized hero sections, decorative gradients,
generic sample-app labels, and one-note color palettes. The first screen should
immediately communicate a working product surface.

## Information Architecture

The app uses five bottom-navigation tabs:

1. Home
2. Queue
3. Project
4. Review
5. Diagnostics

The tabs replace the current developer-oriented validation tabs
(`Checkout`, `Feed`, `Form`, `Dialog`, `Canvas`, `Edge`). The validation cases
must remain present, but they should be embedded as normal product UI.

## Screens

### Home

Home is the product overview screen.

It should include:

- app/product header for FixThis Studio
- active project summary
- metric cards for open feedback, high priority, resolved this week, and queued
  agent drafts
- priority feedback cards with chips and icon actions
- recent activity feed with mixed row/card density
- quick actions that lead conceptually to review or triage workflows

PointPatch coverage:

- mixed semantic nodes
- repeated cards with nearby context
- icon buttons with useful content descriptions
- chips and status labels
- long-ish text that still fits professionally

### Queue

Queue is the triage list for captured feedback.

It should include:

- search field
- filter chips for severity, screen, and assignment
- repeated feedback cards with title, source screen, severity, assignee, and age
- nested actions for assign, save, more, and mark reviewed
- at least one disabled action with visible disabled state

PointPatch coverage:

- `LazyColumn` with repeated cards
- nested clickable rows and buttons
- repeated labels where surrounding context matters
- disabled controls
- icon button semantics

### Project

Project is the selected issue/project detail screen.

It should include:

- selected feedback title and state
- preview surface for the affected UI
- timeline of comments, captures, assignments, and review events
- long reproduction note and compact agent handoff note
- overflow menu
- confirmation dialog for closing the issue

PointPatch coverage:

- dialog/overlay selection
- overflow menu selection
- long text and nested layout
- preview area that can be selected visually
- nearby nodes for ambiguous cards and rows

### Review

Review is the fix-request composition screen.

It should include:

- request title field
- target screen field
- masked token/password-like field to preserve password redaction coverage
- severity dropdown
- checkbox for including screenshot context
- switch for sending to the agent queue; off means the request remains a draft
- validation/helper text
- primary action `Submit request`
- secondary action `Clear draft`

PointPatch coverage:

- text field semantics
- password/masked editable field
- checkbox
- switch
- dropdown
- validation/supporting text
- stable CTA for instrumentation and semantics inspector tests

### Diagnostics

Diagnostics is the product health and inspection screen.

It should include:

- Canvas-only sparkline region
- a labeled chart/timeline region with explicit semantics
- rows demonstrating long text, disabled controls, and nested click targets
- a weak-semantics visual preview block where area selection remains useful

PointPatch coverage:

- Canvas-only region
- Canvas with explicit semantics
- missing or weak semantics area
- edge-case selection behavior
- font-scale and text overflow resilience

## Components

Keep implementation units small enough to read and test independently.

Recommended sample app structure:

```text
sample/src/main/java/io/beyondwin/fixthis/sample/
  MainActivity.kt
  FixThisStudioApp.kt
  FixThisTheme.kt
  model/FixThisDemoData.kt
  components/
    FeedbackCard.kt
    MetricCard.kt
    SectionHeader.kt
    StatusChip.kt
  screens/
    HomeScreen.kt
    QueueScreen.kt
    ProjectScreen.kt
    ReviewScreen.kt
    DiagnosticsScreen.kt
```

Component boundaries:

- `FixThisStudioApp`: app shell, selected tab state, scaffold, navigation
- `FixThisTheme`: sample app color scheme and typography hooks
- `FixThisDemoData`: immutable hardcoded fake data and enums
- screen files: screen-specific layout only
- component files: reusable cards, chips, headers, and rows

Do not add a real repository, ViewModel, network layer, database, login flow, or
external API.

## Data Model

Use hardcoded immutable demo data. The data should be realistic enough to make
the UI meaningful but simple enough to keep the sample app deterministic.

Suggested model concepts:

- `FeedbackItem`
  - id
  - title
  - screenName
  - severity
  - state
  - assignee
  - ageLabel
  - summary
- `ProjectMetric`
  - label
  - value
  - trendLabel
  - status
- `ActivityEvent`
  - title
  - detail
  - timeLabel
- `DiagnosticSignal`
  - label
  - value
  - status

Use enums for severity and state so status colors and labels stay consistent.

## Data Flow

The app remains local and deterministic.

```text
FixThisDemoData
-> screen composables
-> shared components
-> Material/Compose UI
```

Only local UI state is needed:

- selected tab
- form field values on Review
- checkbox/switch/dropdown values
- dialog/menu open state on Project

Use `rememberSaveable` where the current sample already uses it for simple
interactive state.

## Error Handling And States

Because the sample has no backend, error handling is represented visually
rather than functionally.

Include these product-like states:

- high-priority severity
- blocked or disabled action
- validation/helper text in Review
- stale diagnostic signal
- waiting state for one diagnostic signal

Do not implement fake asynchronous loading or network failures.

## Accessibility And Semantics

The sample should intentionally include both strong and weak semantics.

Strong semantics:

- Material buttons, text fields, checkboxes, switches, chips, and menu items
- content descriptions for icon-only actions
- meaningful text near repeated actions
- labeled Canvas/timeline where explicitly useful

Weak semantics:

- at least one Canvas-only chart region
- one visual preview surface where area selection is the right fallback
- one nested clickable or ambiguous row/card target

Weak semantics are not defects in this sample. They exist to verify that
PointPatch can still capture useful context when UI is visual or ambiguous.

## Package Rename Scope

Implementation must update:

- `sample/build.gradle.kts`
  - `namespace`
  - `applicationId`
- `sample/src/main/AndroidManifest.xml`
  - app label to `FixThis`
- sample Kotlin source paths and package declarations
- sample androidTest source paths and package declarations
- README commands that name the sample package
- tests that assert visible sample app text
- source-matching or MCP test fixtures only where they are intended to track the
  sample app package

Implementation must not rename:

- root Gradle project `PointPatch`
- modules named `pointpatch-*`
- plugin id `io.github.pointpatch.compose`
- library packages under `io.github.pointpatch.*`
- CLI command names in this phase

## Testing

Update sample instrumentation tests to use stable FixThis anchors:

- `FixThis`
- `Home`
- `Queue`
- `Review`
- `Submit request`
- `Diagnostics`

Update the semantics inspector test to assert the stable `Submit request` CTA
on the new Review screen.

Minimum verification:

```bash
./gradlew :app:assembleDebug
```

Additional verification when an emulator or Android device is available:

```bash
./gradlew :app:connectedDebugAndroidTest
./gradlew test
```

If no emulator or Android device is available, report that connected
instrumentation tests were not run.

## Documentation

README should continue to explain that the repository contains a sample app
exposed as Gradle project `:app` under `sample/`.

Update the full smoke command:

```bash
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.beyondwin.fixthis.sample
```

Do not rewrite historical design documents unless an implementation task
specifically requires it. Historical specs can continue to mention the old
sample package as part of past design context.

## Acceptance Criteria

- The sample app installs as `io.beyondwin.fixthis.sample`.
- The launcher label is `FixThis`.
- The visible sample UI presents FixThis Studio as a believable product surface.
- The app has five bottom-navigation tabs: Home, Queue, Project, Review, and
  Diagnostics.
- Form, dialog/menu, Canvas, and edge semantics coverage remain present inside
  product screens.
- Sample tests are updated for new package and visible text anchors.
- README package examples use `io.beyondwin.fixthis.sample`.
- PointPatch library, CLI, MCP, and Gradle plugin namespaces remain unchanged.

## Implementation Notes

Work in two separable passes:

1. Rename and package wiring:
   - package paths
   - Gradle namespace/applicationId
   - manifest label
   - README package examples
   - test package declarations and fixtures
2. UI replacement:
   - app shell
   - fake data
   - shared components
   - five product screens
   - test anchor updates

Keeping these passes distinct will make package-related build failures easier
to distinguish from UI or test assertion failures.
