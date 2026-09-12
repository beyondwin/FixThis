# ADR-0006: Stable Target Evidence For Open Source Compose Compatibility

- Status: Accepted
- Date: 2026-05-07

## In short

Target identity uses semantics, test tags, occurrence, and source
candidates. It does not use Compose tooling internals. `targetEvidence` is
additive JSON. The bridge protocol stays `1.3`.

## Context

Agents need to know which UI target was selected, tell repeated items
apart, and judge source-candidate confidence.

An earlier plan had two layers:

- Phase 1: stable evidence from semantics, test tags, source candidates,
  screenshots, and Markdown detail modes.
- Phase 2: composable identity from Compose tooling data such as
  `LocalInspectionTables` and `parseSourceInformation`.

FixThis is open source. The default path must work across Compose versions,
Kotlin versions, and app setups. Tooling APIs are not the same as stable
app-facing semantics APIs. Some need extra artifacts or opt-in annotations.

Optional extras are fine later. The core handoff must still work when they
are missing.

## Decision

Stable Target Evidence v1 does not require Compose tooling data.

It uses evidence already in the architecture:

- `FixThisNode` semantics: role, text, editable text, content description,
  test tag, actions, bounds, sensitivity flags, and tree kind.
- A strict `comp:<ComposableName>:<variant>` test-tag convention.
- Occurrence counting over captured merged semantics nodes.
- Existing `SourceMatcher` candidates and match reasons.
- Markdown detail modes that change only the Markdown surface, not JSON.

`targetEvidence` is nullable, additive data on `FixThisAnnotation`, domain
`Annotation`, and MCP `AnnotationDto`. Existing fields are not removed or
renamed.

The bridge protocol version is not bumped. Status exposes additive
capabilities instead:

```json
{
  "capabilities": {
    "targetEvidence": true,
    "detailModes": ["compact", "precise", "full"],
    "composableIdentity": false
  }
}
```

Compose-tooling composable identity stays disabled by default and out of
scope for v1. If added later, it should be experimental and preferably a
separate optional artifact. Default `fixthis-compose-sidekick` must work
without `ui-tooling-data`.

Current mainline code computes target evidence in the MCP Save path, using
the frozen preview's captured nodes, selected target, source-index
candidates, and screenshot availability.

## Consequences

- Core identity works across a wider Compose range.
- Agents get occurrence and target identity without a forced Compose BOM.
- Older sessions omit `targetEvidence`; newer JSON can be ignored by
  readers that use `ignoreUnknownKeys`.
- Source location stays best-effort. This improves "which UI target and
  likely source", not exact call-site recovery.
- Occurrence is a count of captured merged semantics nodes, not every
  visible pixel in every Android window.
- Experimental composable identity can still be added later, advertised as
  best-effort, and disabled when unproven.

## Alternatives Considered

- Require a specific Compose BOM and implement composable identity in the
  default capture path. Rejected: brittle for open-source apps.
- Add `ui-tooling-data` to the default sidekick. Rejected: useful, not
  necessary, and a harder compatibility surface.
- Bump `BridgeProtocol.VERSION` for `targetEvidence`. Rejected: nullable
  additive JSON does not need a protocol break.
- Build target evidence only after reading a session. Rejected: occurrence
  needs the full captured node list at capture time.
- Keep only Markdown improvements. Rejected: agents need machine-readable
  evidence even when Markdown is compact.
