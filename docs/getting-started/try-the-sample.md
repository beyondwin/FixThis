# Quick Start — Sample App

The fastest way to see FixThis: one real handoff from the bundled sample.
No changes to your app.

## Prerequisites

| | Tested with |
| --- | --- |
| JDK | 21 |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.2.21 |
| Compose BOM | 2025.01.01 |
| Android `minSdk` | 23 (Android 6.0) |
| Android `targetSdk` / `compileSdk` | 34 |
| Desktop OS | macOS, Linux, Windows (`adb` on PATH) |
| Agent (optional) | Claude Code, Codex, Cursor, or any chat that accepts Markdown |

Connect a debuggable device or an unlocked emulator first.

## Run the sample (~5 min)

```bash
git clone <this-repo> && cd FixThis
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.github.beyondwin.fixthis.sample
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.github.beyondwin.fixthis.sample
```

`fixthis run` installs the sample debug APK, launches it, and opens FixThis
Studio at `http://127.0.0.1:<port>`.

## In the console

1. Click **Annotate** to freeze the preview.
2. Click a UI element, or drag a visual area.
3. Type the change you want.
4. Repeat for other spots on this screen.
5. **Copy Prompt** to paste into any chat agent, or **Save to MCP** for
   Claude Code / Codex.

## Done

- A numbered annotation pin is visible.
- Compact Markdown is on the clipboard, or a local MCP handoff is saved.
- The agent can receive the paste or call `fixthis_read_feedback`.

The Diagnostics tab includes a native AndroidView fixture. Visual-area
annotations over those pixels should warn instead of claiming Compose source
precision.

If doctor or run fails, see [Troubleshooting](../guides/troubleshooting.md).

## Next

- [Connect your agent](connect-your-agent.md)
- [Add FixThis to your app](add-to-your-app.md)
- [Console tour](../guides/feedback-console-tour.md)
