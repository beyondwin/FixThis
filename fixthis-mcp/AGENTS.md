# MCP And Console Agent Notes

This subtree owns MCP, local HTTP, feedback sessions, handoff rendering, and queue persistence.

- Preserve persisted JSON names and backward decoding: `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, and `sourceCandidates` are compatibility boundaries.
- For persistence/session changes run `./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest' --tests '*FeedbackSessionService*' --no-daemon`; legacy sessions must keep decoding.
- Keep Copy Prompt and Save to MCP compact-handoff grammar aligned.
- For console edits run node scripts/build-console-assets.mjs and then its --check form.
- Run npm run console:test:fast and ./gradlew :fixthis-mcp:test --no-daemon as applicable.
- For handoff changes also run npm run handoff:eval:test.
- Do not expose raw runtime evidence in JSON, MCP Markdown, or logs.
