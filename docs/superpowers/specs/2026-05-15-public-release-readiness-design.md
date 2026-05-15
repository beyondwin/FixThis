# Public Release Readiness and Publish Prep Design

Date: 2026-05-15

## Goal

Make FixThis feel safe and credible for real external users without claiming
artifact publication before it exists.

This work prepares two related but separate outcomes:

1. A trustworthy GitHub source release path that an individual open-source
   maintainer can operate today.
2. A publish-ready shape for future Maven Central, Gradle Plugin Portal, and
   MCP Registry publication, guarded so no workflow accidentally publishes
   artifacts without explicit credentials and approval.

The project remains honest in all public docs: FixThis is usable from source
and through local/composite build wiring, but external Gradle artifacts are not
published yet.

## Context

FixThis is not a normal standalone MCP server. A typical personal MCP server
can often be trusted with a README, a package install path (`npx`, `uvx`,
PyPI, or Docker), and an MCP client config snippet. FixThis has a broader
distribution surface:

- Android debug runtime (`:fixthis-compose-sidekick`);
- Gradle plugin (`:fixthis-gradle-plugin`);
- desktop CLI (`:fixthis-cli`);
- MCP stdio server and feedback console (`:fixthis-mcp`);
- agent bootstrap scripts and docs.

That means MCP-style packaging alone is not enough. Android/Gradle users will
expect stable coordinates, versioning, release notes, compatibility claims,
and a clear distinction between debug-only runtime code and production app
builds.

The MCP Registry also separates metadata publication from package hosting. It
can point to packages or remote servers, but the installable package must exist
elsewhere. For FixThis, the future package path is primarily Gradle artifacts
plus optionally an MCP package/registry entry for discovery.

## Non-Goals

- Do not publish to Maven Central, Gradle Plugin Portal, npm, PyPI, Docker, or
  the MCP Registry in this phase.
- Do not add real signing keys, tokens, or personal credentials to the repo.
- Do not claim one-line external Gradle install until artifacts are actually
  available from public registries.
- Do not change persisted MCP JSON field names or bridge protocol semantics.
- Do not make release builds a supported sidekick target. FixThis remains
  debug-only.

## User-Facing Trust Model

External users should be able to answer four questions from the repo without
guessing:

1. Can I try this today?
   - Yes, from the sample app and from source/composite-build wiring.
2. Is this safe for my production app?
   - The sidekick is debug-only, local-first, and not supported in release
     builds.
3. What is not published yet?
   - Maven Central and Gradle Plugin Portal artifacts are not available yet.
4. What evidence backs this release?
   - CI, local release gates, smoke commands, changelog, release notes,
     compatibility docs, security docs, and explicit known limitations.

## Proposed Shape

### 1. Release Trust Track

Improve the source-release path so `v0.2.0` or a similar next tag can be cut
with a credible public trail.

Public docs should converge on one message:

- `README.md` states what works today and what remains unpublished.
- `docs/contributing/release-readiness.md` is the readiness dashboard.
- `docs/contributing/release-process.md` is the operator playbook.
- `docs/releases/unreleased.md` is the release-candidate-facing summary for
  current `main`.
- `CHANGELOG.md` remains the chronological source of truth.

The release-readiness page should be rewritten from a flat checklist into a
dashboard with these sections:

- current release status;
- supported install paths today;
- blocked install paths;
- evidence required before tagging;
- artifact-publication prerequisites;
- owner-supplied secrets and accounts;
- explicit "do not claim published until verified" rules.

### 2. Artifact Publish Prep Track

Prepare the repo for Gradle artifact publication without enabling accidental
publication.

The design should define the future public coordinates before build files
start to depend on them:

| Surface | Future coordinate / id | Notes |
| --- | --- | --- |
| Gradle plugin | `io.beyondwin.fixthis.compose` | Existing plugin id. Published through Gradle Plugin Portal later. |
| Sidekick artifact | `io.github.beyondwin:fixthis-compose-sidekick` | Debug-only dependency for external apps. |
| Core artifact | `io.github.beyondwin:fixthis-compose-core` | Publish only if needed by sidekick consumers or plugin internals. Keep independent. |
| CLI distribution | no Maven requirement for first external Gradle release | Current local installDist path is enough until binary packaging is chosen. |
| MCP server | no npm/PyPI claim yet | Future discovery can use MCP Registry metadata once an install method exists. |

Implementation should add only safe preparation:

- document the coordinates and module ownership;
- add `version`/`group` policy where appropriate only if it does not imply a
  completed publish;
- optionally add local `publishToMavenLocal`/dry-run validation tasks;
- document required secrets and manual gates;
- keep real remote publish tasks disabled or manual-only until owner
  credentials are present.

If a GitHub Actions workflow is added, it must be `workflow_dispatch` only,
require explicit inputs such as `release_version`, and fail fast unless all
required secrets are present. It must not run on `push`, `pull_request`, or
tag creation in this phase.

### 3. Release Gate Automation

Add a small local script that checks release-readiness drift. This is not a
replacement for tests; it prevents misleading public release text.

Recommended script:

```bash
node scripts/check-release-readiness.mjs
```

Initial checks:

- README still states Maven Central / Gradle Plugin Portal artifacts are not
  published.
- `docs/contributing/release-readiness.md` links to release process,
  changelog, security docs, privacy docs, and compatibility docs.
- `docs/contributing/release-process.md` includes source-release and
  future-artifact-release paths separately.
- `docs/releases/unreleased.md` exists and describes current `main` as
  unreleased.
- No public docs contain a misleading install snippet that uses published
  Gradle coordinates without also marking it as future/not-yet-published.

The script should be deterministic, dependency-light, and wired into
`CONTRIBUTING.md` and CI only after it is stable.

## Documentation Contract

Use these terms consistently:

- **source release**: GitHub tag + GitHub Release + source/composite-build
  usage.
- **artifact release**: public Maven Central and/or Gradle Plugin Portal
  artifacts.
- **publish-ready**: build/docs/CI are prepared but no public artifact exists.
- **published**: artifact is visible in the relevant public registry and
  install instructions have been verified.

Avoid ambiguous phrases such as "available on Gradle" or "install from Maven"
until the artifact exists.

## Safety and Credentials

No secret values enter the repository. Documentation may name expected secret
keys, for example:

- `MAVEN_CENTRAL_USERNAME`;
- `MAVEN_CENTRAL_PASSWORD` or token equivalent;
- `SIGNING_KEY`;
- `SIGNING_PASSWORD`;
- `GRADLE_PUBLISH_KEY`;
- `GRADLE_PUBLISH_SECRET`.

All publish instructions must include a manual verification step before public
docs are changed from "not published" to "published".

## Testing

Design/spec phase validation:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Implementation phase validation should add:

```bash
node scripts/check-release-readiness.mjs
bash -n scripts/*.sh
./gradlew help --warning-mode all --no-daemon
```

If Gradle publishing prep changes are added:

```bash
./gradlew publishToMavenLocal --dry-run
./gradlew :fixthis-gradle-plugin:validatePlugins
```

Full release-candidate validation remains the longer checklist in
`docs/contributing/release-process.md`.

## Risks

| Risk | Mitigation |
| --- | --- |
| Docs imply artifacts are published before they exist. | Add release-readiness drift checks and keep README status explicit. |
| Publish workflow accidentally runs from CI. | Use manual `workflow_dispatch`, required inputs, and fail-fast secret checks only. |
| Scope expands into full packaging for CLI/MCP/npm. | Treat MCP Registry/npm/PyPI as future discovery work after Gradle publication path is stable. |
| Credentials differ from documented names. | Keep names documented as defaults and allow owner override before enabling real publish. |
| Gradle plugin and sidekick versions drift. | Establish one version source of truth before enabling remote publication. |

## Acceptance Criteria

- A reader can tell that FixThis is usable today from source but not yet
  published as external Gradle artifacts.
- The release readiness docs define exactly what must happen before artifact
  publication.
- Future Gradle coordinates and module responsibilities are documented.
- Any publish workflow or task added in implementation is safe-by-default and
  cannot publish without explicit maintainer credentials.
- A release-readiness check catches misleading public docs before a release.
- The implementation plan can be executed in small commits: docs first,
  readiness script second, optional Gradle publish dry-run third.
