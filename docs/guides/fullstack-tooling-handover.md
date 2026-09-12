# Fullstack / Tooling Handover

Short maintainer map. Start with the
[architecture overview](../architecture/overview.md) for terms and ownership.
Daily routing: [project map](project-map.md) and
[agent code compass](../architecture/agent-code-compass.md).

## What to open

| Question | Open |
| --- | --- |
| What is the product? | [README](../../README.md), [product](../product/README.md) |
| Where does code live? | [Project map](project-map.md), [architecture](../architecture/overview.md) |
| Why a trade-off exists | [Decisions](../product/decision-rationale.md), [ADRs](../architecture/adr/README.md) |
| CLI / MCP / JSON contracts | [Reference](../index.md#reference-contracts) |
| How to verify | [CONTRIBUTING](../../CONTRIBUTING.md) |
| How to ship | [Release readiness](../contributing/release-readiness.md) |

## Non-negotiables

- `:fixthis-compose-core` stays free of MCP, CLI, Android UI, browser DTOs, and `.fixthis/` paths.
- Persisted JSON field names are compatibility contracts.
- The Android app does not host HTTP or MCP.
- Bridge protocol changes move `BridgeProtocol.VERSION`, console minimum, `BridgeClient.kt`, and `ServerVersionRoutes.kt` together.
- Do not commit `.fixthis/`, screenshots, reports, or fixture workspaces.
- Do not hand-edit generated console assets.

## First week

1. Run the sample: [try the sample](../getting-started/try-the-sample.md).
2. Read [architecture overview](../architecture/overview.md).
3. Pick a task, then `npm run agent:route -- --task <id> --json`.
4. Change the smallest surface the router names.
5. Run the returned focused checks before the broad gate.

Historical planning lives under `docs/superpowers/`, `docs/specs/`, and
`docs/plans/`. Use it only when a maintained page or source file points there.
