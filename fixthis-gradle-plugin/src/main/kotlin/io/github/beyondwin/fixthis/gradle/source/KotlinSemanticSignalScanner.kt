package io.github.beyondwin.fixthis.gradle.source

internal val kotlinSourceQuotedStringRegex = Regex("\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\"")
internal val kotlinSourceTextCallRegex = Regex("\\bText\\s*\\(\\s*(?:text\\s*=\\s*)?(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
internal val kotlinSourceStringResourceRegex = Regex("\\bstringResource\\s*\\(\\s*R\\.string\\.([A-Za-z0-9_]+)")
internal val kotlinSourceTestTagRegex = Regex("\\btestTag\\s*\\(\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
internal val kotlinSourceContentDescriptionRegex =
    Regex("\\bcontentDescription\\s*=\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
internal val kotlinSourceFunctionRegex = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
internal val kotlinSourcePackageRegex = Regex("\\bpackage\\s+([A-Za-z_][A-Za-z0-9_.]*)")
internal val kotlinSourceClassRegex = Regex("\\b(class|object|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")
private val strictCompTestTagRegex = Regex("""comp:[A-Za-z_][A-Za-z0-9_]*:.+""")

internal data class KotlinStringResourceBinding(
    val resourceName: String,
    val resolvedValue: String?,
)

internal data class KotlinStringResourceSignal(
    val range: IntRange,
    val resourceName: String,
    val resolvedValue: String?,
)

internal data class KotlinRoleSignal(
    val range: IntRange,
    val role: String,
)

internal fun stringResourceBindings(
    source: String,
    resolver: Map<String, String>,
): Map<String, KotlinStringResourceBinding> = stringResourceVariableRegex.findAll(source)
    .associate { match ->
        val variableName = match.groupValues[1]
        val resourceName = match.groupValues[2]
        variableName to KotlinStringResourceBinding(
            resourceName = resourceName,
            resolvedValue = resolver[resourceName],
        )
    }

internal fun contentDescriptionStringResourceSignals(
    source: String,
    resolver: Map<String, String>,
): List<KotlinStringResourceSignal> = contentDescriptionStringResourceRegex.findAll(source)
    .map { match ->
        val resourceName = match.groupValues[1]
        KotlinStringResourceSignal(
            range = match.range,
            resourceName = resourceName,
            resolvedValue = resolver[resourceName],
        )
    }
    .toList()

internal fun contentDescriptionVariableSignals(
    source: String,
    bindings: Map<String, KotlinStringResourceBinding>,
): List<KotlinStringResourceSignal> = contentDescriptionVariableRegex.findAll(source)
    .mapNotNull { match ->
        val binding = bindings[match.groupValues[1]] ?: return@mapNotNull null
        KotlinStringResourceSignal(
            range = match.range,
            resourceName = binding.resourceName,
            resolvedValue = binding.resolvedValue,
        )
    }
    .toList()

internal fun roleSignals(source: String): List<KotlinRoleSignal> = roleRegex.findAll(source)
    .map { match ->
        KotlinRoleSignal(
            range = match.range,
            role = match.groupValues[1],
        )
    }
    .toList()

internal fun collectSemanticModifierSignals(
    source: String,
    resolver: Map<String, String>,
    entryFor: (IntRange) -> SourceIndexEntryBuilder,
) {
    val bindings = stringResourceBindings(source, resolver)
    val resourceSignals = (
        contentDescriptionStringResourceSignals(source, resolver) +
            contentDescriptionVariableSignals(source, bindings)
        ).sortedBy { signal -> signal.range.first }
    resourceSignals.forEach { signal ->
        entryFor(signal.range).apply {
            stringResources += signal.resourceName
            addSignal(SourceSignalKindAsset.STRING_RESOURCE, signal.resourceName)
            signal.resolvedValue?.let { resolved ->
                text += resolved
                contentDescriptions += resolved
                addSignal(SourceSignalKindAsset.CONTENT_DESCRIPTION, resolved)
            }
        }
    }
    roleSignals(source).forEach { signal ->
        entryFor(signal.range).apply {
            roles += signal.role
            addSignal(SourceSignalKindAsset.ROLE, signal.role)
        }
    }
}

internal fun IntRange.contains(other: IntRange): Boolean = first <= other.first && last >= other.last

internal fun String.isStrictCompTestTag(): Boolean = strictCompTestTagRegex.matches(this)

internal fun String.lineStartOffsets(): IntArray {
    val offsets = mutableListOf(0)
    forEachIndexed { index, char ->
        if (char == '\n' && index + 1 < length) {
            offsets += index + 1
        }
    }
    return offsets.toIntArray()
}

internal fun MatchResult.startLine(lineStartOffsets: IntArray): Int = range.startLine(lineStartOffsets)

internal fun MatchResult.literalStartLine(lineStartOffsets: IntArray): Int {
    val literalRange = groups[1]?.range ?: groups[2]?.range ?: range
    return literalRange.startLine(lineStartOffsets)
}

internal fun IntRange.startLine(lineStartOffsets: IntArray): Int {
    val insertionPoint = lineStartOffsets.binarySearch(first)
    return if (insertionPoint >= 0) {
        insertionPoint + 1
    } else {
        -insertionPoint - 1
    }
}

private val stringResourceVariableRegex =
    Regex("\\bval\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*[^=\\n]+)?=\\s*stringResource\\s*\\(\\s*R\\.string\\.([A-Za-z0-9_]+)")
private val contentDescriptionStringResourceRegex =
    Regex("\\bcontentDescription\\s*=\\s*stringResource\\s*\\(\\s*R\\.string\\.([A-Za-z0-9_]+)")
private val contentDescriptionVariableRegex =
    Regex("\\bcontentDescription\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)")
private val roleRegex = Regex("\\brole\\s*=\\s*Role\\.([A-Za-z_][A-Za-z0-9_]*)")
