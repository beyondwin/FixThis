# Agent Graphify Workflow

Graphify is a local navigation aid for agents working in the FixThis
repository. It is not part of the Android runtime, MCP server, CLI install
flow, release artifacts, or public contract.

Use Graphify when a task depends on broad codebase structure: where behavior
lives, how modules relate, which files participate in a workflow, or what path
connects two concepts.

## Local Output

Graphify writes generated data under `graphify-out/`. That directory is local
tool state and must not be committed.

Expected local outputs can include:

- `graphify-out/graph.json`
- `graphify-out/GRAPH_REPORT.md`
- `graphify-out/graph.html`
- `graphify-out/cache/`
- `graphify-out/memory/`
- `graphify-out/wiki/`

The repository keeps `.graphifyignore` so local artifacts, generated build
output, fixture workspaces, and Graphify output do not feed back into
extraction.

## Basic Commands

Generate or refresh the local graph:

```bash
graphify update .
```

Ask a focused architecture or codebase question:

```bash
graphify query "where is source matching implemented?"
```

Inspect a relationship:

```bash
graphify path "fixthis-gradle-plugin" "sourceCandidates"
```

Explain a node or concept:

```bash
graphify explain "FeedbackSessionService"
```

Prefer `query`, `path`, or `explain` for focused questions. Read
`graphify-out/GRAPH_REPORT.md` only for broad architecture review or when the
focused commands do not surface enough context.

## Verification Rules

Graphify output is advisory. Before making behavior changes, verify the answer
against the source, tests, and canonical docs.

Use the reference docs and implementation as the authority for:

- MCP tool outputs and persisted session JSON
- `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, and `sourceCandidates`
- bridge protocol behavior
- source-index schema and source matching confidence
- debug-only Android runtime constraints
- release, package, and install flows

If Graphify conflicts with source or docs, the source and docs win.

## Hook Policy

Manual updates are the default:

```bash
graphify update .
```

Hooks are optional local automation:

```bash
graphify hook install
graphify hook status
graphify hook uninstall
```

Do not require hooks for contributors, CI, release work, or agent setup. This
repository has frequent generated-file churn, and Graphify output is a local
agent aid rather than a project artifact.

## Fallback

If `graphify` is not installed or `graphify-out/graph.json` does not exist,
continue with normal repo exploration:

```bash
rg "<symbol-or-contract>"
rg --files
```

Graphify failures should not block normal FixThis development.
