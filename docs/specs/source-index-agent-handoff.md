# Spec — Source Index Agent-Handoff Enrichment

Status: Proposed
Plan: [`../plans/source-index-agent-handoff.md`](../plans/source-index-agent-handoff.md)

## Problem

The FixThis MCP handoff to coding agents (Claude Code, Codex, Cursor) carries
`SourceCandidate`s derived from a build-time source index. Two gaps degrade
the prompt quality the agent ultimately receives:

1. **String resources are not resolved.** The Kotlin scanner records
   `stringResources: ["login_button"]` for a `stringResource(R.string.login_button)`
   call, but the actual user-visible string `"Log in"` is recorded only against
   the `strings.xml` entry. When the runtime semantics tree reports the
   selected element's text as `"Log in"`, the matcher cannot connect that to
   `LoginScreen.kt:42` — only to `strings.xml`. Real-world Android apps use
   `stringResource(...)` for virtually all UI text (i18n), so this is the
   dominant text path, not an edge case.
2. **The enclosing Composable function is not surfaced.** The scanner emits
   `COMPOSABLE_SYMBOL` for each `@Composable fun` *declaration*, but
   does not record which Composable *encloses* a given `Text`/`testTag` call.
   The handoff therefore cannot tell the agent *"selected element is inside
   `HomeScreen` at `HomeScreen.kt:42`"*, even though that anchor is the most
   stable thing for an agent to grep for (file/line numbers drift after
   edits; function names rarely do).

The internal scoring tweaks (call-depth, tie-breakers, weight multipliers)
considered as part of broader source-matching work do not change the bytes
the agent receives — they only re-rank candidates the agent already saw. The
two items above are the only ones that materially change the handoff
payload.

## Goals

- The matcher locates the correct Kotlin call site for any UI element whose
  text comes from `stringResource(R.string.X)` when the runtime text matches
  the default-locale value of `X`.
- Every Kotlin source-index entry tied to a UI call carries the name of the
  enclosing `@Composable fun`; entries outside any Composable carry no owner.
- `SourceCandidate` exposes the owner function name through a stable field,
  and the feedback handoff Markdown includes it in source-candidate lines.

This is a pre-1.0 project, so **backwards compatibility with older index
assets, persisted sessions, or older matcher outputs is not a goal**. Tests
should pin the *current* shape, not assert that prior shapes still work.

## Non-Goals

- Multi-locale resolution. Only the default locale (`res/values/strings.xml`)
  is resolved. Localized variants (`values-en/`, `values-ko/`, ...) remain
  out of scope; they are not load-bearing for matching.
- Call-graph depth tracking, call-site stable hashes, or anything that
  requires the Compose compiler plugin or KSP. Those belong to a later
  hardening pass (the "B/C" levels of the broader source-matching plan).
- Modifier chain analysis beyond what the scanner already does.
- Changes to `BridgeProtocol.VERSION`. The bridge protocol is the
  console↔server contract; this work only changes the *build-time asset
  schema* and the *MCP handoff serialization*, neither of which is the
  bridge protocol.

## Wire-Format Changes

### `SourceIndex` asset (gradle plugin → runtime JSON)

- `schemaVersion`: bump `1.1` → `1.2`. The field is informational only; pre-1.0
  release means we do not maintain backwards compatibility with prior assets.
- `SourceSignalKind` enum gains two values (additive):
  - `STRING_RESOURCE_RESOLVED` — `value` is the resolved default-locale
    string. Emitted on the Kotlin call line, *not* on the XML entry.
  - `LAMBDA_OWNER_FUNCTION` — `value` is the simple name of the enclosing
    `@Composable fun`. Emitted on entries inside such a function.
- `SourceIndexEntry` gains no new top-level fields. Both new pieces of data
  ride on the existing `signals: List<SourceSignal>` array.

### `SourceCandidate` model (`fixthis-compose-core`, agent-visible)

- New optional field: `ownerComposable: String? = null`.
- Populated from the matched entry's `LAMBDA_OWNER_FUNCTION` signal while
  `SourceMatcher` builds the `SourceCandidate`.
- Serialized in MCP tool responses and preserved through the domain
  `SourceHint` round-trip used by persisted sessions.
- Included in human-readable feedback handoff Markdown wherever source
  candidates are rendered.

### `SourceMatchReason` enum

- New value: `SELECTED_RESOLVED_STRING_RESOURCE` — fires when the selected
  element's text matches a `STRING_RESOURCE_RESOLVED` signal.
- `SELECTED_STRING_RESOURCE` (currently scored 0.0) remains because the
  resId itself is still a useful matched-term in handoff output. No
  legacy-session constraint motivates its preservation.

## Scoring Policy

`SourceScoringPolicy.bucketScore` gains:

```
SELECTED_RESOLVED_STRING_RESOURCE -> 48.0
```

Rationale: a resolved `stringResource` match points to the Kotlin call site
that rendered the selected UI text. It should therefore beat the XML
`strings.xml` `UI_TEXT` entry for the same visible text (`SELECTED_TEXT`,
45.0), nearby context (24.0/22.0), and activity-only evidence (15.0). This
intentionally favors the call site an agent is likely to edit over the resource
definition that merely supplies copy.

`rankingTier` (the new function added by the current WIP in
`SourceScoringPolicy.kt`) gains a clause:

```
SELECTED_RESOLVED_STRING_RESOURCE in reasons -> selectedTextRankingTier
```

(Same tier as `SELECTED_TEXT` — they describe the same evidence class.)

## Handoff Text Format

Feedback handoff Markdown currently emits source candidates like:

```
Selected element:
  text: "Log in"
  source candidates:
    - LoginScreen.kt:42 (score 48.0, matched: stringResource: login_button)
```

After this work:

```
Selected element:
  text: "Log in"
  source candidates:
    - LoginScreen.kt:42 inside fun LoginScreen (score 48.0, matched: resolved string "Log in" → R.string.login_button)
```

The `inside fun X` segment is omitted when `ownerComposable` is null.

## Index Generation Pipeline Changes

Current pipeline (`SourceIndexGenerator.generate`):

```
kotlinFiles.forEach { kotlinScanner.scan(it) }    // emits Kotlin entries
xmlFiles.forEach    { xmlScanner.scan(it) }        // emits XML entries
```

After:

```
val resolver = buildResolver(xmlFiles)             // resId → defaultLocaleValue
kotlinFiles.forEach { kotlinScanner.scan(it, resolver) }   // emits Kotlin entries enriched with RESOLVED signals
xmlFiles.forEach    { xmlScanner.scan(it) }                // unchanged
```

The XML scanner's existing per-`<string>` entry behavior is preserved (it
serves a useful purpose: a designer who changes copy in `strings.xml` should
still be reachable from the matcher's `STRING_RESOURCE` path). The new
behavior is purely additive cross-linking.

## Build Cost

The Gradle task is already `@CacheableTask` with
`@PathSensitive(PathSensitivity.RELATIVE)` for both `kotlinSourceFiles` and
`resourceXmlFiles`. The new resolver step:

- Reads only `**/values/strings.xml` (default locale; locale variants
  ignored). For most apps this is 1–3 files.
- Builds an in-memory `Map<String, String>` of size ≤ a few thousand
  entries.
- Adds a single map lookup per `stringResource(...)` call already being
  scanned.

Expected wall-clock delta: ≤ ~50 ms for sample, ≤ ~300 ms for medium apps,
≤ ~800 ms for large apps. Re-runs with no input change are still no-ops
(Gradle UP-TO-DATE).

## Failure Modes and Edge Cases

- **Unknown resId.** `stringResource(R.string.foo)` where `foo` is not in
  the parsed `strings.xml` — emit the existing `STRING_RESOURCE` signal
  only; do not emit `STRING_RESOURCE_RESOLVED`. No error.
- **Conditional `stringResource`.** The V1 regex scanner resolves direct
  `stringResource(R.string.foo)` calls only. Expressions such as
  `stringResource(if (cond) R.string.a else R.string.b)` remain unresolved
  and do not emit resource-id signals for the conditional resource ids.
- **Formatted strings.** A resource value containing `%1$s` is emitted as
  the raw template (`"Hello, %1$s"`). Matching formatted runtime output such
  as `"Hello, Kim"` is not guaranteed in this pass.
- **Top-level Composables.** A `@Composable fun TopLevel()` body whose
  immediate call is not nested in another Composable — owner signal is
  *itself*. A `Text(...)` call outside any `@Composable fun` (rare) — no
  owner signal emitted.
- **Nested Composable lambdas.** `Column { Row { Text("…") } }` — the owner
  is the closest enclosing `@Composable fun`, *not* `Column` or `Row` (they
  are not declared functions in user code). This is the desired behavior:
  the agent's grep-anchor should be a user-defined function name.
- **Cross-module enum sync.** Gradle plugin emits the JSON; compose-core
  deserializes it. The two enums (`SourceSignalKindAsset` and
  `SourceSignalKind`) must be updated together so the strict default
  `kotlinx.serialization` decoder does not reject the new values. This is
  enforced by the plan's first task ordering, not by a runtime fallback.

## Out-of-Scope Risks (Acknowledged)

- The scoring tweak (48.0 for resolved) shifts the top-1 candidate for some
  existing screens where `STRING_RESOURCE` was previously beaten by
  `NEARBY_TEXT`. A golden-case audit is part of the plan (Task 5).
- The current `XmlStringResourceScanner` only parses `<string>` elements,
  not `<plurals>` or `<string-array>`. This work does not expand that
  surface; plurals/arrays remain unresolved. Acceptable: they account for
  <1% of UI text in typical apps.

## Acceptance Criteria

1. `./gradlew :fixthis-gradle-plugin:test :fixthis-compose-core:test :fixthis-mcp:test` passes.
2. `./gradlew check` passes on the sample app, with `fixthis-source-index.json`
   for the sample containing at least one `STRING_RESOURCE_RESOLVED` signal
   and at least one `LAMBDA_OWNER_FUNCTION` signal.
3. A new golden matcher case in `SourceMatcherTest` selects a Kotlin call
   site (not the `strings.xml` line) when the only available text on that
   call site is `stringResource(R.string.X)` and the runtime text equals
   the default-locale value.
4. A new matcher/model test asserts the resulting `SourceCandidate` carries a
   non-null `ownerComposable`, and domain mapper tests assert it survives the
   `SourceCandidate` ↔ `SourceHint` round-trip.
5. `BridgeProtocolVersionSyncTest` in `:fixthis-mcp:test` continues to pass
   (the bridge protocol is unchanged).
6. `docs/reference/output-schema.md`, `docs/reference/feedback-console-contract.md`,
   and the source-matching reference doc (created or updated) describe the two
   new signal kinds and the handoff field.
