# FixThis npm wrapper

This package installs the FixThis CLI and MCP server from the matching GitHub
Release asset, then exposes:

```bash
npx --package @beyondwin/fixthis fixthis init --agent --project-dir . --target all
```

The MCP Registry verification name is:

```text
io.github.beyondwin/fixthis
```

FixThis itself is local-first and talks to Android debug builds over ADB. The
downloaded CLI/MCP package comes from:

```text
https://github.com/beyondwin/FixThis/releases
```
