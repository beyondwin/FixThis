# Compose Sidekick Agent Notes

This subtree is the debug-only Android runtime inside the target app.

- Never add release-variant behavior.
- Do not move MCP session storage, HTTP, or browser state into the app.
- Coordinate bridge changes with the CLI and docs/reference/bridge-protocol.md.
- Run ./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon.
- Run npm run android:proof -- --strict when the connected path changes.
- Report unavailable, unauthorized, offline, locked, or ambiguous devices explicitly.
