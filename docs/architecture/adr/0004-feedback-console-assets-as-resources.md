# ADR-0004: Feedback Console Assets As Resources

- Status: Accepted
- Date: 2026-05-06

## In short

Console HTML, CSS, and JS are files on the classpath. Kotlin only loads
them. Do not put the UI back into a giant string.

## Context

The console used to store HTML, CSS, and JavaScript in one large Kotlin
raw string. The split places `index.html`, `styles.css`, and `app.js` under
`fixthis-mcp/src/main/resources/console`. A Kotlin loader assembles them.

## Decision

Browser assets are classpath resources. Kotlin code only loads and injects
those resources into the served HTML.

## Consequences

- HTML, CSS, and JavaScript diffs are reviewable as separate files.
- Resource loading is tested, including path-traversal rejection.
- MCP code still assembles the served HTML.

## Alternatives Considered

- Keep the raw string in Kotlin. Rejected: large asset changes were hard
  to review.
- Serve separate static files from arbitrary disk paths. Rejected: the
  console server uses packaged resources and must not expose unmanaged
  paths.
