# Quick Start — Try the Sample App

The fastest way to see FixThis end-to-end is the bundled sample app. No code
changes to your own app required.

## Prerequisites

| | Tested with |
| --- | --- |
| JDK (toolchain) | 21 |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.2.21 |
| Compose BOM | 2026.04.01 |
| Android `minSdk` | 24 (Android 7.0) |
| Android `targetSdk` / `compileSdk` | 36 |
| Desktop OS | macOS, Linux, Windows (anywhere `adb` runs) |
| AI agent (optional) | Claude Code, Codex, Cursor, or any chat agent that accepts pasted Markdown |

ADB must be on your PATH. Connect a debuggable Android device or unlocked
emulator before running the console.

## Run the sample (~5 min)

```bash
# 1. Clone (you can drive everything from the CLI; Android Studio is optional)
git clone <this-repo> && cd FixThis

# 2. Build the desktop CLI + MCP server
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist

# 3. Verify your environment (ADB on PATH, JDK 21, device reachable)
fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.beyondwin.fixthis.sample

# 4. With a device or emulator connected, install + run sample + open the browser console
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.beyondwin.fixthis.sample
```

`fixthis run` installs the sample debug APK, launches it, attaches the sidekick
bridge, and opens the FixThis Studio console at `http://127.0.0.1:<port>` in
your default browser.

## What to try in the console

1. Click **Annotate** to freeze the latest preview.
2. Click any UI element on the frozen preview, or drag a visual area.
3. Type a comment about the change you want.
4. Click **Add annotation** — a numbered pin appears on the overlay.
5. Repeat 2–4 for any other changes on this screen.
6. Click **Copy Prompt** to copy a compact Markdown prompt to the clipboard,
   then paste it into Claude / Codex / Cursor / any chat-style agent.

   *Or* click **Save to MCP** to persist the batch as a local handoff that
   Claude Code or Codex can read on demand via MCP tools.

## Sample coverage

- The Diagnostics tab includes a native AndroidView fixture. Use it to verify
  that visual-area annotations over non-Compose pixels carry low-confidence or
  possible-interop handoff warnings instead of overclaiming Compose source
  precision.

## Troubleshooting

If `fixthis doctor` or `fixthis run` reports an issue, see
[Troubleshooting](../guides/troubleshooting.md) for ADB, lockscreen, and
bridge-attach diagnoses.

## What's next

- [Add FixThis to your own app](add-to-your-app.md)
- [Feedback console tour](../guides/feedback-console-tour.md) — full walkthrough
  with screenshots
- [Working with AI agents](../guides/agents.md) — Claude Code, Codex,
  Cursor, and chat-style agents
