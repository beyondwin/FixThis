# Source Matching Reference

FixThis source matching starts from the build-time `fixthis-source-index.json`
asset. The Gradle task scans Kotlin sources and default-locale Android string
resources, then the runtime matcher ranks source-index entries against the
selected Compose semantics node and nearby context.

For multi-module Android apps, the Gradle plugin scans the application module
plus project dependencies that are present on the active debug variant's
compile/runtime classpath. Unrelated sibling modules in the same Gradle build
are not indexed, which keeps candidate ranking focused on code that can render
the installed APK.

## Source Index Schema

Current source-index `schemaVersion` is `1.2`. FixThis is pre-1.0, so this
version is informational and does not promise forward compatibility with older
assets.

Each entry preserves the legacy field lists (`symbols`, `text`,
`contentDescriptions`, `testTags`, `stringResources`, `roles`, and
`activityNames`) and also carries typed `signals`. Current signal kinds are:

- `COMPOSABLE_SYMBOL`: an `@Composable fun` declaration.
- `UI_TEXT`: literal UI text found in recognized Compose UI calls, including
  `Text("...")` and `Text(text = "...")`.
- `STRING_RESOURCE`: a resource id referenced as `R.string.<name>` or defined
  in XML.
- `STRING_RESOURCE_RESOLVED`: the default-locale string value for a direct
  `stringResource(R.string.<name>)` call. This signal is emitted on the Kotlin
  call-site entry, not on the XML resource entry.
- `TEST_TAG`: a Compose `testTag(...)`.
- `STRICT_COMP_TEST_TAG`: a strict test tag of the form `comp:<ComposableName>:...`,
  `screen:<ComposableName>:...`, or dot-delimited `comp.<ComposableName>.<id>`, resolving
  the named composable owner at the same medium confidence for all three forms.
- `CONTENT_DESCRIPTION`: a Compose semantics content description, including
  direct `contentDescription = stringResource(...)` calls and local variables
  assigned from `stringResource(...)` with or without an explicit Kotlin type.
- `ROLE`: a Compose semantics role such as `Role.Button`.
- `ACTIVITY_NAME`: an activity-name signal.
- `ARBITRARY_STRING_LITERAL`: a Kotlin string literal not recognized as a
  stronger UI signal.
- `LAMBDA_OWNER_FUNCTION`: the simple name of the enclosing `@Composable fun`
  for indexed entries inside a composable body.
- `LAYOUT_RENDERER`: a `Layout(...)`, `SubcomposeLayout(...)`, or
  `SubcomposeLayout { ... }` renderer call inside a composable owner, or a
  content-slot wrapper composable — a `@Composable fun` that exposes a
  `content: @Composable (...) -> Unit` slot parameter — which emits the signal
  carrying the wrapper's own function name. This is used as call-site evidence;
  it does not by itself make a source candidate high confidence.

For layout renderer signals, direct unqualified calls are indexed only when
`Layout`, `SubcomposeLayout`, or a wildcard import comes from
`androidx.compose.ui.layout`. Qualified `androidx.compose.ui.layout.Layout`
and `androidx.compose.ui.layout.SubcomposeLayout` calls are also recognized.
Comments, strings, declarations, nested block comments, `Layout { ... }`
trailing-lambda decoys, and same-name non-Compose local calls are ignored.
Content-slot wrapper detection reuses the same ignored-range filtering, so slot
declarations inside comments or strings do not emit the signal.

## Default String Resolution

The source-index generator resolves only default-locale Android strings from
`res/values/strings.xml`. Qualified locale folders such as `values-en/` and
`values-ko/` are intentionally ignored. Unknown resource ids still emit the raw
`STRING_RESOURCE` signal but do not emit `STRING_RESOURCE_RESOLVED`.

Resolved string-resource matches use `selected resolved stringResource` with a
bucket score of `48.0`, which intentionally ranks the Kotlin rendering call site
above the XML `strings.xml` entry for the same visible text.

Selected Compose `stateDescription` values are also matched against source text
and resolved string-resource signals. This keeps stateful controls such as
toggle buttons and sort controls from losing their source hint when their
visible text is empty.

## Owner Composable Resolution

The Kotlin scanner tracks the enclosing `@Composable fun` with the same
regex-and-brace-depth fidelity as the rest of the source scanner. Entries inside
that function body receive `LAMBDA_OWNER_FUNCTION=<name>`. Entries outside any
composable body do not receive an owner.

The scanner is not a Kotlin parser. Braces inside string literals can affect the
brace-depth heuristic, matching the known limitations of the existing regex
scanner.

Layout renderer call sites inherit `LAMBDA_OWNER_FUNCTION` like other entries.
When a selected target uses a strict test tag (`comp:<ComposableName>:...`,
`screen:<ComposableName>:...`, or dot-delimited `comp.<ComposableName>.<id>`), the
matcher may surface a `Layout`/`SubcomposeLayout` call or a content-slot wrapper
composable inside that owner as a medium-confidence edit surface hint. The layout
renderer signal is conservative typed evidence for where layout work may live,
not a direct match signal or proof that selected pixels map exactly to that line.

## Agent Handoff

`SourceCandidate.ownerComposable` is populated from `LAMBDA_OWNER_FUNCTION`.
Markdown handoffs render it as `inside fun <Composable>` in precise/full output
and as `owner=<Composable>` on the rank-1 compact candidate when available.

## Runtime Trust Fixtures

Runtime trust fixtures are local-only checks that install a debug app, capture a
real screen through the MCP/session path, resolve a semantics target, and
validate observed `sourceCandidates` plus `targetReliability`. They complement
source-index fixtures; they do not replace the fast build-time source-index
checks and they are not a CI or release requirement.

## Untyped Fallback Evidence

When an entry matches only through the preserved untyped source-index fields
and no typed signal supports the selected text, content description, or role,
the matcher treats the evidence as an untyped fallback. This caps the source
candidate at low confidence and adds the internal `UNTYPED_FALLBACK` risk.

For compatibility with persisted session JSON and existing agent prompts, the
current output still serializes that risk as `LEGACY_FALLBACK` and renders the
match reason string as `"legacy fallback"` in `matchReasons` / compact
`matched=[legacy]` output. Those labels describe weak untyped source evidence;
they do not imply support for pre-v0.4 browser recovery mirrors or old local
artifact roots.
