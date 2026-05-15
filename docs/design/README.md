# Design Rationale

This directory contains maintained design explanations that are too product- or
workflow-focused for an ADR but important enough to be part of the normal
documentation set.

Read these when you want to understand why the current user-facing shape exists.
Read `docs/reference/` when you need the exact API, JSON, or prompt contract.

## Documents

- [Handoff prompt rationale](handoff-prompt-rationale.md) explains the compact
  Markdown handoff format, why the prompt includes source candidates,
  confidence, instance markers, and agent protocol fields, and why the renderer
  is server-owned.

Current precedence order:

1. Reference docs and output schemas
2. ADRs
3. Maintained design rationale in this directory
4. Release notes and tests
5. Archived product and technical background
