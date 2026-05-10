# Archive — Historical Documentation

> ⚠️ **ARCHIVED.** The documents in this directory are preserved for the
> design-decision trail but no longer reflect the current shipped behavior of
> FixThis. They are mostly written in Korean and date from the early product
> exploration phase (2026-05-03 to 2026-05-07).
>
> **For current behavior, start at [`docs/index.md`](../index.md).** When an
> archived document conflicts with a current reference under
> [`docs/reference/`](../reference) or [`docs/architecture/`](../architecture),
> the current reference wins.

## Why these are kept

These files captured early product reasoning — why FixThis is Compose-only,
why it's debug-only, why the feedback console moved out of the app and onto
the desktop, etc. The reasoning is still useful when revisiting an old
trade-off; the *implementation details* described in them are not.

## Contents

### Product / decisions

- [`prd-v1-2026-05-03.md`](prd-v1-2026-05-03.md) — initial Product Requirements
  Document (Korean). Many UX details (in-app annotation, floating button,
  clipboard-first flow) describe explorations that were superseded by the
  MCP-first feedback console.
- [`decisions-v1-2026-05-03.md`](decisions-v1-2026-05-03.md) — narrated
  decision log explaining why each major direction was picked over its
  alternatives. Companion to the PRD.
- [`technical-design-v1-2026-05-03.md`](technical-design-v1-2026-05-03.md) —
  initial detailed technical design. Module names, file paths, and runtime
  flows have drifted; treat as background only.

### Design briefs (superseded)

- [`design-zero-setup.md`](design-zero-setup.md) — early "zero-setup" install
  flow exploration.
- [`design-feedback-console-ux.md`](design-feedback-console-ux.md) — early
  console UX status note (current contract is in
  [`docs/reference/feedback-console-contract.md`](../reference/feedback-console-contract.md)).
- [`design-claude-redesign-brief.md`](design-claude-redesign-brief.md) — early
  Studio redesign brief.

### Roadmap / improvement proposals

- [`project-improvement-proposals-2026-05-07.md`](project-improvement-proposals-2026-05-07.md) —
  improvement proposals from 2026-05-07. Most have been implemented or
  superseded; check the [`CHANGELOG`](../../CHANGELOG.md) for what shipped.

## Where to look instead

| You want                          | Look here                                                                                         |
| --------------------------------- | ------------------------------------------------------------------------------------------------- |
| What FixThis is + Quick Start     | [`README.md`](../../README.md), [`docs/index.md`](../index.md)                                     |
| MCP tools + workflow              | [`docs/reference/mcp-tools.md`](../reference/mcp-tools.md)                                         |
| Console contract / persisted JSON | [`docs/reference/feedback-console-contract.md`](../reference/feedback-console-contract.md), [`docs/reference/output-schema.md`](../reference/output-schema.md) |
| Architecture overview             | [`docs/architecture/overview.md`](../architecture/overview.md)                                     |
| Architecture decisions            | [`docs/architecture/adr/`](../architecture/adr)                                                    |
| Troubleshooting                   | [`docs/guides/troubleshooting.md`](../guides/troubleshooting.md)                                   |
