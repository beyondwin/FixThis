# CLI Exit Codes

All `fixthis` commands return one of:

| Code | Name | Meaning |
|------|------|---------|
| `0` | OK | All requested actions applied, or all checks passed. |
| `1` | PARTIAL | Some actions skipped or some checks failed. Detail in stdout (JSON if `--json`) or stderr. |
| `2` | USAGE_ERROR | Invalid flag / argument combination. No side effects. |
| `3` | ENV_BLOCKER | Environment-level prerequisite missing (e.g. `adb`, Android SDK). Remediation available via `doctor --json`. |
| `4` | INTERNAL_ERROR | Unexpected exception. Re-run with `--verbose` for stack trace. |

Agents should treat any non-zero exit as an opportunity to consult `fixthis doctor --json`.
