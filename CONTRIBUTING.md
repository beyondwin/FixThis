# Contributing

## Required Local Checks

Run these before opening a pull request:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

## Connected Device Checks

Connected-device verification is manual until the project has a reliable device or emulator runner. When it is skipped, record one of these categories in the pull request:

- `SKIPPED_NO_DEVICE`
- `SKIPPED_UNAUTHORIZED_DEVICE`
- `SKIPPED_LOCKED_DEVICE`
- `SKIPPED_WIRELESS_ADB_LOST`

## Local Artifacts

`.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`, `.fixthis/artifacts/`, and `.fixthis/smoke-reports/` can contain screenshots or local feedback. Do not commit or share them casually.

## Compatibility Checklist

- Existing persisted sessions still decode.
- MCP JSON field names are unchanged unless the pull request explains a migration.
- CLI commands keep their current flags and output shape unless the pull request explains the break.
- Existing Compose public APIs keep source compatibility or the pull request explains the break.
- New coroutine code does not hold monitor locks around disk or bridge I/O.
