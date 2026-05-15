# Public Release Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare FixThis for credible public source releases and future Gradle artifact publication without claiming artifacts are published.

**Architecture:** Keep release trust as docs plus deterministic local checks. Public docs remain the source of truth for what is usable today; `scripts/check-release-readiness.mjs` enforces that they do not drift into misleading published-artifact claims. Gradle publish prep is documented and validated only through safe local/dry-run commands in this phase.

**Tech Stack:** Markdown docs, Node.js 20 script using only built-in modules, existing Gradle/Kotlin build, GitHub Actions documentation only unless a guarded manual workflow is added later.

---

## File Structure

- Modify `README.md`: tighten the Status/Roadmap wording around source releases, not-yet-published Gradle artifacts, and where users can verify release readiness.
- Modify `docs/contributing/release-readiness.md`: convert from a short checklist into the canonical readiness dashboard.
- Modify `docs/contributing/release-process.md`: split source-release steps from future artifact-release steps and document safe manual gates.
- Modify `docs/releases/unreleased.md`: make current `main` read like a release-candidate summary, still clearly unreleased.
- Modify `CONTRIBUTING.md`: add the release-readiness check to local verification docs.
- Create `scripts/check-release-readiness.mjs`: deterministic drift checker for public release claims and required cross-links.
- Modify `.github/workflows/ci.yml`: add the release-readiness checker to CI after it passes locally.
- Optional modify `docs/reference/compatibility.md`: only if release-readiness links need a clearer compatibility owner page. Do not duplicate the compatibility matrix.

## Guardrails

- Do not publish to Maven Central, Gradle Plugin Portal, npm, PyPI, Docker, or MCP Registry.
- Do not add secrets or credential values.
- Do not claim published Gradle coordinates in user-facing install instructions.
- Do not change bridge protocol or persisted MCP JSON field names.
- Do not touch unrelated dirty files in the root checkout.

---

### Task 1: Rewrite Release Readiness Dashboard

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `README.md`

- [ ] **Step 1: Replace `docs/contributing/release-readiness.md` with the dashboard**

Use this content:

```markdown
# Release Readiness

FixThis is usable today as a GitHub source release and from `main` through the
sample app, local MCP bootstrap, and Gradle composite-build wiring. Public
Gradle artifacts are not published yet.

This page is the release dashboard. It separates what users can do today from
what must be true before the README can advertise Maven Central or Gradle
Plugin Portal installation.

## Current Status

| Surface | Status | User impact |
| --- | --- | --- |
| GitHub source release | Available from `v0.1.0` | Users can inspect tagged source and release notes. |
| Current `main` | Unreleased hardening after `v0.1.0` | Users can try latest docs/code from source. |
| Sample app | Available | Users can verify the workflow before touching their app. |
| Claude Code / Codex MCP bootstrap | Available from source | `./scripts/bootstrap-mcp.sh --sample` configures local MCP for the sample. |
| External Gradle artifacts | Not published | External apps must use source/composite-build wiring for now. |
| MCP Registry entry | Not published | Discovery remains through the GitHub repository and docs. |

## Supported Install Paths Today

- Clone the repository and run the sample app.
- Use `./scripts/bootstrap-mcp.sh --sample` to register the local MCP server
  with Claude Code or Codex.
- Use Gradle composite-build or local repository wiring for an external debug
  Compose app.
- Use **Copy Prompt** for chat-style agents without MCP.

## Not Published Yet

The following install paths are intentionally not advertised as ready:

- Maven Central dependency for `io.github.beyondwin:fixthis-compose-sidekick`.
- Gradle Plugin Portal entry for `io.github.beyondwin.fixthis.compose`.
- npm/PyPI/Docker package for the MCP server.
- MCP Registry metadata entry.

Do not change README install instructions to published Gradle coordinates until
the artifacts are visible in their public registries and verified from a clean
consumer project.

## Required Before Next Source Release

- [ ] Full PR checks pass on the release commit.
- [ ] `node scripts/check-doc-consistency.mjs` passes.
- [ ] `node scripts/check-release-readiness.mjs` passes.
- [ ] `git diff --check` passes.
- [ ] `CHANGELOG.md` entries are reviewed and moved under the release heading.
- [ ] `docs/releases/vX.Y.Z.md` exists and matches the changelog summary.
- [ ] `docs/releases/unreleased.md` is reset for the next cycle after tagging.
- [ ] Connected smoke is run on a real device or unlocked emulator, or the
      release notes explicitly say it was not run.
- [ ] Security, privacy, compatibility, and troubleshooting docs still match
      the release claims.

## Required Before External Artifact Release

- [ ] Public group and artifact coordinates are final:
      `io.github.beyondwin:fixthis-compose-sidekick` and
      `io.github.beyondwin:fixthis-compose-core` if core is published.
- [ ] Gradle plugin id remains `io.github.beyondwin.fixthis.compose`.
- [ ] One version source of truth is established for all published modules.
- [ ] `publishToMavenLocal` or equivalent dry-run packaging is verified.
- [ ] Gradle Plugin Portal validation passes for `:fixthis-gradle-plugin`.
- [ ] Maven Central namespace ownership is verified by the maintainer.
- [ ] Gradle Plugin Portal account ownership is verified by the maintainer.
- [ ] Signing and publishing secrets are configured outside the repository.
- [ ] A clean external sample project resolves the published artifacts from
      public registries.
- [ ] README install instructions are updated only after registry verification.

## Future Coordinates

| Surface | Future coordinate / id | Registry |
| --- | --- | --- |
| Gradle plugin | `io.github.beyondwin.fixthis.compose` | Gradle Plugin Portal |
| Compose sidekick | `io.github.beyondwin:fixthis-compose-sidekick` | Maven Central |
| Compose core | `io.github.beyondwin:fixthis-compose-core` | Maven Central, only if needed by consumers |
| MCP server | no package coordinate yet | Future MCP Registry metadata after an install package exists |

## Required Maintainer Secrets

These names document the default expected CI inputs. They are not committed and
can be renamed before real publication is enabled.

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Maven Central publishing identity or token user. |
| `MAVEN_CENTRAL_PASSWORD` | Maven Central publishing password or token secret. |
| `SIGNING_KEY` | ASCII-armored signing key for Maven artifacts. |
| `SIGNING_PASSWORD` | Password for the signing key. |
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portal publish key. |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal publish secret. |

## Evidence Links

- [Release process](release-process.md)
- [Changelog](../../CHANGELOG.md)
- [Unreleased release notes](../releases/unreleased.md)
- [Security model](../../SECURITY.md)
- [Privacy](../reference/privacy.md)
- [Threat model](../reference/threat-model.md)
- [Compatibility](../reference/compatibility.md)
- [CLI reference](../reference/cli.md)
- [MCP tools](../reference/mcp-tools.md)

## Release Claim Rules

- Use **source release** only for GitHub tags and GitHub Releases.
- Use **publish-ready** only when build, docs, and checks are prepared but no
  public artifact exists.
- Use **artifact release** only after Maven Central or Gradle Plugin Portal
  artifacts are public and verified.
- Never use "published", "install from Maven", or "available on Gradle" for
  FixThis artifacts until the relevant registry listing is live.
```

- [ ] **Step 2: Update README Status wording**

In `README.md`, keep the existing Status section but make the first paragraph
link to release readiness as the verification source. Use this replacement for
the Status block:

```markdown
## Status

> ⚠️ **Source release available; Gradle artifacts not published.** FixThis has
> GitHub Releases for source snapshots, starting with
> [v0.1.0](docs/releases/v0.1.0.md), and current `main` can be tried from
> source. It is not yet on Maven Central or the Gradle Plugin Portal. External
> projects must wire this repository via Gradle composite build (`includeBuild`)
> until artifacts are released.
>
> The live readiness dashboard is
> [Release readiness](docs/contributing/release-readiness.md). It lists the
> supported install paths today, future artifact coordinates, and the checks
> required before public artifact instructions can replace source/composite
> setup.
>
> Current `main` also contains unreleased hardening after v0.1.0. See
> [Unreleased changes since v0.1.0](docs/releases/unreleased.md) and
> [`CHANGELOG.md`](CHANGELOG.md#unreleased) before cutting the next tag.
```

- [ ] **Step 3: Run markdown link consistency**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected:

```text
All doc-consistency rules pass.
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/contributing/release-readiness.md
git commit -m "docs: clarify release readiness status"
```

---

### Task 2: Split Source Release and Future Artifact Release Process

**Files:**
- Modify: `docs/contributing/release-process.md`
- Modify: `docs/releases/unreleased.md`

- [ ] **Step 1: Update the release process introduction**

In `docs/contributing/release-process.md`, replace the opening paragraphs
through `## Versioning` with:

```markdown
# Release Process

How to cut a FixThis release.

FixThis currently supports **source releases**: a commit on `main`, an
annotated `vX.Y.Z` tag, and a GitHub Release whose notes point to the matching
CHANGELOG section. Maven Central and Gradle Plugin Portal publication are
future **artifact releases** and must stay behind explicit maintainer approval
until the readiness checklist is complete.

See [`release-readiness.md`](release-readiness.md) before changing public
install instructions.

## Release Types

| Type | What ships | Status |
| --- | --- | --- |
| Source release | Git tag, GitHub Release, release notes, source/composite-build usage | Supported today |
| Artifact release | Maven Central and/or Gradle Plugin Portal artifacts | Publish-ready prep only |
| MCP Registry release | Registry metadata pointing to a public package or remote server | Future work after an install package exists |

## Versioning
```

- [ ] **Step 2: Replace `## Cut a release` with source-release steps**

Find `## Cut a release` and replace that section up to `## Post-release` with:

````markdown
## Cut a Source Release

1. Move CHANGELOG `Unreleased` entries under a new dated heading:

   ```markdown
   ## [0.2.0] - 2026-MM-DD

   ### Added
   - Release readiness documentation and validation.
   ```

   Leave a fresh empty `## Unreleased` block at the top.

2. Create the matching human release note under `docs/releases/`:

   ```bash
   cp docs/releases/unreleased.md docs/releases/v0.2.0.md
   ```

   Edit the copied file so it describes the tagged release, not current `main`.

3. Run the release-readiness checks:

   ```bash
   node scripts/check-doc-consistency.mjs
   node scripts/check-release-readiness.mjs
   git diff --check
   ```

4. Commit:

   ```bash
   git add CHANGELOG.md docs/releases README.md docs/contributing
   git commit -m "release: 0.2.0"
   ```

5. Tag:

   ```bash
   git tag -a v0.2.0 -m "FixThis 0.2.0"
   git push origin main v0.2.0
   ```

6. Create the GitHub Release from the tag:

   ```bash
   gh release create v0.2.0 --title "FixThis v0.2.0" --notes-file docs/releases/v0.2.0.md
   ```

## Future Artifact Release Gate

Do not run this section until `release-readiness.md` marks external artifact
release prerequisites complete.

Before changing README install instructions to public Gradle coordinates:

1. Verify Maven Central namespace ownership for `io.github.beyondwin.fixthis`.
2. Verify Gradle Plugin Portal ownership for `io.github.beyondwin.fixthis.compose`.
3. Configure signing and publishing secrets outside the repository.
4. Run local/dry-run packaging validation:

   ```bash
   ./gradlew publishToMavenLocal --dry-run
   ./gradlew :fixthis-gradle-plugin:validatePlugins
   ```

5. Publish from an explicitly approved manual workflow or local maintainer
   machine.
6. Verify from a clean external consumer project that:

   ```kotlin
   plugins {
       id("io.github.beyondwin.fixthis.compose") version "X.Y.Z"
   }

   dependencies {
       debugImplementation("io.github.beyondwin:fixthis-compose-sidekick:X.Y.Z")
   }
   ```

   resolves from public registries.

7. Only after verification, update README and getting-started docs from
   source/composite-build setup to published-artifact setup.
````

- [ ] **Step 3: Update unreleased release note status**

At the top of `docs/releases/unreleased.md`, ensure the file starts with:

```markdown
# Unreleased changes since v0.1.0

This page summarizes current `main`. It is not a tagged release and should not
be used as evidence that Maven Central or Gradle Plugin Portal artifacts are
published.
```

Keep the existing user-facing notes below that introduction.

- [ ] **Step 4: Run checks**

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected:

```text
All doc-consistency rules pass.
```

- [ ] **Step 5: Commit**

```bash
git add docs/contributing/release-process.md docs/releases/unreleased.md
git commit -m "docs: split source and artifact release process"
```

---

### Task 3: Add Release Readiness Drift Checker

**Files:**
- Create: `scripts/check-release-readiness.mjs`
- Modify: `CONTRIBUTING.md`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Create the failing checker skeleton**

Create `scripts/check-release-readiness.mjs`:

```javascript
#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const root = process.cwd();
const failures = [];

function read(file) {
  return fs.readFileSync(path.join(root, file), 'utf8');
}

function pass(rule) {
  console.log(`PASS ${rule}`);
}

function fail(rule, message) {
  failures.push(`FAIL ${rule}: ${message}`);
}

function requireIncludes(rule, file, needle) {
  const text = read(file);
  if (text.includes(needle)) {
    pass(rule);
  } else {
    fail(rule, `${file} must include ${JSON.stringify(needle)}`);
  }
}

function requireRegex(rule, file, regex, description) {
  const text = read(file);
  if (regex.test(text)) {
    pass(rule);
  } else {
    fail(rule, `${file} must include ${description}`);
  }
}

function forbidPublishedGradleClaims(rule, file) {
  const text = read(file);
  const riskyPatterns = [
    /published\s+to\s+Maven\s+Central/i,
    /available\s+on\s+Maven\s+Central/i,
    /available\s+from\s+the\s+Gradle\s+Plugin\s+Portal/i,
    /install\s+from\s+Maven\s+Central/i,
  ];
  const allowedContext =
    /not\s+published/i.test(text) ||
    /future/i.test(text) ||
    /not\s+yet/i.test(text) ||
    /do\s+not/i.test(text);
  const matched = riskyPatterns.find((pattern) => pattern.test(text));
  if (!matched || allowedContext) {
    pass(rule);
  } else {
    fail(rule, `${file} contains an unqualified published-artifact claim matching ${matched}`);
  }
}

requireIncludes(
  'R1.readme-not-published',
  'README.md',
  'It is not yet on Maven Central or the Gradle Plugin Portal',
);
requireIncludes(
  'R2.readiness-release-process',
  'docs/contributing/release-readiness.md',
  '[Release process](release-process.md)',
);
requireIncludes(
  'R3.readiness-security',
  'docs/contributing/release-readiness.md',
  '[Security model](../../SECURITY.md)',
);
requireIncludes(
  'R4.readiness-privacy',
  'docs/contributing/release-readiness.md',
  '[Privacy](../reference/privacy.md)',
);
requireIncludes(
  'R5.readiness-compatibility',
  'docs/contributing/release-readiness.md',
  '[Compatibility](../reference/compatibility.md)',
);
requireRegex(
  'R6.release-process-types',
  'docs/contributing/release-process.md',
  /Source release[\s\S]*Artifact release[\s\S]*MCP Registry release/,
  'separate Source release, Artifact release, and MCP Registry release rows',
);
requireIncludes(
  'R7.unreleased-warning',
  'docs/releases/unreleased.md',
  'It is not a tagged release',
);

for (const file of [
  'README.md',
  'docs/getting-started/add-to-your-app.md',
  'docs/getting-started/connect-your-agent.md',
  'docs/contributing/release-readiness.md',
  'docs/contributing/release-process.md',
]) {
  forbidPublishedGradleClaims(`R8.no-unqualified-published-claims:${file}`, file);
}

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(failure);
  }
  process.exit(1);
}

console.log('\nAll release-readiness rules pass.');
```

- [ ] **Step 2: Run the checker**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected after Tasks 1-2 are complete:

```text
PASS R1.readme-not-published
PASS R2.readiness-release-process
PASS R3.readiness-security
PASS R4.readiness-privacy
PASS R5.readiness-compatibility
PASS R6.release-process-types
PASS R7.unreleased-warning
PASS R8.no-unqualified-published-claims:README.md
PASS R8.no-unqualified-published-claims:docs/getting-started/add-to-your-app.md
PASS R8.no-unqualified-published-claims:docs/getting-started/connect-your-agent.md
PASS R8.no-unqualified-published-claims:docs/contributing/release-readiness.md
PASS R8.no-unqualified-published-claims:docs/contributing/release-process.md

All release-readiness rules pass.
```

- [ ] **Step 3: Document the local check in `CONTRIBUTING.md`**

In `CONTRIBUTING.md`, find the required local checks section and add:

```bash
node scripts/check-release-readiness.mjs
```

Add one sentence near the command list:

```markdown
`check-release-readiness.mjs` protects public release docs from accidentally
claiming Maven Central or Gradle Plugin Portal publication before artifacts are
actually visible.
```

- [ ] **Step 4: Add the checker to CI**

In `.github/workflows/ci.yml`, add this step near the existing doc consistency
step:

```yaml
      - name: Check release readiness docs
        run: node scripts/check-release-readiness.mjs
```

- [ ] **Step 5: Run checks**

```bash
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected:

```text
All release-readiness rules pass.
All doc-consistency rules pass.
```

- [ ] **Step 6: Commit**

```bash
git add scripts/check-release-readiness.mjs CONTRIBUTING.md .github/workflows/ci.yml
git commit -m "ci: check release readiness docs"
```

---

### Task 4: Publish Prep Documentation and Safe Validation

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/contributing/release-process.md`
- Optional modify: `gradle.properties`

- [ ] **Step 1: Decide whether to add version/group now**

Inspect `gradle.properties`:

```bash
sed -n '1,120p' gradle.properties
```

If it already contains `VERSION_NAME`, `VERSION`, `group`, or `version`, do
not add another version source. Instead document the existing value in
`release-readiness.md`.

If there is no publish version source, do not add one in this task unless the
owner explicitly asks. Keep this phase documentation-only to avoid implying
artifact publication is implemented.

- [ ] **Step 2: Add a publish prep section to release process**

In `docs/contributing/release-process.md`, under `## Future Artifact Release Gate`,
add this subsection:

````markdown
### Publish Prep Validation

These commands are safe because they do not publish remote artifacts:

```bash
./gradlew publishToMavenLocal --dry-run
./gradlew :fixthis-gradle-plugin:validatePlugins
```

If either task does not exist yet, keep the command documented here as the
target validation contract and track the missing Gradle publishing setup as an
artifact-release blocker in `release-readiness.md`.
````

- [ ] **Step 3: Add explicit blocker rows if Gradle publish tasks are missing**

In `docs/contributing/release-readiness.md`, under
`## Required Before External Artifact Release`, add these checked/unchecked
items if they are not already present:

```markdown
- [ ] Gradle publishing plugins are configured for local dry-run validation.
- [ ] `./gradlew publishToMavenLocal --dry-run` succeeds.
- [ ] `./gradlew :fixthis-gradle-plugin:validatePlugins` succeeds.
```

- [ ] **Step 4: Run safe validation commands**

Run:

```bash
./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

Then run:

```bash
./gradlew publishToMavenLocal --dry-run --no-daemon
```

Expected if publish tasks are not configured yet:

```text
Task 'publishToMavenLocal' not found
```

If the task is missing, do not treat it as a failed implementation. The docs
must keep it as an explicit artifact-release blocker.

- [ ] **Step 5: Run release checks**

```bash
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected:

```text
All release-readiness rules pass.
All doc-consistency rules pass.
```

- [ ] **Step 6: Commit**

```bash
git add docs/contributing/release-readiness.md docs/contributing/release-process.md gradle.properties
git commit -m "docs: document artifact publish prep gates"
```

If `gradle.properties` was not changed, omit it from `git add`.

---

### Task 5: Final Verification and Merge Prep

**Files:**
- Verify only unless a previous task reveals a small doc typo.

- [ ] **Step 1: Run final release doc checks**

```bash
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected:

```text
All release-readiness rules pass.
All doc-consistency rules pass.
```

- [ ] **Step 2: Run shell syntax checks**

```bash
bash -n scripts/bootstrap-mcp.sh
bash -n scripts/fixthis-smoke.sh
bash -n scripts/restart-console.sh
```

Expected: no output and exit code 0.

- [ ] **Step 3: Run Gradle help warning check**

```bash
./gradlew help --warning-mode all --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Inspect status and log**

```bash
git status --short --branch
git log --oneline --decorate -5
```

Expected:

```text
## codex/release-publish-readiness
```

Latest commits should include:

```text
docs: clarify release readiness status
docs: split source and artifact release process
ci: check release readiness docs
docs: document artifact publish prep gates
```

- [ ] **Step 5: Prepare final summary**

Summarize:

- public docs now separate source release, publish-ready, and published;
- release-readiness checker is in local docs and CI;
- artifact publication remains blocked until maintainer credentials and
  registry verification exist;
- exact verification commands run and their results.
