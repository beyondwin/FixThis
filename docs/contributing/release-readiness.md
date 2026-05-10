# Release Readiness

## Current Status

FixThis is ready for local debug use in this repository. External release requires the items below to be completed first.

## Required Before External Release

- [x] Project owner selects and adds a root `LICENSE` (Apache-2.0, added 2026-05-10).
- [x] CI baseline runs on pull requests (`.github/workflows/ci.yml`).
- [x] `CONTRIBUTING.md` documents local verification.
- [x] `CHANGELOG.md` records user-visible changes and known limitations.
- [x] `SECURITY.md` documents the local-first debug security model.
- [ ] Maven Central / Gradle Plugin Portal coordinates are published (still placeholder).

## Compatibility Matrix

| Surface | Supported |
| --- | --- |
| Android UI toolkit | Jetpack Compose debug builds |
| Release builds | Not supported |
| AccessibilityService | Not used |
| MCP workflow | Desktop stdio server plus localhost console |
| Console host | Loopback localhost by default |
| Screenshots | Local artifacts only |
| External AI API calls | Not made by FixThis |
| External Gradle artifacts | Not published yet |

## Known Release Blockers

- Published Gradle plugin and sidekick coordinates are placeholders until artifacts are actually released to Maven Central / Gradle Plugin Portal.
- Compatibility should be rechecked against the target Android Gradle Plugin, Kotlin, Compose BOM, Java, Gradle, SDK, operating system, and MCP client versions before publishing.
