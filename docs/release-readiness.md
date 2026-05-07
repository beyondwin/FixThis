# Release Readiness

## Current Status

FixThis is ready for local debug use in this repository. External release requires the items below to be completed first.

## Required Before External Release

- Project owner selects and adds a root `LICENSE`.
- CI baseline is required on pull requests.
- `CONTRIBUTING.md` documents local verification.
- `CHANGELOG.md` records user-visible changes and known limitations.
- `SECURITY.md` documents the local-first debug security model.
- README compatibility details remain clear about unpublished external artifacts.

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

- No root `LICENSE` has been selected.
- Published Gradle plugin and sidekick coordinates are placeholders until artifacts are actually released.
- Compatibility should be rechecked against the target Android Gradle Plugin, Kotlin, Compose BOM, Java, Gradle, SDK, operating system, and MCP client versions before publishing.
