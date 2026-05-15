package io.github.beyondwin.fixthis.cli.commands

/**
 * Best-effort scrubbing of secrets and home paths out of error messages and
 * stack traces before they are printed to stderr. Defense-in-depth alongside
 * the documented "redact before pasting" guidance in
 * docs/guides/troubleshooting.md.
 */
internal object SetupErrorRedactor {

    private val SENSITIVE_KEY = Regex(
        "(?i)\"((?:api[-_]?key|token|secret|credential|password|bearer)[\\w-]*)\"\\s*[:=]\\s*\"[^\"]*\"",
    )
    private val SENSITIVE_LONG_VALUE_AFTER_KEY = Regex(
        "(?i)((?:api[-_]?key|token|secret|credential|password|bearer)[\\w-]*\\s*[:=]\\s*)([^\\s,;}\\]]{20,})",
    )
    private val BEARER_HEADER = Regex("(?i)(Bearer|Basic)\\s+[A-Za-z0-9._\\-/+=]+")
    private val USERS_HOME = Regex("/Users/[^/\\s\"]+")
    private val LINUX_HOME = Regex("/home/[^/\\s\"]+")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        var out = text
        out = SENSITIVE_KEY.replace(out) { match ->
            "\"${match.groupValues[1]}\": \"***REDACTED***\""
        }
        out = SENSITIVE_LONG_VALUE_AFTER_KEY.replace(out, "$1***REDACTED***")
        out = BEARER_HEADER.replace(out, "$1 ***REDACTED***")
        out = USERS_HOME.replace(out, "/Users/<redacted>")
        out = LINUX_HOME.replace(out, "/home/<redacted>")
        return out
    }
}
