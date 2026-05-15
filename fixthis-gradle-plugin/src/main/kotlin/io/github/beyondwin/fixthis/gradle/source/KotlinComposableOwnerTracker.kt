package io.github.beyondwin.fixthis.gradle.source

internal fun composableOwnerByLine(lines: List<String>): Array<String?> {
    val owners = arrayOfNulls<String>(lines.size)
    val stack = ArrayDeque<Pair<String, Int>>()
    var depth = 0
    var pendingComposable = false
    var pendingFunName: String? = null
    var pendingFunOpenDepth = -1
    lines.forEachIndexed { index, line ->
        if (line.contains("@Composable")) pendingComposable = true
        functionRegex.find(line)?.let { match ->
            if (pendingComposable || line.contains("@Composable")) {
                pendingFunName = match.groupValues[1]
                pendingFunOpenDepth = depth
            }
            pendingComposable = false
        }
        var ownerOnThisLine: String? = stack.lastOrNull()?.first
        line.forEach { char ->
            when (char) {
                '{' -> {
                    depth += 1
                    if (pendingFunName != null && depth == pendingFunOpenDepth + 1) {
                        ownerOnThisLine = pendingFunName
                        stack.addLast(pendingFunName!! to depth)
                        pendingFunName = null
                        pendingFunOpenDepth = -1
                    }
                }
                '}' -> {
                    if (stack.isNotEmpty() && stack.last().second == depth) stack.removeLast()
                    depth -= 1
                }
            }
        }
        owners[index] = ownerOnThisLine ?: stack.lastOrNull()?.first
    }
    return owners
}

private val functionRegex = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
