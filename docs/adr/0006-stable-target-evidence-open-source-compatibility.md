# ADR-0006: Stable Target Evidence For Open Source Compose Compatibility

- Status: Accepted
- Date: 2026-05-07

## Context

FixThis needs richer handoff evidence so an agent can identify the selected UI target, distinguish repeated UI elements, and judge source-candidate confidence. The earlier target-evidence plan included two ideas:

- Phase 1: stable evidence built from semantics, test tags, source candidates, screenshots, and Markdown detail modes.
- Phase 2: composable identity based on Compose tooling data such as `LocalInspectionTables`, `CompositionData.mapTree`, `parseSourceInformation`, call-site candidates, definition candidates, and wrapper inference.

FixThis is intended to be open source. The default experience should work across a range of Jetpack Compose versions, Kotlin versions, and app setups. Depending on Compose tooling internals in the core capture path would make the public library fragile because those APIs are not the same as stable app-facing semantics APIs. Some tooling APIs are available through separate artifacts, some require opt-in annotations, and some depend on how inspection data is recorded by the composition.

The project can still use optional, best-effort enhancements, but the core handoff must degrade gracefully when optional evidence is missing.

## Decision

Stable Target Evidence v1 is implemented without requiring Compose tooling data.

The stable path uses only evidence already available in the current architecture:

- `FixThisNode` semantics fields: role, text, editable text, content description, test tag, actions, bounds, sensitivity flags, and tree kind.
- A strict `comp:<ComposableName>:<variant>` test-tag convention.
- Occurrence counting over captured merged semantics nodes.
- Existing `SourceMatcher` candidates and match reasons.
- Markdown detail modes that change only the Markdown surface, not the JSON payload.

`targetEvidence` is nullable, additive data on `FixThisAnnotation`, domain `Annotation`, and MCP `AnnotationDto`. Existing fields are not removed or renamed.

The bridge protocol version is not bumped for this additive evidence. Instead, bridge status exposes additive capabilities, such as:

```json
{
  "capabilities": {
    "targetEvidence": true,
    "detailModes": ["compact", "precise", "full"],
    "composableIdentity": false
  }
}
```

Compose tooling based composable identity will remain disabled by default and out of scope for Stable Target Evidence v1. If implemented later, it should live behind an experimental capability and preferably in a separate optional module or artifact. The default `fixthis-compose-sidekick` path must continue to work without `ui-tooling-data` or runtime tooling introspection.

Current mainline implementation computes target evidence in the MCP feedback
session Save path, using the frozen preview's captured merged semantics nodes,
selected target, source-index candidates, and screenshot artifact availability.

## Consequences

- FixThis can support a broader range of Compose versions because the core feature relies on semantics and string conventions rather than tooling internals.
- Agents receive more stable target identity and occurrence information without forcing apps to adopt a specific Compose BOM.
- JSON compatibility remains tolerant: older persisted sessions omit `targetEvidence`; newer JSON can be ignored by older readers that use `ignoreUnknownKeys`.
- Source location precision is intentionally limited. Stable Target Evidence v1 improves "which UI target and likely source" but does not promise exact composable call-site recovery.
- Occurrence counting must be described as based on captured merged semantics nodes, not as a guaranteed count of every visible UI element in every Android window.
- Experimental composable identity remains possible later, but it must be advertised as best effort and disabled when its capability cannot be proven.

## Alternatives Considered

- Require a specific Compose BOM and implement composable identity in the default capture path. Rejected because it would make open-source adoption brittle and make upgrades risky for downstream apps.
- Add `ui-tooling-data` to the default sidekick and infer call sites by default. Rejected because tooling data is useful but not necessary for the core handoff problem, and it creates a harder compatibility surface.
- Bump `BridgeProtocol.VERSION` for `targetEvidence`. Rejected because nullable additive JSON fields do not require a protocol break. A version bump would also require synchronized changes in the CLI and would break mixed-version setups unnecessarily.
- Build target evidence only in MCP after reading a session. Rejected because occurrence needs the full captured node list at the capture point, while persisted annotation DTOs may only retain selected and nearby nodes.
- Keep only Markdown improvements and avoid JSON evidence. Rejected because agents need machine-readable evidence that stays complete even when Markdown is compact.
