# FixThis Full Project Rename Design

Date: 2026-05-07
Status: approved design, pending implementation plan

## Purpose

Rename the whole project from PointPatch to FixThis. This is not limited to the
sample app. The product name, module names, Gradle contracts, Kotlin packages,
CLI command names, MCP tool names, resource names, local storage paths, runtime
socket names, tests, fixtures, and current documentation should all move to the
FixThis brand.

This is a breaking rename. Backward compatibility with the old PointPatch public
contracts is not part of this phase.

## Approved Direction

Use a complete FixThis rename with `io.beyondwin.fixthis` as the new package
prefix.

The project already renamed the sample app to FixThis. This design extends that
decision across the repository so the sample app, SDK/runtime, CLI, MCP server,
Gradle plugin, and documentation use one brand and one naming system.

## Naming Contract

Apply these rename rules consistently:

```text
PointPatch                         -> FixThis
pointpatch                         -> fixthis
pointPatch                         -> fixThis

io.github.pointpatch.*             -> io.beyondwin.fixthis.*
io.github.pointpatch.compose       -> io.beyondwin.fixthis.compose

:pointpatch-compose-core           -> :fixthis-compose-core
:pointpatch-compose-overlay        -> :fixthis-compose-overlay
:pointpatch-compose-sidekick       -> :fixthis-compose-sidekick
:pointpatch-gradle-plugin          -> :fixthis-gradle-plugin
:pointpatch-cli                    -> :fixthis-cli
:pointpatch-mcp                    -> :fixthis-mcp

pointpatch CLI binary              -> fixthis
pointpatch-cli generated script    -> removed
pointpatch-mcp binary              -> fixthis-mcp

pointpatch_* MCP tools             -> fixthis_*
pointpatch:// resources            -> fixthis://

.pointpatch/                       -> .fixthis/
files/pointpatch/                  -> files/fixthis/
cache/pointpatch/                  -> cache/fixthis/
localabstract:pointpatch_<package> -> localabstract:fixthis_<package>
```

Code identifiers should follow the same contract. Examples:

```text
PointPatch                         -> FixThis
PointPatchInitializer              -> FixThisInitializer
PointPatchTools                    -> FixThisTools
PointPatchJsonFormatter            -> FixThisJsonFormatter
pointPatchJson                     -> fixThisJson
GeneratePointPatchSourceIndexTask  -> GenerateFixThisSourceIndexTask
```

## Scope

Implementation must rename active code and current product documentation.

In scope:

- Root Gradle project name.
- Included Gradle build and module directories.
- Gradle project paths and project dependencies.
- Android library namespaces.
- Gradle plugin id.
- Kotlin source package declarations, imports, source paths, and test paths.
- Public Kotlin classes, objects, functions, and constants that contain the old
  brand.
- CLI application name, generated distribution paths, setup output, help text,
  and docs examples.
- MCP executable name, tool names, resource URIs, server naming, setup JSON, and
  tests.
- Runtime bridge socket names.
- Generated source-index asset paths and build-info asset paths.
- App-private runtime paths under `files/` and `cache/`.
- Project-local paths under `.fixthis/`.
- README, current docs, troubleshooting docs, MCP docs, and active architecture
  overview docs.
- Tests and fixtures that assert the current product contract.

Out of scope:

- Automatic migration of old `.pointpatch/` local debug sessions or artifacts.
- Compatibility aliases for the old `pointpatch` CLI binary.
- A secondary `fixthis-cli` launcher script.
- Compatibility aliases for old `pointpatch_*` MCP tools or `pointpatch://`
  resources.
- Keeping the old Gradle plugin id as an alias.
- Rewriting old brainstorming specs as if they were new requirements. Historical
  specs may keep old names when they describe past decisions, but current docs
  should use FixThis or include a short migration note.

## Execution Order

### 1. Rename the Gradle Graph

Start with the build graph so later compiler failures are about source imports,
not missing projects.

- Rename directories from `pointpatch-*` to `fixthis-*`.
- Update `settings.gradle.kts`:
  - root project name to `FixThis`.
  - included build from `pointpatch-gradle-plugin` to `fixthis-gradle-plugin`.
  - included projects from `:pointpatch-*` to `:fixthis-*`.
- Update all `project(":pointpatch-*")` references to `project(":fixthis-*")`.
- Update plugin build root project name from `pointpatch-gradle-plugin-build` to
  `fixthis-gradle-plugin-build`.

### 2. Rename Packages and Public Identifiers

Move source packages after the Gradle graph resolves.

- Move `src/main/kotlin/io/github/pointpatch/...` to
  `src/main/kotlin/io/beyondwin/fixthis/...`.
- Move matching test and androidTest paths.
- Replace package declarations and imports.
- Rename public identifiers from `PointPatch` to `FixThis`.
- Rename lower camel identifiers from `pointPatch` to `fixThis`.
- Update Android library namespaces to `io.beyondwin.fixthis.compose.*`.

The sample app already uses `io.beyondwin.fixthis.sample`; keep that package and
application id.

### 3. Rename Runtime and Persistence Contracts

After packages compile, update runtime strings and generated assets.

- `.pointpatch/` becomes `.fixthis/`.
- `files/pointpatch/` becomes `files/fixthis/`.
- `cache/pointpatch/` becomes `cache/fixthis/`.
- `localabstract:pointpatch_<package>` becomes
  `localabstract:fixthis_<package>`.
- Generated assets under `assets/pointpatch/` become `assets/fixthis/`.
- Error messages and validation text should say FixThis.

No automatic data migration is required. Existing local debug artifacts are
discardable.

### 4. Rename CLI and MCP Contracts

Update user-facing command contracts after runtime names are stable.

- CLI distribution application name becomes `fixthis`.
- Remove the old secondary `pointpatch-cli` generated launcher behavior instead
  of creating a new secondary `fixthis-cli` launcher.
- MCP distribution application name becomes `fixthis-mcp`.
- Command examples use `fixthis`.
- MCP tools use `fixthis_` prefixes.
- MCP resources use `fixthis://` URIs.
- Setup JSON names the server and command as FixThis.
- Tests should assert the new names.

Do not keep compatibility wrappers for the old names in this phase.

### 5. Update Current Docs and Fixtures

Finally, update documentation and fixtures.

- README should introduce `FixThis for Android Compose`.
- Current docs should use FixThis for product, CLI, MCP, plugin, and storage
  paths.
- Add a short migration note that the project was previously named PointPatch
  and that old public contracts are not preserved.
- Test fixtures should use `io.beyondwin.fixthis.sample` or
  `io.beyondwin.fixthis.*` as appropriate.
- Historical specs can keep old wording if they are clearly historical and not
  active instructions.

## Compatibility Policy

This rename is intentionally breaking.

The implementation should not preserve:

- `pointpatch` CLI binary.
- `pointpatch-cli` generated script.
- any replacement secondary `fixthis-cli` generated script.
- `pointpatch-mcp` executable.
- `io.github.pointpatch.compose` Gradle plugin id.
- `io.github.pointpatch.*` Kotlin packages.
- `pointpatch_*` MCP tool names.
- `pointpatch://` MCP resources.
- `.pointpatch/` local storage path.
- `files/pointpatch/` and `cache/pointpatch/` app-private paths.
- `localabstract:pointpatch_<package>` bridge socket name.

The project should document that users must update scripts, MCP client
configuration, Gradle plugin ids, imports, package references, and local storage
expectations to the new FixThis names.

## Testing

Minimum verification:

```bash
./gradlew test
./gradlew :app:assembleDebug
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis --help
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample
```

Additional verification when an Android device or emulator is available:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Final rename audit:

```bash
rg -n -i "pointpatch"
```

Remaining matches must be reviewed and classified. Active code, current docs,
current commands, and current tests should not use PointPatch names. Acceptable
remaining matches are limited to explicit migration notes or clearly historical
design/spec records.

## Acceptance Criteria

- The root project is named `FixThis`.
- Repository modules use `fixthis-*` names.
- Kotlin packages use `io.beyondwin.fixthis.*`.
- Library Android namespaces use `io.beyondwin.fixthis.compose.*`.
- The Gradle plugin id is `io.beyondwin.fixthis.compose`.
- CLI command examples and generated binaries use `fixthis`.
- MCP tools use `fixthis_*`.
- MCP resources use `fixthis://`.
- Runtime storage uses `.fixthis/`, `files/fixthis/`, and `cache/fixthis/`.
- Runtime bridge sockets use `fixthis_<package>`.
- Generated source-index assets live under `assets/fixthis/`.
- Current docs present the product as FixThis and include a short migration note.
- Tests and fixtures assert FixThis contracts.
- Minimum verification commands pass, or any unavailable Android-device checks
  are explicitly reported.
- `rg -n -i "pointpatch"` has no unclassified active-code or current-doc hits.
