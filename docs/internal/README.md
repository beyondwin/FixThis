# Internal Documentation

This directory holds **internal AI orchestration artifacts** — implementation
plans and design specs produced by the maintainers' AI-assisted development
workflow. They are kept in the repository for transparency, but they are not
part of the public documentation surface.

If you are looking for user-facing documentation, start at
[`docs/index.md`](../index.md).

## Contents

- [`superpowers/plans/`](superpowers/plans) — implementation plans, one per shipped
  feature or refactor. Useful as historical reference for *why* a change landed
  the way it did, but the actual current behavior is described in the user
  guides under [`docs/guides/`](../guides) and the references under
  [`docs/reference/`](../reference).
- [`superpowers/specs/`](superpowers/specs) — design specs and open-risk write-ups
  that fed into those plans.

## Stability

Files in `docs/internal/` are **not under any compatibility guarantee** —
they may be edited, deleted, or rewritten without notice. Public-facing
documentation lives elsewhere and is kept in sync with the shipped code.

If a document here describes behavior that contradicts a public reference,
the public reference wins. File an issue if you find a discrepancy.
