# Source Matching Reference

FixThis source matching starts from the build-time `fixthis-source-index.json`
asset. The Gradle task scans Kotlin sources and default-locale Android string
resources, then the runtime matcher ranks source-index entries against the
selected Compose semantics node and nearby context.

## Source Index Schema

Current source-index `schemaVersion` is `1.2`. FixThis is pre-1.0, so this
version is informational and does not promise forward compatibility with older
assets.

Each entry preserves the legacy field lists (`symbols`, `text`,
`contentDescriptions`, `testTags`, `stringResources`, `roles`, and
`activityNames`) and also carries typed `signals`. Current signal kinds are:

- `COMPOSABLE_SYMBOL`: an `@Composable fun` declaration.
- `UI_TEXT`: literal UI text found in recognized Compose UI calls.
- `STRING_RESOURCE`: a resource id referenced as `R.string.<name>` or defined
  in XML.
- `STRING_RESOURCE_RESOLVED`: the default-locale string value for a direct
  `stringResource(R.string.<name>)` call. This signal is emitted on the Kotlin
  call-site entry, not on the XML resource entry.
- `TEST_TAG`: a Compose `testTag(...)`.
- `STRICT_COMP_TEST_TAG`: a `comp:<ComposableName>:...` test tag.
- `CONTENT_DESCRIPTION`: a Compose semantics content description.
- `ROLE`: a role signal.
- `ACTIVITY_NAME`: an activity-name signal.
- `ARBITRARY_STRING_LITERAL`: a Kotlin string literal not recognized as a
  stronger UI signal.
- `LAMBDA_OWNER_FUNCTION`: the simple name of the enclosing `@Composable fun`
  for indexed entries inside a composable body.

## Default String Resolution

The source-index generator resolves only default-locale Android strings from
`res/values/strings.xml`. Qualified locale folders such as `values-en/` and
`values-ko/` are intentionally ignored. Unknown resource ids still emit the raw
`STRING_RESOURCE` signal but do not emit `STRING_RESOURCE_RESOLVED`.

Resolved string-resource matches use `selected resolved stringResource` with a
bucket score of `48.0`, which intentionally ranks the Kotlin rendering call site
above the XML `strings.xml` entry for the same visible text.

## Owner Composable Resolution

The Kotlin scanner tracks the enclosing `@Composable fun` with the same
regex-and-brace-depth fidelity as the rest of the source scanner. Entries inside
that function body receive `LAMBDA_OWNER_FUNCTION=<name>`. Entries outside any
composable body do not receive an owner.

The scanner is not a Kotlin parser. Braces inside string literals can affect the
brace-depth heuristic, matching the known limitations of the existing regex
scanner.

## Agent Handoff

`SourceCandidate.ownerComposable` is populated from `LAMBDA_OWNER_FUNCTION`.
Markdown handoffs render it as `inside fun <Composable>` in precise/full output
and as `owner=<Composable>` on the rank-1 compact candidate when available.
