# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- Release and getting-started docs now reflect the post-v0.2.3 channel state:
  Homebrew is available at `beyondwin/fixthis/fixthis`, the scoped npm wrapper
  is available at `@beyondwin/fixthis`, and the MCP Registry entry is
  `io.github.beyondwin/fixthis`.
- The release dashboard now separates currently usable channels from remaining
  future package targets such as PyPI and Docker.
- Console bundle work on `main` minifies the browser app with esbuild and keeps
  source resolution explicit through `// @requires` directives.

## Compatibility Notes

- External Android apps should use Gradle plugin
  `io.github.beyondwin.fixthis.compose` version `0.2.3`.
- The plugin resolves the debug-only sidekick from Maven Central.
- Homebrew installs the matching CLI/MCP GitHub Release package on macOS.
- npm installs the matching CLI/MCP GitHub Release package through
  `@beyondwin/fixthis`.

## Validation Surface

Before tagging the next release, run the current contributor checklist:

```bash
./gradlew \
  spotlessCheck \
  detekt \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  :app:assembleDebug \
  :fixthis-cli:installDist \
  :fixthis-mcp:installDist \
  --no-daemon
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node scripts/check-doc-consistency.mjs
node scripts/run-console-tests.mjs availability canonical pending beforeunload undo activity preview draft session harness
git diff --check
```

For console layout or agent-state UI changes, also run:

```bash
npm run console:smoke
npm run console:responsive:stress
```
