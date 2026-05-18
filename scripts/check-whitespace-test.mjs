import assert from "node:assert/strict";
import { filterWhitespaceOutput } from "./check-whitespace.mjs";
import test from "node:test";

test("filters whitespace warnings for docs/superpowers Markdown docs", () => {
  const result = filterWhitespaceOutput([
    "docs/superpowers/plans/2026-05-15-plan.md:25: trailing whitespace.",
    "+- `fixthis-mcp/src/main/console/domain/workspaceState.js`  ",
    "docs/superpowers/specs/2026-05-09-design.md:3: trailing whitespace.",
    "+**Date:** 2026-05-09  ",
    "docs/superpowers/work-notes/2026-05-17-ledger.md:9: new blank line at EOF.",
    "docs/superpowers/notes.md:11: trailing whitespace.",
    "+note  ",
    "",
  ].join("\n"));

  assert.equal(result.ignoredCount, 4);
  assert.equal(result.output, "");
});

test("keeps whitespace warnings outside docs/superpowers Markdown docs", () => {
  const result = filterWhitespaceOutput([
    "README.md:10: trailing whitespace.",
    "+bad  ",
    "scripts/draftStorageAdapter-test.mjs:127: new blank line at EOF.",
    "",
  ].join("\n"));

  assert.equal(result.ignoredCount, 0);
  assert.equal(result.output, [
    "README.md:10: trailing whitespace.",
    "+bad  ",
    "scripts/draftStorageAdapter-test.mjs:127: new blank line at EOF.",
  ].join("\n"));
});
