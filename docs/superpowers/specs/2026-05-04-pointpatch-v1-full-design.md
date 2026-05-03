# PointPatch V1 Full Design Supplement

Date: 2026-05-04
Status: approved direction, pending implementation plan

## Purpose

This document supplements:

- `docs/pointpatch_prd.md`
- `docs/pointpatch_technical_design.md`
- `docs/pointpatch_decisions.md`

The product direction remains:

```text
Point at Android Compose UI.
Describe what should change.
Send precise runtime context to an AI coding agent.
```

V1 Full includes the in-app annotation workflow, Compose runtime inspection,
screenshot/export, Gradle source indexing, CLI setup/run/doctor, and MCP
integration.

## Scope Decision

V1 Full should include MCP, but implementation must be milestone-based. MCP is
part of the final V1 acceptance criteria, not the first subsystem to build.

Recommended milestone order:

1. Compose sample app and core annotation model
2. Sidekick autoinit and overlay attach
3. Semantics inspection and Smart Select
4. Screenshot capture and local export
5. Gradle plugin and source index
6. CLI bridge, run, setup, and doctor
7. MCP server and macro tools
8. Documentation, compatibility matrix, and release readiness

This keeps the V1 product promise intact while making every stage independently
testable.

## Sample App Requirement

The current `sample/` project is an XML/ViewBinding/AppCompat starter app. It
must be replaced with a Jetpack Compose test app and used as the primary
manual and automated validation target.

The sample app should include:

- `CheckoutScreen`: amount text, coupon row, pay button, bottom bar
- `FeedScreen`: `LazyColumn`, repeated cards, icon buttons
- `FormScreen`: text field, password field, checkbox, switch
- `DialogScreen`: alert dialog, dropdown menu, popup
- `CanvasScreen`: Canvas-only region and Canvas with explicit semantics
- `EdgeCasesScreen`: nested clickable rows, disabled controls, long text,
  font-scale stress cases, RTL-friendly layout

The sample app must intentionally include both strong and weak semantics so the
tool's behavior is visible:

- Material/Compose components with useful semantics
- custom UI with missing semantics
- repeated UI where nearby context matters
- editable and password fields for redaction

## Selection UX: Smart Select

The selection UI should not be only tap or only drag. V1 should use Smart
Select:

```text
Tap Select -> Scope Chips -> Area Select fallback
```

### Primary: Tap Select

The default interaction is a single tap.

Flow:

```text
PointPatch button
-> Select UI
-> selection layer appears
-> user taps target
-> best semantics candidate is highlighted
-> comment sheet opens
```

Tap is the default because Compose semantics are node-based. Buttons, text,
cards, list items, rows, switches, and text fields should be fast to select
without asking the user to draw a box.

### Refinement: Scope Chips

After a tap, PointPatch should show the selected node and a small set of
alternate scope chips. These chips let the user move between smaller and larger
targets at the same coordinate.

Example:

```text
Selected: Button "Pay now"

[Text] [Button] [BottomBar] [Screen]
```

The selected chip changes the highlight immediately. This is better than
requiring drag for common cases like:

- select the text inside a button
- select the whole button
- select the card containing the button
- select the list item containing the card
- select the bottom bar containing the pay button

Candidate labels should be human-readable, not raw semantics dumps.

Good labels:

```text
Button - "Pay now" - 984x136
Text - "Pay now" - 132x32
Card - near "Monthly plan" - 984x220
Row - near "Total" - 984x180
```

Debug details can be hidden behind an expandable section.

### Fallback: Area Select

Area Select is used only when node selection is not enough.

Use cases:

- spacing or padding feedback
- Canvas-only UI
- image or visual-only region
- background color or surface area feedback
- no semantics node found at the tap point
- all candidate scopes are wrong

Interaction:

```text
Long press or Area Select button
-> drag a rectangle
-> visual area is highlighted
-> annotation uses area bounds, screenshot crop, nearby nodes, and tap point
```

Area Select should not replace Tap Select. It is a visual fallback.

### Selection Failure

If no semantics node is found, PointPatch must still create an annotation.

Output should include:

- tap point
- selected visual area, if any
- screenshot full/crop, if available
- nearby meaningful nodes, if available
- error code such as `NO_NODE_AT_TAP`
- user comment

The user-facing message should be short:

```text
No UI node found here. You can still describe the change.
```

## Comment Sheet UX

The comment sheet should be optimized for fast capture.

Default layout:

```text
Selected
Button "Pay now"
CheckoutScreen - bottom area

Scope
[Text] [Button] [BottomBar] [Screen]

Request
[text field]

Preview
[screenshot crop]

[Copy for AI] [Copy JSON] [Share]
```

When an MCP request is waiting, the primary action becomes:

```text
Send to AI Agent
```

Clipboard export and MCP return should use the same annotation model. MCP should
not require a separate selection UX.

## Output Contract Improvements

The annotation schema should distinguish required and optional fields.

Required fields:

- `schemaVersion`
- `id`
- `createdAtEpochMillis`
- `platform`
- `app.packageName`
- `activity.className`
- `tap`
- `selection.kind`
- `userComment`
- `errors`

Optional fields:

- `selectedNode`
- `selection.areaBoundsInWindow`
- `candidatesAtPoint`
- `scopeCandidates`
- `nearbyNodes`
- `sourceCandidates`
- `searchHints`
- `screenshot`

Add a `selection` object:

```json
{
  "kind": "SEMANTICS_NODE",
  "confidence": "HIGH",
  "selectedUid": "0:MERGED:142",
  "areaBoundsInWindow": null,
  "source": "TAP_SELECT"
}
```

Allowed values:

- `kind`: `SEMANTICS_NODE`, `VISUAL_AREA`, `TAP_POINT`
- `confidence`: `HIGH`, `MEDIUM`, `LOW`, `NONE`
- `source`: `TAP_SELECT`, `SCOPE_CHIP`, `AREA_SELECT`, `FALLBACK`

This lets AI agents understand whether the selection is a strong Compose node
or a weaker visual fallback.

## Screenshot and Desktop Access

Android screenshot paths in `cacheDir` are not directly readable by desktop AI
agents. V1 must define how screenshots move across the bridge.

Required behavior:

- app stores screenshots locally in cache
- bridge exposes screenshots as binary resources or base64 metadata
- CLI/MCP can pull screenshots to `.pointpatch/artifacts/`
- Markdown export should prefer desktop-readable paths when generated through
  CLI/MCP
- app-only clipboard export can include Android-local paths and a warning

Suggested desktop path:

```text
.pointpatch/artifacts/<annotation-id>/<annotation-id>-full.png
.pointpatch/artifacts/<annotation-id>/<annotation-id>-crop.png
```

## MCP and Bridge Contract

V1 MCP tools:

- `pointpatch_status`
- `pointpatch_get_current_screen`
- `pointpatch_get_ui_feedback`
- `pointpatch_verify_ui_change`

The MCP server should use stdio. JSON-RPC messages are newline-delimited on
stdout. Logs must go to stderr only.

The Android sidekick bridge can use length-prefixed JSON over
`LocalServerSocket`, but the desktop MCP boundary must remain standard MCP
stdio.

Bridge handshake must include:

- sidekick version
- bridge protocol version
- package name
- debuggable flag
- process start time
- token validation
- source index availability

Failure cases to handle:

- `ADB_NOT_FOUND`
- `NO_DEVICE`
- `MULTIPLE_DEVICES`
- `RUN_AS_FAILED`
- `PACKAGE_NOT_DEBUGGABLE`
- `PACKAGE_NOT_RUNNING`
- `SIDEKICK_SESSION_NOT_FOUND`
- `BRIDGE_TOKEN_MISMATCH`
- `PROTOCOL_VERSION_MISMATCH`
- `MCP_TIMEOUT`

## Compatibility Matrix

The technical design should replace vague compatibility wording with an explicit
initial matrix before implementation starts.

Recommended initial support target:

- minSdk: 23 or 24, matching sample and library choice
- targetSdk/compileSdk: current project default
- Kotlin: one pinned version
- AGP: one pinned version first, then matrix
- Compose BOM: one pinned version first, then matrix
- Java toolchain: 17 unless AGP choice requires otherwise

The first implementation should compile one known-good stack before expanding
the matrix.

## Source Index Expectations

The source indexer is best-effort. V1 should not imply exact file/line mapping.

Schema should include match explanation:

```json
{
  "file": "app/src/main/java/example/CheckoutScreen.kt",
  "line": 42,
  "score": 1850,
  "matchedTerms": ["Pay now", "Total"],
  "matchReasons": ["selected_text_exact", "nearby_text_exact"],
  "confidence": "MEDIUM"
}
```

The matcher should prefer useful ranked candidates over false precision.

## Release Safety

Release safety should be tested, not only described.

Acceptance criteria:

- Gradle plugin adds runtime only to debuggable variants
- direct dependency docs show `debugImplementation` only
- initializer exits when `FLAG_DEBUGGABLE` is false
- bridge server refuses to start when not debuggable
- release sample build has no visible overlay
- release sample does not write sidekick session files

## V1 Full Acceptance Criteria

V1 Full is done when:

- Compose sample app covers all required test screens
- debug app shows PointPatch button
- idle overlay does not block normal app touches
- Tap Select chooses useful semantics nodes
- Scope Chips can change target scope
- Area Select can capture visual-only regions
- selected node failure still creates an annotation
- comment sheet exports Markdown and JSON
- screenshots are captured or failure is recorded without crashing
- editable/password redaction works
- source index is generated and packaged for debug
- source candidates appear with match reasons
- CLI can run, inspect, setup, and doctor the sample
- MCP exposes the four V1 macro tools
- `pointpatch_get_ui_feedback` opens app selection and returns annotation
- MCP/CLI can provide desktop-readable screenshot artifacts
- release build has no active PointPatch runtime
- docs clearly state Compose-only, debug-only, local-first, best-effort source
  mapping, and screenshot privacy limitations

## Notes for Implementation Planning

Do not start with MCP internals. Start by making the sample app and annotation
model real, then build the runtime capture loop. MCP should wrap a working
runtime workflow rather than define it.

The implementation plan should split ownership by module:

- sample app
- core model and formatter
- sidekick runtime and inspection
- overlay UI and Smart Select
- screenshot/export
- Gradle plugin/source index
- CLI/ADB bridge
- MCP server
- tests and docs
