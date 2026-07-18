# Compose Core Agent Notes

This subtree is pure Kotlin domain logic.

- Do not import Android UI, MCP, CLI, Gradle plugin, sidekick, browser DTO, or .fixthis types.
- Keep source scoring deterministic and persisted-schema-neutral.
- Start with docs/reference/source-matching.md and docs/reference/output-schema.md.
- Run ./gradlew :fixthis-compose-core:test --no-daemon.
- For persisted target/source model changes run `./gradlew :fixthis-compose-core:test --tests '*TargetEvidenceModelTest' --tests '*SourceCandidateSerializationTest' --no-daemon` and preserve backward decoding.
- For source matching also run npm run source-matching:fixtures:test.
- Update the relevant ADR and architecture test with any boundary change.
