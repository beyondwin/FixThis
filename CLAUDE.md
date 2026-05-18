# FixThis — Claude Code Project Memory

Entry point for Claude Code working in this repository. See
[AGENTS.md](AGENTS.md) for the project overview, MCP setup, AI workflow, and
constraints — those apply equally to Claude Code.

## Pointers

- **Build, test, console rebundle, lint commands** → [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **Final verification before PRs** → run the full Gradle matrix, console
  asset check, console JS syntax check, console JS harnesses, and
  `git diff --check` from [`CONTRIBUTING.md`](CONTRIBUTING.md#required-local-checks).
- **Restart loop after Kotlin changes** → run `bash scripts/restart-console.sh`
  (add `--with-app` to also reinstall the sample APK). Kotlin server code is
  pinned in the JAR; the console JS is live-reloaded under
  `--console-assets-dir`. Full flag list in
  [`CONTRIBUTING.md` § Console Inner Loop](CONTRIBUTING.md#console-inner-loop).
- **Bridge protocol changes** must update `BridgeProtocol.VERSION`, the console
  `MinimumSupportedProtocolVersion`, and the mirrored constants in
  `BridgeClient.kt` and `ServerVersionRoutes.kt`. `BridgeProtocolVersionSyncTest`
  (`:fixthis-mcp:test`) enforces equality across all four sites at CI time. Full
  procedure: [`docs/reference/bridge-protocol.md`](docs/reference/bridge-protocol.md).
- **Architecture invariants** (core has no MCP/CLI/Android dep, persisted JSON
  field names are compatibility contracts, sidekick hosts no HTTP/MCP server)
  → [`docs/architecture/overview.md`](docs/architecture/overview.md) and
  [`docs/architecture/adr/`](docs/architecture/adr/).
- **Console UI iteration** — pass `--console-assets-dir` to read HTML/CSS/JS
  from source instead of the packaged JAR; rebundle JS via
  `node scripts/build-console-assets.mjs` after edits (produces minified
  `app.js` ≤ 225 KiB raw / 58 KiB gzip, an `app.js.map` source map for
  DevTools, and a `console-build-meta.json` sidecar inlined as
  `window.FixThisConsoleConfig.buildMeta` at serve time), then verify with
  `node scripts/build-console-assets.mjs --check`. Details in
  [`docs/reference/feedback-console-contract.md`](docs/reference/feedback-console-contract.md).
- **Connected tests** — require an unlocked interactive emulator or device.
  Locked physical devices can still report `device` in `adb` while failing
  Compose hierarchy discovery. See
  [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md).
