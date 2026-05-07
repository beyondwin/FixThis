## Summary

## Impacted Layers
- [ ] Domain (`fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain`)
- [ ] Use cases (`fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/usecase`)
- [ ] MCP DTO / persistence
- [ ] CLI / bridge
- [ ] Compose overlay UI
- [ ] Studio theme
- [ ] Tests only
- [ ] Documentation only

## Compatibility
- [ ] Existing persisted sessions still decode
- [ ] MCP JSON field names are unchanged
- [ ] CLI commands keep their current flags and output shape
- [ ] Existing Compose public APIs keep source compatibility or the PR explains the break

## Architecture Checks
- [ ] No MCP DTO imported into `compose-core`
- [ ] No domain model exposed directly from Composable parameters
- [ ] New Studio UI spacing and shapes use theme tokens
- [ ] New coroutine code does not introduce monitor locks around disk or bridge I/O
- [ ] Related ADR is linked or updated

## Verification
- [ ] Required local checks are listed below with PASS or FAIL results
- [ ] Connected-device verification is listed below, or SKIPPED with one of: `SKIPPED_NO_DEVICE`, `SKIPPED_UNAUTHORIZED_DEVICE`, `SKIPPED_LOCKED_DEVICE`, `SKIPPED_WIRELESS_ADB_LOST`
- [ ] Any other relevant checks that were not run are listed as SKIPPED with a reason and residual risk

| Command / check | Result | Notes |
| --- | --- | --- |
| `./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test` | PASS / FAIL |  |
| `./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist` | PASS / FAIL |  |
| `node --check fixthis-mcp/src/main/resources/console/app.js` | PASS / FAIL |  |
| `git diff --check` | PASS / FAIL |  |
| Connected-device verification | PASS / FAIL / SKIPPED_* |  |
