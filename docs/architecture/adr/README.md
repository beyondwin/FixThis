# Architecture Decision Records

Decisions that still constrain the code. Each file has Context, Decision,
Consequences, and Alternatives Considered.

Status is one of: Proposed, Accepted, Deprecated, or Superseded.

Start with the one-line note. Open the ADR when you need the rationale or
an exception table.

## Accepted

| ADR | In practice |
| --- | --- |
| [0001 Clean layering](0001-use-clean-architecture-layering.md) | Domain stays in `compose-core`. Outer modules adapt. |
| [0002 Domain in compose-core](0002-domain-models-live-in-compose-core.md) | Annotation/snapshot/session models are pure Kotlin. |
| [0003 Annotation vs item](0003-feedback-item-to-annotation-naming.md) | Code says annotation. Disk/MCP still say `items`. |
| [0004 Console assets](0004-feedback-console-assets-as-resources.md) | HTML/CSS/JS are files, not a Kotlin string. |
| [0006 Stable target evidence](0006-stable-target-evidence-open-source-compatibility.md) | Identity comes from semantics, not Compose tooling internals. |
| [0007 Console owns recovery](0007-feedback-console-connection-recovery.md) | The browser reconnects. The app stays a status pill. |
| [0008 Session packages](0008-session-package-decomposition.md) | Split `session/` by job. New imports need a rule. |
| [0009 Target type strategy](0009-feedback-target-type-polymorphism.md) | Node vs area behavior lives in one strategy each. |
| [2026-05-14 Bridge concurrency](2026-05-14-bridge-server-concurrency.md) | Start/stop are serialized. After stop, create a new server. |

## Superseded

| ADR | In practice |
| --- | --- |
| [0005 Overlay state machine](0005-overlay-mode-state-machine.md) | Retired. Do not implement. Console owns selection now. |
