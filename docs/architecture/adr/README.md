# Architecture Decision Records

This directory records durable architecture decisions for FixThis.

Each ADR uses a monotonic numeric prefix, a short kebab-case title, and one status:

- Proposed
- Accepted
- Deprecated
- Superseded

Every ADR must include Context, Decision, Consequences, and Alternatives Considered.

## Accepted ADRs

- [ADR-0001: Use Clean Architecture Layering](0001-use-clean-architecture-layering.md)
- [ADR-0002: Domain Models Live In Compose Core](0002-domain-models-live-in-compose-core.md)
- [ADR-0003: Feedback Item To Annotation Naming](0003-feedback-item-to-annotation-naming.md)
- [ADR-0004: Feedback Console Assets As Resources](0004-feedback-console-assets-as-resources.md)
- [ADR-0006: Stable Target Evidence Open Source Compatibility](0006-stable-target-evidence-open-source-compatibility.md)
- [ADR-0007: Feedback Console Owns Connection Recovery](0007-feedback-console-connection-recovery.md)
- [ADR 2026-05-14: Bridge Server Concurrency Model](2026-05-14-bridge-server-concurrency.md)

## Superseded ADRs

- [ADR-0005: Overlay Mode State Machine](0005-overlay-mode-state-machine.md)

ADR-0005 records the earlier in-app overlay state-machine design. The current
mainline product path is MCP feedback console first, with the Android app
showing only MCP connection status.
