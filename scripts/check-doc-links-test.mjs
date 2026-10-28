import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { collectDocumentationFiles, validateDocLinks } from "./check-doc-links.mjs";

function fixture(t, files) {
  const root = mkdtempSync(path.join(tmpdir(), "fixthis-doc-links-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const [file, body] of Object.entries(files)) {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    writeFileSync(path.join(root, file), body);
  }
  return root;
}

test("reports deleted files and missing anchors from nested maintained docs", (t) => {
  const root = fixture(t, {
    "docs/index.md": "# Docs\n[History](superpowers/README.md)\n[Setup](guide.md#old-heading)\n",
    "docs/guide.md": "# Setup\n",
  });
  const result = validateDocLinks(root, ["docs/index.md", "docs/guide.md"]);
  assert.equal(result.errors.length, 2);
  assert.match(result.errors[0], /docs\/index.md:2.*superpowers\/README.md.*missing/i);
  assert.match(result.errors[1], /docs\/index.md:3.*old-heading.*heading/i);
});

test("resolves relative files, images, directory READMEs, same-page and duplicate headings", (t) => {
  const root = fixture(t, {
    "README.md": "# Start\n[Self](#start)\n[Guide](docs/guide.md#repeat-1)\n[Folder](docs/#docs)\n![Logo](docs/logo.png)\n[Space](<docs/with space.md#hello>)\n",
    "docs/README.md": "# Docs\n",
    "docs/guide.md": "# Repeat\n## Repeat\n",
    "docs/with space.md": "# Hello\n",
    "docs/logo.png": "image",
  });
  assert.deepEqual(validateDocLinks(root, ["README.md"]).errors, []);
});

test("supports reference links and explicit HTML anchors without fetching external URLs", (t) => {
  const root = fixture(t, {
    "README.md": "[Guide][setup]\n[setup][]\n[setup]\n[HTML](#named)\n[Web](https://example.invalid/a.md#missing)\n[Mail](mailto:dev@example.invalid)\n\n[setup]: docs/guide.md#setup \"Setup guide\"\n<a id=\"named\"></a>\n",
    "docs/guide.md": "# Setup\n",
  });
  assert.deepEqual(validateDocLinks(root, ["README.md"]).errors, []);
  writeFileSync(path.join(root, "docs/guide.md"), "# Renamed\n");
  assert.equal(validateDocLinks(root, ["README.md"]).errors.length, 3);
});

test("ignores fenced code, inline examples and comments but checks real links", (t) => {
  const root = fixture(t, {
    "README.md": "# Docs\n```md\n[Example](missing.md)\n# Fake\n```\n~~~md\n[Example](other.md)\n~~~\n`[Example](inline.md)`\n<!-- [Example](comment.md) -->\n[Real](#fake)\n",
  });
  const result = validateDocLinks(root, ["README.md"]);
  assert.equal(result.errors.length, 1);
  assert.match(result.errors[0], /#fake/);
});

test("uses Markdown headings and skips indented code and escaped link syntax", (t) => {
  const root = fixture(t, {
    "README.md": "# Docs\n[Setext](guide.md#setup)\n[Inline](guide.md#a-b-c--d)\n[Duplicate](guide.md#setup-1)\n\n    [Example](indented.md)\n\n\\[Escaped](escaped.md)\n",
    "guide.md": "Setup\n=====\n\n## **A** `B` [C](README.md) &amp; D\n\nSetup\n-----\n",
  });
  const result = validateDocLinks(root, ["README.md", "guide.md"]);
  assert.deepEqual(result.errors, []);
  assert.equal(result.checked, 4);
});

test("reports each reference use at its line and ignores anchors inside comments", (t) => {
  const root = fixture(t, {
    "README.md": "# Docs\n\n[One][missing]\n[Two][missing]\n\n[missing]: absent.md\n\n<!-- <a id=\"fake\"></a> -->\n[Missing](#fake)\n",
  });
  const result = validateDocLinks(root, ["README.md"]);
  assert.equal(result.errors.length, 3);
  assert.match(result.errors[0], /README\.md:3: absent\.md/);
  assert.match(result.errors[1], /README\.md:4: absent\.md/);
  assert.match(result.errors[2], /README\.md:9: #fake/);
});

test("uses Git inventory including new docs while excluding ignored and frozen snapshots", (t) => {
  const root = fixture(t, {
    ".gitignore": "docs/superpowers/\nnode_modules/\n",
    "README.md": "# Docs\n",
    "docs/new.md": "# New\n",
    "docs/specs/README.md": "# History\n",
    "docs/specs/old.md": "# Old\n",
    "docs/plans/old.md": "# Old\n",
    "docs/releases/v1.0.0.md": "# Frozen\n",
    "docs/releases/unreleased.md": "# Current\n",
    "docs/superpowers/local.md": "# Local\n",
    "node_modules/pkg/README.md": "# Dependency\n",
    "CHANGELOG.md": "# Frozen release log\n",
  });
  execFileSync("git", ["init", "-q"], { cwd: root });
  execFileSync("git", ["add", "README.md"], { cwd: root });
  assert.deepEqual(collectDocumentationFiles(root), [
    "README.md", "docs/new.md", "docs/releases/unreleased.md", "docs/specs/README.md",
  ]);
});
