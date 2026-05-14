# FixThis Setup — Error Diagnostics Design Spec

> **Status:** Design — ready for implementation plan
> **Owner:** DX / Setup domain
> **Companion plan:** `docs/superpowers/plans/2026-05-14-setup-error-diagnostics-implementation.md`
> **Predecessor:** `docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md` (Change 1 already shipped — cause is attached to `CliktError` but never printed)

---

## 1. Problem

### 1.1 Current state

`fixthis setup --write` registers FixThis with Claude Code and Codex by merging the FixThis MCP entry into `.claude/settings.json` and `~/.codex/config.toml`. The merge can fail for many reasons (malformed pre-existing JSON, malformed TOML, an `mcpServers` key with a non-object value, a transient `IOException`, an unexpected key shape). All those failures pass through one catch block in
`fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`, lines 82–90:

```kotlin
val merged = try {
    val current = configFile.takeIf { it.isFile }?.readText()
    writer.merge(current, entry)
} catch (e: Exception) {
    throw CliktError(
        "Could not merge ${writer.name} MCP config at ${configFile.absolutePath}: ${e.message}",
        cause = e,
    )
}
```

The 2026-05-09 polish plan added `${e.message}` to the suffix and attached `cause = e`, which is a one-level improvement. Three concrete gaps remain:

1. **The `cause` field is never printed.** `Main.kt:27–40` catches `CliktError` and calls `command.getFormattedHelp(error)`. In Clikt 5.0.3 (verified in the project's resolved dependency cache, `clikt-core-jvm-5.0.3-sources.jar :: BaseCliktCommand.kt :: getFormattedHelp`) the implementation for a plain (non-`ContextCliktError`) error reads:

   ```kotlin
   if (error != null && error !is ContextCliktError) {
       return error.message
   }
   ```

   So `error.message` is the *only* thing the user ever sees. The `cause` chain that the SetupCommand catch carefully attaches is silently dropped at the top of `main()`.

2. **Single-level message extraction.** `${e.message}` shows only the outermost exception. Real-world stacks are nested. A `kotlinx.serialization.json.SerializationException` thrown by `parseToJsonElement` typically wraps another parse position cause; a TOML write triggered by `AtomicConfigFileWriter` can wrap an `IOException(EACCES)`. The user sees one terse line and has no way to ask for more without re-running under a JVM debugger.

3. **No diagnostic mode.** There is no `--verbose` (or equivalent) flag on `SetupCommand`, on its parent (`fixthis`), or anywhere else in `:fixthis-cli` (verified with `grep -rn "verbose\\|--verbose" fixthis-cli/`). When a user reports "setup failed", a maintainer has no opt-in way to ask for the full stack trace.

### 1.2 Impact

- **Time-to-fix is high for malformed config files.** A truncated `.claude/settings.json` (e.g. `{"mcpServers":`) surfaces as `Could not merge claude MCP config at /…/settings.json: Unexpected JSON token at offset 14: …` — the position is in the inner cause, which is *not* in `e.message` for several `kotlinx.serialization` exception shapes. Users see only the outer message and must read source.
- **No path to "show me everything".** Bug reports filed against `fixthis setup` arrive without enough information to reproduce. The current workaround is to ask the user to add `--stacktrace` to the *underlying* Gradle run that launches the CLI, which only works when `fixthis` is invoked via Gradle, not via the installed binary.
- **No actionable recommendations.** Each failure category has a different fix (delete the file, fix JSON, fix permissions, install via `installDist`). Today every failure says the same thing: "Could not merge". Even the new explicit `IllegalArgumentException` from `ClaudeConfigWriter` (Polish Change 2) blends into the generic catch.
- **Inconsistent with the existing UX pattern.** The `executable == null` warning was upgraded in Polish Change 3 to a four-line actionable message ("Warning: fixthis-mcp executable not found. \n  The written config will use …\n  Fix: run …"). The merge-failure path has not received the same treatment.

### 1.3 Trigger for this spec

Code review on 2026-05-13 observed that the 2026-05-09 polish was insufficient: the line `Could not merge claude MCP config at /path/settings.json:` was still appearing in user-reported issues *with no suffix detail*, because for some exception subclasses the `message` field is `null` and `${e.message}` renders as `null` or empty. The polish change made the prefix correct but the suffix unreliable.

---

## 2. Goals and Non-Goals

### 2.1 Goals

G1. **Propagate the full cause chain to the printed message.** When `fixthis setup --write` fails with any exception, the user sees the outermost message and every nested `Throwable.cause.message` in a readable form.

G2. **Categorize the failure and recommend a remediation.** For the five known failure categories (truncated JSON, malformed JSON values, malformed TOML, filesystem-permission error, unexpected mcpServers shape), the printed output names the category and prints the exact next command or manual fix.

G3. **Add a `--verbose` flag on `setup`** that opts into a full Java stack trace (including suppressed exceptions) on failure. Default behavior remains terse.

G4. **Print the cause chain at the Clikt boundary,** not only inside `SetupCommand`, so that any future `CliktError(cause = e)` produced elsewhere in `:fixthis-cli` benefits without per-call-site changes.

G5. **All changes are TDD-driven** with unit tests in `:fixthis-cli` that pin the expected output for each category. No integration test is required because the failure surface is deterministic from `SetupCommand` and `Main`.

### 2.2 Non-Goals

NG1. **No new failure recovery.** This spec only *reports* failures more clearly. Auto-recovery (e.g., backing up the malformed file and writing a fresh one) is out of scope.

NG2. **No changes to the merge algorithms.** `ClaudeConfigWriter.merge` and `CodexConfigWriter.merge` keep their current logic and exception types. We add diagnostic plumbing around them, not new validation inside them.

NG3. **No `--verbose` plumbing for `run`, `console`, `doctor`, `mcp`, `clean`, or `status`.** Those commands have separate UX paths and adding a CLI-global flag would touch them all. We restrict to `setup` in this iteration. (Future iterations can promote the flag once the per-command pattern proves out.)

NG4. **No localization of error messages.** Messages remain English; localization is tracked separately.

NG5. **No JSON output mode for setup errors.** Some MCP tooling needs machine-readable failures; that is a separate spec.

NG6. **No changes to `AtomicConfigFileWriter`.** The "Could not write …" path in `applyWritePlan` already catches without preserving cause (line 105: `catch (_: Exception)`), but it is gated by `AtomicConfigFileWriter.write` which produces IO errors with self-explanatory messages. Folding it in is a stretch goal for the implementation plan but not a Goal here.

---

## 3. Design

### 3.1 Architecture overview

Three surfaces change, each with a clear contract:

```
┌─────────────────────────────────────────────────────────────────┐
│ SetupCommand.kt                                                 │
│   - --verbose flag (Clikt option, --verbose / -v)               │
│   - buildWritePlans():                                          │
│       catch (e: Exception) →                                    │
│         throw CliktError(                                       │
│           message = renderMergeFailure(writer, configFile, e),  │
│           cause = e,                                            │
│         )                                                       │
│   - renderMergeFailure() classifies e and builds                │
│     multi-line message with category + cause chain + fix.       │
└────────────────────────┬────────────────────────────────────────┘
                         │ CliktError(message, cause)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ Main.kt                                                         │
│   try { command.parse(args) }                                   │
│   catch (CliktError) {                                          │
│     command.getFormattedHelp(error) → System.err / out          │
│     if (verbose context set) {                                  │
│       error.cause?.let { System.err.println(it.stackTrace) }    │
│     }                                                           │
│     exitProcess(error.statusCode)                               │
│   }                                                             │
└────────────────────────┬────────────────────────────────────────┘
                         │ verbose=true plumbed via Clikt Context
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ DiagnosticContext (new, internal)                               │
│   - thread-local-ish flag, set by SetupCommand when --verbose   │
│     is true; read by Main.kt after CliktError catch.            │
│   - Implementation: companion-object var on a new               │
│     `DiagnosticContext` object. (Setup is the only writer;      │
│     Main is the only reader; the CLI process is single-shot.)   │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Failure category taxonomy

The merge surface is small enough to enumerate. Each category gets a stable string token that the spec pins; tests assert on those tokens.

| Category token | Triggers (verified) | Recommended action |
|---|---|---|
| `MALFORMED_JSON` | `kotlinx.serialization.json.JsonDecodingException` from `parseToJsonElement` (e.g., truncated `.claude/settings.json`). Detect by class-name suffix `JsonDecodingException` to avoid linking against an internal class. | "Open `<path>`, fix the JSON syntax (the parse error above points at the offending position), and re-run `fixthis setup --write`. If you cannot recover the file, back it up and delete it; `fixthis setup --write` will create a fresh one." |
| `MALFORMED_MCPSERVERS_SHAPE` | `IllegalArgumentException` whose message contains `"mcpServers"` and `"not a JSON object"` (the exact string thrown by `ClaudeConfigWriter.merge` after Polish Change 2). | "The `mcpServers` key already exists in `<path>` but is not a JSON object. Open the file and replace it with `{}` (an empty object), or remove the key entirely; `fixthis setup --write` will add the FixThis entry." |
| `MALFORMED_TOML` | `IllegalStateException` or generic `Exception` raised inside `CodexConfigWriter.merge` (the writer parses TOML manually; failures surface as `IllegalStateException` from the regex/section code or `IllegalArgumentException` from key normalization). Detect by `configFile.name.endsWith(".toml")` plus *not* a `JsonDecodingException` and *not* the explicit Claude `mcpServers` shape error. | "Open `<path>`, repair the TOML (errors above name the failure), and re-run. If unsure, back up the file and delete the `[mcp_servers.fixthis]` section; `fixthis setup --write` will rewrite it." |
| `FILESYSTEM_ERROR` | `IOException` thrown by `configFile.readText()` (e.g., EACCES on a config file owned by another UID). | "Filesystem error reading `<path>`. Check permissions with `ls -l <path>`. If the file is owned by another user, `chmod +r` or `chown` so your shell can read it." |
| `UNKNOWN` | Any other `Exception` reaching the catch. Falls back to "category UNKNOWN" + the full cause chain. | "Please file an issue with the output of `fixthis setup --write --verbose` and the contents of `<path>` (redact secrets first)." |

### 3.3 Cause chain rendering

`renderCauseChain(top: Throwable): String` walks `top` → `top.cause` → … and produces:

```
caused by SerializationException: Unexpected JSON token at offset 14: ...
  caused by JsonDecodingException: Expected '"' but had '<EOF>' instead
```

Implementation outline (final code in the plan):

- Walk `generateSequence(top) { it.cause }.takeWhile { /* break on cycles via IdentityHashMap */ }`.
- Stop at depth 8 and append `… (cause chain truncated)` so a pathological cycle cannot hang the CLI.
- Skip the outermost throwable when it is the same as the `e` we already rendered as the message prefix (avoid duplicating `e.message`).
- Each line is `"  caused by <ClassSimpleName>: <message-or-(no message)>"` indented by two spaces per level for readability.

### 3.4 Full message shape

For category `MALFORMED_JSON`, target output (single example; other categories follow the same shape):

```
Could not merge claude MCP config at /home/u/proj/.claude/settings.json.
  Category: MALFORMED_JSON
  Cause:
    caused by SerializationException: Unexpected JSON token at offset 14: Expected closing brace
  Fix: Open /home/u/proj/.claude/settings.json, fix the JSON syntax (the parse error above points at the offending position), and re-run `fixthis setup --write`. If you cannot recover the file, back it up and delete it; `fixthis setup --write` will create a fresh one.
  Re-run with --verbose for a full stack trace.
```

Key contract points:

- **Prefix is stable:** `Could not merge <writer> MCP config at <abs-path>.` — existing tests already match this prefix via `startsWith`.
- **`Category:` line is the second line, always.** Tests pin its presence per category.
- **`Cause:` block lists ≥1 indented `caused by …` line.** Tests count the lines.
- **`Fix:` block is one line of free-form text.** Tests assert on key tokens (e.g., contains `delete`, contains the file path).
- **`Re-run with --verbose` hint is appended only when `--verbose` was NOT passed.** When `--verbose` is on, the hint is suppressed and the full stack trace follows.

### 3.5 `--verbose` flag

Added on `SetupCommand` only:

```kotlin
private val verbose by option("--verbose", "-v", help = "Print full stack trace on failure").flag(default = false)
```

When `--verbose` is true and `run()` is about to throw a `CliktError` from the merge path, it first records `DiagnosticContext.verbose = true`. `Main.kt`, after printing the formatted help, checks `DiagnosticContext.verbose` and prints `error.cause?.stackTraceToString()` to `System.err` if set.

Why a companion-object var instead of Clikt's `Context`:

- `getFormattedHelp` runs *with* the context but the context object is not surfaced on a plain (non-`ContextCliktError`) error. We could thread a `ContextCliktError`, but that requires a custom error class and pulls Clikt-internal API into our code. A single `internal object DiagnosticContext` with a `@JvmStatic var verbose: Boolean` is simpler and lives in the same module that owns both writer and reader. The CLI is a one-shot process; there is no risk of cross-invocation leakage.

Alternative considered: thread the flag through the throw site as a new `DiagnosticCliktError(message: String, cause: Throwable, val verbose: Boolean)` subclass, caught in Main. Rejected because:

- It introduces a new public-ish class for one bit of state.
- It does not generalize: other commands that may want `--verbose` later would each have to throw the same subclass.
- The chosen approach (object holder) is exactly the same shape Clikt uses internally for its own `printError` flag (a Boolean on `CliktError`).

### 3.6 Backward compatibility

- **Existing prefix preserved.** `Could not merge <writer> MCP config at <path>.` is unchanged.
- **Existing tests survive:** `SetupCommandTest.targetAllPreflightsMergesBeforeWritingAnyConfig` already asserts `startsWith("Could not merge claude MCP config at <path>:")`. The new format ends the first line with `.` instead of `:`. **Breaking change for this assertion** — the plan updates it.
- **`AgentConfigWriterTest.claudeMergeThrowsWhenMcpServersIsArray`** asserts `IllegalArgumentException` message tokens (not the CliktError suffix). Unchanged.
- **Existing CLI users:** the new multi-line output is strictly more information; no script consumes the message body. The only programmatic consumer is `bootstrap-mcp.sh`, which only checks the CLI exit code (and exits the shell with `set -euo pipefail`). Unaffected.

### 3.7 What changes in each file

| File | Change |
|---|---|
| `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt` | Add `verbose` option. Replace simple `${e.message}` interpolation with a call to new `renderMergeFailure(writer, configFile, e)`. Set `DiagnosticContext.verbose` when verbose is on. |
| `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupErrorRendering.kt` (new) | Holds `renderMergeFailure`, the category-classification logic, and `renderCauseChain`. Internal visibility. |
| `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/DiagnosticContext.kt` (new) | `internal object DiagnosticContext { var verbose: Boolean = false }`. |
| `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Main.kt` | After `command.getFormattedHelp(error)`, if `DiagnosticContext.verbose`, print `error.cause?.stackTraceToString()` to stderr. |
| `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommandTest.kt` | Update one existing assertion; add five new tests (one per category). |
| `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/SetupErrorRenderingTest.kt` (new) | Unit tests for `renderMergeFailure` and `renderCauseChain` in isolation. |

---

## 4. Migration

### 4.1 Source migration

This is an additive change inside `:fixthis-cli`. No downstream module imports `SetupCommand` or `Main`. No public types are renamed. No persisted file formats change.

### 4.2 Documentation migration

Update sites:

1. `docs/reference/cli.md` — add `--verbose` to the `setup` flag table. One row.
2. `docs/guides/troubleshooting.md` — add a "Setup failures" section that lists the five categories and copy-pastes the recommended action text. This duplicates the runtime message (intentional — search engines surface the doc; the runtime surfaces it inline).
3. `docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md` — add a note at the top: "Superseded by `2026-05-14-setup-error-diagnostics-design.md` for the cause-propagation goal; Changes 2/3/4 still hold."

### 4.3 Test migration

The existing `SetupCommandTest.targetAllPreflightsMergesBeforeWritingAnyConfig` asserts:

```kotlin
assertTrue(
    "Expected message to start with merge error prefix",
    error.message!!.startsWith("Could not merge claude MCP config at ${claudeSettings.absolutePath}:"),
)
```

The colon `:` becomes a period `.` in the new format. Update to `startsWith("Could not merge claude MCP config at ${claudeSettings.absolutePath}.")` in the same task that introduces the new renderer. No other test references this string.

### 4.4 Rollout

Single-PR migration. No feature flag. Risk is low because the output change is strictly additive (more lines) and the prefix line is preserved.

---

## 5. Test Strategy

### 5.1 Automated tests (added in the plan)

**Unit tests for the renderer** (`SetupErrorRenderingTest.kt`, isolated, no Clikt parsing):

- `rendersMalformedJsonCategoryForJsonDecodingException` — feed a `kotlinx.serialization.json.SerializationException` (the public superclass; `JsonDecodingException` is internal) thrown by `Json.Default.parseToJsonElement("{")` and assert: category line equals `MALFORMED_JSON`, fix line contains the path and the literal word `delete`, cause line contains the parse error.
- `rendersMalformedMcpServersShapeCategoryWhenIllegalArgumentMentionsMcpServers` — feed the exact `IllegalArgumentException` produced by `ClaudeConfigWriter.merge` against `{"mcpServers":[]}`; assert category line.
- `rendersMalformedTomlCategoryForCodexFailure` — feed a synthetic `IllegalStateException("bad TOML")` with `configFile.name = "config.toml"`; assert category line equals `MALFORMED_TOML`.
- `rendersFilesystemErrorCategoryForIoException` — feed `IOException("Permission denied")`; assert category line equals `FILESYSTEM_ERROR`.
- `rendersUnknownCategoryForOpaqueException` — feed `RuntimeException("unknown")`; assert category line equals `UNKNOWN` and fix mentions `--verbose`.
- `walksCauseChainAndIndentsByDepth` — build `RuntimeException("a", RuntimeException("b", RuntimeException("c")))`; assert output has three `caused by` lines, indented `  `, `    `, `      ` respectively.
- `truncatesPathologicalCauseCycleAtDepth8` — build a `Throwable` whose `cause` reflects to itself via Java reflection (set via `initCause`); assert output ends with `(cause chain truncated)`. *If cycle construction is too invasive for the test, simulate by passing a custom `Throwable` subclass that overrides `getCause` to return itself.*
- `verboseHintAppearsWhenVerboseFalseAndOmittedWhenTrue` — call the renderer twice, once with `verbose=false`, once with `verbose=true`; assert the `Re-run with --verbose` line is present then absent.

**Integration tests for SetupCommand** (`SetupCommandTest.kt`, exercises Clikt parsing):

- `mergeErrorPrintsMalformedJsonCategory` — write `{"mcpServers":` to `.claude/settings.json`, run `setup --write --target claude`, assert `error.message` contains `Category: MALFORMED_JSON`.
- `mergeErrorPrintsMalformedShapeCategory` — write `{"mcpServers":[]}`, assert `Category: MALFORMED_MCPSERVERS_SHAPE`.
- `verboseFlagSetsDiagnosticContext` — run with `--verbose`, catch `CliktError`, assert `DiagnosticContext.verbose == true`. (The flag's *effect* is in Main, not in SetupCommand; we only verify the wire-up.)
- Reset `DiagnosticContext.verbose = false` in a `@Before` block to avoid test ordering coupling.

**Integration tests for Main.kt** (new `MainTest.kt` — verified absent today; the existing `MainCommandTest` only tests subcommand registration):

- `mainPrintsCauseStackTraceWhenDiagnosticContextVerboseTrue` — set `DiagnosticContext.verbose = true`, construct a `CliktError("msg", cause = RuntimeException("inner"))`, invoke a small helper extracted from `main()` (e.g., `internal fun printCliktError(...)`), redirect stderr, assert output contains `RuntimeException: inner` and `at <some-frame>`. Requires `main()` refactor to extract the print-and-exit path; the plan does this in Task 7.

### 5.2 Manual verification

After implementation, perform these manual scenarios on a sample project (`io.beyondwin.fixthis.sample`):

1. **Truncated JSON:**
   ```bash
   mkdir -p .claude && printf '{"mcpServers":' > .claude/settings.json
   ./fixthis-cli/build/install/fixthis/bin/fixthis setup --write --target claude --package io.beyondwin.fixthis.sample
   ```
   Expect: exit non-zero. Stderr shows prefix line, `Category: MALFORMED_JSON`, a `caused by` line, a `Fix:` line, a `Re-run with --verbose` hint.

2. **Same scenario with `--verbose`:**
   ```bash
   ./fixthis-cli/build/install/fixthis/bin/fixthis setup --write --target claude --verbose --package io.beyondwin.fixthis.sample
   ```
   Expect: same message as above *minus* the `Re-run with --verbose` hint, *plus* a Java stack trace section.

3. **`mcpServers` is a JSON array:**
   ```bash
   printf '{"mcpServers":[]}' > .claude/settings.json
   ./fixthis-cli/build/install/fixthis/bin/fixthis setup --write --target claude --package io.beyondwin.fixthis.sample
   ```
   Expect: `Category: MALFORMED_MCPSERVERS_SHAPE`. Fix line names the file and mentions `{}`.

4. **Filesystem error on Codex path:**
   ```bash
   chmod 000 ~/.codex/config.toml   # make unreadable
   ./fixthis-cli/build/install/fixthis/bin/fixthis setup --write --target codex --package io.beyondwin.fixthis.sample
   chmod 600 ~/.codex/config.toml   # restore
   ```
   Expect: `Category: FILESYSTEM_ERROR`.

5. **Healthy run unchanged:**
   ```bash
   rm -f .claude/settings.json
   ./fixthis-cli/build/install/fixthis/bin/fixthis setup --write --target claude --package io.beyondwin.fixthis.sample
   ```
   Expect: exit 0. Output starts with `Wrote claude MCP config (project-local): <path>`.

### 5.3 Regression coverage

- Run the full `:fixthis-cli:test` suite to confirm no previously green test regresses.
- Run `./gradlew :fixthis-cli:installDist` to confirm the binary still builds.

---

## 6. Open Risks

### 6.1 R1: kotlinx-serialization exception class is internal

`JsonDecodingException` is `internal` in `kotlinx-serialization-json`. We cannot reference it directly. Mitigation: detect via class-simple-name suffix or via the public superclass `SerializationException` plus a string check on the message. The plan uses the simple-name approach (`e::class.simpleName == "JsonDecodingException"`) with a fallback to `SerializationException` so even if the internal class name changes in a future serialization version, we still classify as `MALFORMED_JSON`.

### 6.2 R2: `DiagnosticContext` is mutable global state

The companion-object var is technically not thread-safe. The CLI is single-threaded for argument parsing; this is fine in practice. However, *tests* run in parallel within Gradle. Mitigation: tests must reset the flag in `@Before` and the renderer must read the flag exactly once per render (snapshot it into a local). The plan codifies both. If we ever add a multi-threaded test runner stage, switch to `ThreadLocal<Boolean>`.

### 6.3 R3: Cause-chain rendering can include sensitive paths or secrets

Exception messages from `kotlinx-serialization` typically include offending JSON fragments. If a user's `settings.json` contains a secret in another key (e.g., an `OPENAI_API_KEY`), the parser may echo it as `Unexpected token near "…"`. The default render *and* `--verbose` will surface this on stderr. Mitigation: document in `docs/guides/troubleshooting.md` that users should redact before pasting setup error output into bug reports; the documentation is the mitigation, not the code. Tracking issue: not opened in this spec.

### 6.4 R4: Behavior divergence between installed CLI and Gradle-wrapped invocations

When `fixthis` is invoked via `./gradlew :fixthis-cli:run`, Gradle's own error reporter may also print a stack trace, doubling the output under `--verbose`. Mitigation: out of scope; the `installDist` path is the supported invocation surface.

### 6.5 R5: Test for stack-trace printing is JVM-frame-dependent

The `MainTest.mainPrintsCauseStackTraceWhenDiagnosticContextVerboseTrue` test asserts `at <some-frame>` substring. The exact frame may vary across JDK minor versions (frame mangling of lambda names). Mitigation: assert only the literal substring `at ` (the standard `Throwable.printStackTrace` prefix), not a class/method name.

### 6.6 R6: `--verbose` shadows future global verbose

If we later add `--verbose` to the root `fixthis` command, the Clikt parser will treat the per-subcommand option as ambiguous. Mitigation: when promoting, remove the per-command flag in the same change and route the global flag value into `DiagnosticContext`. Documented in `docs/reference/cli.md` once promoted.

---

## 7. References

- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt` lines 82–90 — current catch block (post-Polish).
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Main.kt` lines 27–40 — current CliktError print path.
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt` lines 25–32 — explicit `IllegalArgumentException` for non-object `mcpServers` (Polish Change 2 source).
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CodexConfigWriter.kt` — TOML parsing surface (manual regex-based; failures bubble as `IllegalStateException`/`IllegalArgumentException`).
- Clikt 5.0.3 source `BaseCliktCommand.getFormattedHelp` — confirms only `error.message` is returned for non-`ContextCliktError`.
- `docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md` — predecessor; this spec extends Change 1.
- `docs/superpowers/plans/2026-05-09-fixthis-setup-polish.md` — predecessor implementation, completed.
- `docs/reference/cli.md` — CLI flag reference (will be updated in the plan).
- `docs/guides/troubleshooting.md` — troubleshooting index (will gain a "Setup failures" section).
