# FixThis Full Project Rename Design

Date: 2026-05-07
Status: approved design, pending implementation plan

## Purpose

Rename the whole project from FixThis to FixThis. This is not limited to the
sample app. The product name, module names, Gradle contracts, Kotlin packages,
CLI command names, MCP tool names, resource names, local storage paths, runtime
socket names, tests, fixtures, and current documentation should all move to the
FixThis brand.

This is a breaking rename. Backward compatibility with the old FixThis public
contracts is not part of this phase.

## Approved Direction

Use a complete FixThis rename with `io.github.beyondwin.fixthis` as the new package
prefix.

The project already renamed the sample app to FixThis. This design extends that
decision across the repository so the sample app, SDK/runtime, CLI, MCP server,
Gradle plugin, and documentation use one brand and one naming system.

## Naming Contract

Apply these rename rules consistently:

```text
FixThis                         -> FixThis
fixthis                         -> fixthis
fixThis                         -> fixThis

io.github.beyondwin.fixthis.*             -> io.github.beyondwin.fixthis.*
io.github.beyondwin.fixthis.compose       -> io.github.beyondwin.fixthis.compose

:fixthis-compose-core           -> :fixthis-compose-core
:fixthis-compose-overlay        -> :fixthis-compose-overlay
:fixthis-compose-sidekick       -> :fixthis-compose-sidekick
:fixthis-gradle-plugin          -> :fixthis-gradle-plugin
:fixthis-cli                    -> :fixthis-cli
:fixthis-mcp                    -> :fixthis-mcp

fixthis CLI binary              -> fixthis
fixthis-cli generated script    -> removed
fixthis-mcp binary              -> fixthis-mcp

fixthis_* MCP tools             -> fixthis_*
fixthis:// resources            -> fixthis://

.fixthis/                       -> .fixthis/
files/fixthis/                  -> files/fixthis/
cache/fixthis/                  -> cache/fixthis/
localabstract:fixthis_<package> -> localabstract:fixthis_<package>
```

Code identifiers should follow the same contract. Examples:

```text
FixThis                         -> FixThis
FixThisInitializer              -> FixThisInitializer
FixThisTools                    -> FixThisTools
FixThisJsonFormatter            -> FixThisJsonFormatter
fixThisJson                     -> fixThisJson
GenerateFixThisSourceIndexTask  -> GenerateFixThisSourceIndexTask
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

- Automatic migration of old `.fixthis/` local debug sessions or artifacts.
- Compatibility aliases for the old `fixthis` CLI binary.
- A secondary `fixthis-cli` launcher script.
- Compatibility aliases for old `fixthis_*` MCP tools or `fixthis://`
  resources.
- Keeping the old Gradle plugin id as an alias.
- Rewriting old brainstorming specs as if they were new requirements. Historical
  specs may keep old names when they describe past decisions, but current docs
  should use FixThis or include a short migration note.

## Execution Order

### 1. Rename the Gradle Graph

Start with the build graph so later compiler failures are about source imports,
not missing projects.

- Rename directories from `fixthis-*` to `fixthis-*`.
- Update `settings.gradle.kts`:
  - root project name to `FixThis`.
  - included build from `fixthis-gradle-plugin` to `fixthis-gradle-plugin`.
  - included projects from `:fixthis-*` to `:fixthis-*`.
- Update all `project(":fixthis-*")` references to `project(":fixthis-*")`.
- Update plugin build root project name from `fixthis-gradle-plugin-build` to
  `fixthis-gradle-plugin-build`.

### 2. Rename Packages and Public Identifiers

Move source packages after the Gradle graph resolves.

- Move `src/main/kotlin/io/github/fixthis/...` to
  `src/main/kotlin/io/github/beyondwin/fixthis/...`.
- Move matching test and androidTest paths.
- Replace package declarations and imports.
- Rename public identifiers from `FixThis` to `FixThis`.
- Rename lower camel identifiers from `fixThis` to `fixThis`.
- Update Android library namespaces to `io.github.beyondwin.fixthis.compose.*`.

The sample app already uses `io.github.beyondwin.fixthis.sample`; keep that package and
application id.

### 3. Rename Runtime and Persistence Contracts

After packages compile, update runtime strings and generated assets.

- `.fixthis/` becomes `.fixthis/`.
- `files/fixthis/` becomes `files/fixthis/`.
- `cache/fixthis/` becomes `cache/fixthis/`.
- `localabstract:fixthis_<package>` becomes
  `localabstract:fixthis_<package>`.
- Generated assets under `assets/fixthis/` become `assets/fixthis/`.
- Error messages and validation text should say FixThis.

No automatic data migration is required. Existing local debug artifacts are
discardable.

### 4. Rename CLI and MCP Contracts

Update user-facing command contracts after runtime names are stable.

- CLI distribution application name becomes `fixthis`.
- Remove the old secondary `fixthis-cli` generated launcher behavior instead
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
- Add a short migration note that the project was previously named FixThis
  and that old public contracts are not preserved.
- Test fixtures should use `io.github.beyondwin.fixthis.sample` or
  `io.github.beyondwin.fixthis.*` as appropriate.
- Historical specs can keep old wording if they are clearly historical and not
  active instructions.

## Compatibility Policy

This rename is intentionally breaking.

The implementation should not preserve:

- `fixthis` CLI binary.
- `fixthis-cli` generated script.
- any replacement secondary `fixthis-cli` generated script.
- `fixthis-mcp` executable.
- `io.github.beyondwin.fixthis.compose` Gradle plugin id.
- `io.github.beyondwin.fixthis.*` Kotlin packages.
- `fixthis_*` MCP tool names.
- `fixthis://` MCP resources.
- `.fixthis/` local storage path.
- `files/fixthis/` and `cache/fixthis/` app-private paths.
- `localabstract:fixthis_<package>` bridge socket name.

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
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.github.beyondwin.fixthis.sample
```

Additional verification when an Android device or emulator is available:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Final rename audit:

```bash
rg -n -i "fixthis"
```

Remaining matches must be reviewed and classified. Active code, current docs,
current commands, and current tests should not use FixThis names. Acceptable
remaining matches are limited to explicit migration notes or clearly historical
design/spec records.

## Acceptance Criteria

- The root project is named `FixThis`.
- Repository modules use `fixthis-*` names.
- Kotlin packages use `io.github.beyondwin.fixthis.*`.
- Library Android namespaces use `io.github.beyondwin.fixthis.compose.*`.
- The Gradle plugin id is `io.github.beyondwin.fixthis.compose`.
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
- `rg -n -i "fixthis"` has no unclassified active-code or current-doc hits.
