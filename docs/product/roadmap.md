# Roadmap

FixThis V1 is intentionally narrow. The current roadmap keeps the product
focused on a local, debug-only Compose handoff loop before expanding into
broader Android UI stacks or additional package channels.

## V1 Scope

- **Jetpack Compose only.** View-based hierarchies, XML layouts, AndroidView
  interop, and WebViews are not source-targeted.
- **Debug builds only.** The sidekick is installed through `debugImplementation`
  and is omitted from release builds.
- **Local-only and ADB-only.** FixThis runs over ADB and localhost. It does not
  upload screenshots, comments, source hints, or prompt text.
- **No required `testTag`s.** Smart Select prefers semantics, nearby labels, and
  composable-name conventions. Tags improve evidence but are optional.
- **No AccessibilityService.** Inspection runs in-process inside the debug app
  through the sidekick.
- **MCP-first workflow.** The browser feedback console is the primary surface.
  The Android app shows only a connection status pill.
- **Best-effort source candidates.** Handoffs include up to three ranked
  candidates plus confidence, margin, and warning signals so agents can verify
  before editing.
- **Pixel screenshots.** Editable and password text is redacted from semantics,
  but screenshots can still contain sensitive pixels.

## High-priority Work

### Public artifact release upkeep

External projects install FixThis with the published Gradle plugin and Maven
artifacts instead of a source checkout, Gradle composite build, or local
repository setup. Ongoing work is release upkeep: keep coordinates, validation
steps, and rollback notes accurate as the public channels evolve.
The release readiness tracker is the live source of truth:
[Release readiness](../contributing/release-readiness.md).

### CLI/MCP package for agent-first installation upkeep

GitHub Releases attach a CLI/MCP package so agents can install the desktop
tooling with `scripts/install-fixthis.sh` and then run
`fixthis install-agent` or `fixthis init`.

The Homebrew tap is available for macOS:

```bash
brew install beyondwin/fixthis/fixthis
```

### Registry discovery channels

The npm wrapper is published as `@beyondwin/fixthis`, and the MCP Registry
entry is published as `io.github.beyondwin/fixthis`. Future discovery work can
add more package channels, such as PyPI or Docker, only when they preserve the
same agent-first setup flow.

### Deeper AndroidView and interop awareness

FixThis currently warns when a selected target may cross a View/WebView
boundary. Future work should expose richer subtree evidence instead of only
lowering target confidence.

### Finish SSE-driven console state sync

Phase 1 pushes session, device, connection, and preview updates over
`/api/events`, with polling retained as a fallback and session/preview events
fenced by active `sessionId`.

Remaining work is to retire the preview polling loop and reduce redundant pull
refreshes. See [Console state sync](../architecture/console-state-sync-design.md).

### Smarter source matching

Future matcher work should keep confidence explainable while expanding the set
of source patterns FixThis recognizes:

- `Layout` and `SubcomposeLayout` wrappers
- more composable-name conventions
- richer handling for shared reusable components
- clearer distinction between call sites, component definitions, and copy/data
  sources

### More agents out of the box

Cursor, Aider, ChatGPT, and other agents already work through Copy Prompt.
First-class MCP/config writers can follow the Claude Code and Codex setup
pattern.

## Out of Scope Until After V1

- XML/View exact source targeting
- WebView DOM inspection
- Flutter, React Native, iOS, or other app stacks
- production runtime usage
- cloud review service behavior
- automatic code edits inside FixThis itself
- guaranteed exact source-line mapping

## Contributing

Vote items up or contribute through the [Contributing guide](../../CONTRIBUTING.md).
