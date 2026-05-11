# Compatibility matrix

FixThis is built and exercised against a single, pinned set of toolchain
versions (the "Tested" axis below). The "Minimum that compiles" axis records
the lower bound that the project still expects to assemble against — those
lower bounds are validated by an informational [nightly workflow](#nightly-validation)
and are not (yet) gated by required CI.

If you consume FixThis from your own project, the **Tested** column is the
safe choice. The **Minimum that compiles** column documents intent for
downstream users on older toolchains; treat it as best-effort until the
nightly is promoted to a required check (see
[CHANGELOG.md](../../CHANGELOG.md) for the promotion follow-up).

## Axes

| Axis                       | Tested (pinned)       | Minimum that compiles | Source                                 |
| -------------------------- | --------------------- | --------------------- | -------------------------------------- |
| Android Gradle Plugin      | **9.1.1**             | 9.0.0                 | `gradle/libs.versions.toml` → `agp`    |
| Kotlin Gradle Plugin       | **2.2.21**            | 2.2.0                 | `gradle/libs.versions.toml` → `kotlin` |
| Compose BOM                | **2026.04.01**        | 2026.01.00            | `gradle/libs.versions.toml` → `composeBom` |
| Compose UI test artifacts  | **1.10.0**            | 1.9.0                 | `gradle/libs.versions.toml` → `composeUiTest` |
| JDK toolchain              | **21 (Temurin)**      | 21                    | `.github/workflows/ci.yml`             |
| Android `minSdk`           | **24**                | 24                    | `app/build.gradle.kts`                 |

The "Tested" column matches what CI builds on every PR and push to `main`
([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)). The "Minimum
that compiles" column is checked nightly (informational only — see
[Nightly validation](#nightly-validation)).

## Rationale per axis

### Android Gradle Plugin

- **Tested: 9.1.1.** AGP 9.x is the first major series with the new
  Kotlin-multiplatform-friendly variant API surface that the sidekick build
  scripts rely on. The Gradle plugin (`:fixthis-gradle-plugin`) targets the
  `AndroidComponentsExtension` shape stabilised in AGP 9.
- **Minimum 9.0.0.** TODO(maintainer): expand once we know the precise lower
  bound. Empirically AGP 9.0.x should compile against today's sources; the
  nightly job is what will catch regressions in that claim. AGP 8.x is
  explicitly out of scope — variant API and namespace handling differ enough
  that the sidekick's auto-wiring no longer applies.

### Kotlin Gradle Plugin

- **Tested: 2.2.21.** Project sources compile with the K2 frontend and use
  language features pinned to Kotlin 2.2. The composite-build setup applies
  the same Kotlin version across `:fixthis-compose-core`, `:fixthis-cli`,
  `:fixthis-mcp`, `:fixthis-compose-sidekick`, and `:fixthis-gradle-plugin`.
- **Minimum 2.2.0.** TODO(maintainer): expand. Kotlin 2.2 unblocks compose
  compiler 1.10+ wiring; older 2.1.x runs into compose-compiler / AGP
  matching constraints. See Compose-internal API surface changes for the
  exact tipping point.

### Compose BOM

- **Tested: 2026.04.01.** Matches the BOM the sample app and `fixthis-compose-core`
  resolve at build time.
- **Minimum 2026.01.00.** TODO(maintainer): expand. The console-bound
  semantics that Smart Select reads (`SemanticsProperties`) have churned
  across Compose 1.7 → 1.10; the lower bound here reflects the first Compose
  release whose semantics surface matches what `:fixthis-compose-sidekick`
  consumes.

### JDK toolchain

- Both "Tested" and "Minimum" pin to JDK 21 (Temurin). Lower JDKs are
  unsupported because AGP 9 itself requires JDK 17+ and the project's
  toolchain configuration locks 21. The CI workflow uses
  [`actions/setup-java@v4`](../../.github/workflows/ci.yml) with
  `distribution: temurin` and `java-version: "21"`.

### Android `minSdk`

- `minSdk` 24 is fixed. Lower SDKs are not validated; the sidekick uses
  `androidx.startup` (debug-only) which requires API 21+, but the sample app
  and tests assume 24.

## Nightly validation

A nightly workflow runs the lower bounds informationally:

- File: [`.github/workflows/nightly-compat.yml`](../../.github/workflows/nightly-compat.yml)
- Schedule: 03:00 UTC daily plus manual `workflow_dispatch`.
- Each axis (AGP, Kotlin, Compose) is exercised by one
  `./gradlew :app:assembleDebug` invocation with a property override
  pointing at the axis's lower bound.
- The workflow is **informational** — `continue-on-error: true` on each
  step. Failures are surfaced in the job log only; they do not block PRs.
- Promotion of this workflow to a required check is tracked in
  [CHANGELOG.md](../../CHANGELOG.md) under the BR-4 Unreleased entry.

> NOTE: the property override mechanism (`-PoverrideAgpVersion`,
> `-PoverrideKotlinVersion`, `-PoverrideComposeBomVersion`) is not yet wired
> through `settings.gradle.kts` / `gradle/libs.versions.toml`. The workflow
> still runs `assembleDebug` and reports the resolved versions, so the daily
> log captures whatever the current pinned bounds produce until the override
> plumbing lands as a follow-up.

## Related

- [Bridge protocol](bridge-protocol.md) — versioning of the wire protocol
  between sidekick and the MCP server, independent of package / toolchain
  versioning.
- [CHANGELOG.md](../../CHANGELOG.md) — release notes, including
  toolchain-bump entries.
- [Release readiness](../contributing/release-readiness.md) — publishing
  checklist for the first external release.
