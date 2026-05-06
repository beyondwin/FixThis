## Summary

## Impacted Layers
- [ ] Domain (`pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/domain`)
- [ ] Use cases (`pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/usecase`)
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
