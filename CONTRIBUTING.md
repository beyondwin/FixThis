# Contributing

## Formatting

Kotlin sources and Gradle Kotlin DSL scripts are formatted by [Spotless](https://github.com/diffplug/spotless)
with the [ktlint](https://github.com/pinterest/ktlint) formatter.

```bash
./gradlew spotlessApply   # format the codebase locally
./gradlew spotlessCheck   # verify formatting (this is what CI runs)
```

CI runs `./gradlew spotlessCheck` before unit tests and fails on unformatted files.
The historical bulk reformatting commit is recorded in
[`.git-blame-ignore-revs`](.git-blame-ignore-revs); configure git to ignore it for
`git blame` with:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

## Required Local Checks

Run these before opening a pull request:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

If you edited any console JS module under `fixthis-mcp/src/main/console/`, rebundle the served asset before running `installDist` and the syntax check:

```bash
node scripts/build-console-assets.mjs
```

Optional console smoke harnesses (require Node + a recent Chromium via Playwright) live under `scripts/`:

```bash
node scripts/console-browser-smoke.mjs       # end-to-end console smoke
node scripts/console-availability-test.mjs   # availability/blocked-state harness
node scripts/console-blocked-harness.mjs     # blocked-overlay rendering harness
```

## Connected Device Checks

Connected-device verification is manual until the project has a reliable device or emulator runner. Run the smoke harness when validating device behavior:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample
```

For host-only validation, use:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only
```

Smoke reports are written under ignored `.fixthis/smoke-reports/` as Markdown and JSON. When connected smoke is skipped, record one of these categories in the pull request:

- `SKIPPED_HOST_ONLY`
- `SKIPPED_ADB_NOT_FOUND`
- `SKIPPED_NO_DEVICE`
- `SKIPPED_UNAUTHORIZED_DEVICE`
- `SKIPPED_OFFLINE_DEVICE`
- `SKIPPED_LOCKED_DEVICE`
- `SKIPPED_WIRELESS_ADB_LOST`
- `SKIPPED_MULTIPLE_DEVICES`

## Local Artifacts

`.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`, `.fixthis/artifacts/`, and `.fixthis/smoke-reports/` can contain screenshots or local feedback. Do not commit or share them casually.

## Compatibility Checklist

- Existing persisted sessions still decode.
- MCP JSON field names are unchanged unless the pull request explains a migration.
- CLI commands keep their current flags and output shape unless the pull request explains the break.
- Existing Compose public APIs keep source compatibility or the pull request explains the break.
- New coroutine code does not hold monitor locks around disk or bridge I/O.
