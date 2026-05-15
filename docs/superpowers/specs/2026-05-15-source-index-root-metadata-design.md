# Source Index Root Metadata Design

## Context

FixThis compact handoff currently renders source candidates such as:

```text
screens/HomeScreen.kt:44  conf=high  ... stale: file not found on host
```

The underlying `SourceIndexEntry.file` value is generated relative to the Android Gradle project directory:

```text
src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt
```

The MCP server, however, verifies host source files relative to the MCP `projectRoot`, which is the repository root:

```text
/Users/kws/source/android/FixThis
```

For the bundled sample app, the real file is:

```text
sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt
```

That mismatch causes false `file not found on host` warnings, false `projectRoot may be misconfigured` status output, and Copy Prompt paths that are harder for agents to open.

## Goals

- Preserve the existing `file` JSON field as a compatibility contract.
- Add enough location metadata to resolve source candidates in repository-root MCP sessions.
- Render Copy Prompt paths that agents can open directly from the workspace.
- Keep old APKs and old persisted sessions readable.
- Avoid hard-coding the bundled `sample/` module name.

## Non-Goals

- Do not rename persisted fields such as `sourceCandidates`, `file`, `line`, or `staleReason`.
- Do not require release builds or non-debug runtime support.
- Do not make source candidates guaranteed mappings; they remain best-effort hints.
- Do not require agents to understand Gradle internals to use a handoff.

## Prior Art

Other source-location systems separate the path from the base used to resolve it:

- Source maps use `sourceRoot` plus `sources[]`.
- SARIF uses artifact URIs plus `uriBaseId`.
- Gradle and IntelliJ model files relative to a module/project but also carry the owning project path.
- LSP ultimately resolves locations into workspace URIs.

The common pattern is: do not store an ambiguous relative path without its base.

## Recommended Design

Add additive root metadata to the generated source index and use a shared MCP resolver to turn candidates into host-resolvable paths.

The canonical model becomes:

```json
{
  "schemaVersion": "1.1",
  "sourceRoot": {
    "kind": "gradle-project",
    "gradlePath": ":app",
    "projectDir": "sample"
  },
  "entries": [
    {
      "file": "src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt",
      "repoFile": "sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt",
      "line": 44
    }
  ]
}
```

`file` remains module-relative. `repoFile` is the repository-root-relative path agents should edit when available. `sourceRoot.projectDir` defines how to resolve legacy module-relative `file` values against the MCP repository root.

## Data Model

Add nullable/defaulted fields so older readers ignore them and older assets decode cleanly:

- `SourceIndex.sourceRoot: SourceRoot? = null`
- `SourceIndexEntry.repoFile: String? = null`

`SourceRoot` fields:

- `kind: String = "gradle-project"`
- `gradlePath: String?`
- `projectDir: String?`

Gradle plugin asset mirrors the same fields:

- `SourceIndexAsset.sourceRoot`
- `SourceIndexEntryAsset.repoFile`
- `SourceIndexEntryBuilder.repoFile`

`schemaVersion` will move from `"1.0"` to `"1.1"` because the change is additive.

## Generation

`FixThisGradlePlugin` already has both the Gradle `Project` and root project. Configure `GenerateFixThisSourceIndexTask` with:

- `projectPath = project.path`
- `rootProjectDirectory = project.rootProject.layout.projectDirectory`
- `projectDirectory = project.layout.projectDirectory`

`SourceIndexGenerator` should compute:

- `file`: unchanged, relative to `projectDirectory`
- `repoFile`: source file relative to `rootProjectDirectory`
- `sourceRoot.projectDir`: `projectDirectory` relative to `rootProjectDirectory`
- `sourceRoot.gradlePath`: `project.path`

For single-module root projects, `projectDir` is `""`; `repoFile` equals `file`.

## MCP Resolution

Create one shared host resolver, for example `HostSourcePathResolver`, and use it everywhere MCP needs to open or stat source files.

Resolution order:

1. `entry.repoFile` when available.
2. `sourceIndex.sourceRoot.projectDir + entry.file`.
3. Existing `projectRoot + entry.file` behavior.
4. Compatibility suffix fallback only when exactly one file under `projectRoot` ends with `entry.file`.

Reject resolved paths that escape `projectRoot`.

The resolver should return structured results:

- resolved `File`
- display path relative to `projectRoot`
- reason used, such as `repoFile`, `sourceRoot`, `legacyRoot`, or `uniqueSuffix`
- failure reason when unresolved

## Consumers

Update these consumers to use the same resolver:

- `SourceCandidateStalenessChecker`: read live source through the resolver before comparing excerpts.
- `HostSourceFreshnessProbe`: count existing/newer indexed files through the resolver, so `fixthis_status` no longer reports false project-root misconfiguration.
- Compact handoff rendering: display host-resolvable paths when available. With the sample app, Copy Prompt should show:

```text
sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt:44
```

The existing compact `Source root:` optimization can still shorten repeated paths, but it should be based on display paths after host resolution, not raw module-relative `file` values.

## Compatibility

Old APKs with only `file`:

- Try `projectRoot + file`.
- Try unique suffix fallback.
- If unresolved, keep existing stale behavior.

Old persisted sessions:

- Continue to deserialize because `repoFile` and `sourceRoot` are nullable/defaulted.
- Existing `sourceCandidates.file` values are unchanged.

New APKs with new MCP:

- Use `repoFile` directly.

New APKs with old MCP:

- Old MCP ignores unknown source-index fields and keeps current behavior.

## Error Handling

Keep current stale reasons, but make unresolved-path diagnostics more precise where they are user-facing:

- `file not found on host` for true misses.
- `file not found on host; sourceRoot unresolved` when root metadata exists but cannot be resolved.
- `file not found on host; multiple suffix matches` when fallback is ambiguous.

For compact handoff, avoid claiming the whole source index is stale when the actual problem is host path resolution. Use the existing target warning only when excerpt checks or freshness checks indicate a real stale index.

## Testing

Add focused tests:

- Gradle plugin generates `repoFile` and `sourceRoot` for a module under `sample/`.
- Gradle plugin keeps root-module paths stable.
- Core model deserializes v1 source indexes without new fields.
- MCP resolver resolves by `repoFile`, then `sourceRoot + file`, then legacy root path.
- MCP resolver rejects path escapes.
- MCP resolver reports ambiguity when two modules contain the same suffix.
- `SourceCandidateStalenessChecker` clears stale for the sample module path.
- `HostSourceFreshnessProbe` does not report `projectRoot may be misconfigured` when files exist under a module directory.
- Compact handoff renders `sample/src/main/...` for the bundled sample.

## Rollout

1. Add additive source-index models in core and Gradle asset models.
2. Update Gradle source-index generation to populate `sourceRoot` and `repoFile`.
3. Add MCP `HostSourcePathResolver`.
4. Move staleness checker and freshness probe to the resolver.
5. Update compact handoff display paths.
6. Update output schema and troubleshooting docs.
7. Rebuild/install sample, capture feedback, and verify Copy Prompt no longer emits false `file not found on host`.

## Decisions

- Use `projectDir = ""` for root projects and render no prefix.
- Keep `sourceRoot.kind` as a string rather than an enum initially to leave room for future non-Gradle roots.
