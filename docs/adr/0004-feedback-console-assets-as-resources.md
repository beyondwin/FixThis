# ADR-0004: Feedback Console Assets As Resources

- Status: Accepted
- Date: 2026-05-06

## Context

The feedback console previously stored the HTML document, CSS, and JavaScript in one large Kotlin raw string. The implemented resource split places `index.html`, `styles.css`, and `app.js` under `pointpatch-mcp/src/main/resources/console` and keeps a Kotlin loader responsible for assembly.

## Decision

Feedback console browser assets are classpath resources, and Kotlin code only loads and injects those resources into the served HTML.

## Consequences

- HTML, CSS, and JavaScript diffs are reviewable as separate files.
- Resource loading is covered by tests, including path traversal rejection.
- The served console HTML contract remains assembled by MCP code.

## Alternatives Considered

- Keep the raw string in Kotlin. Rejected because large asset changes were difficult to review and maintain.
- Serve separate static files from arbitrary file-system paths. Rejected because the current console server uses packaged resources and should not expose unmanaged paths.
