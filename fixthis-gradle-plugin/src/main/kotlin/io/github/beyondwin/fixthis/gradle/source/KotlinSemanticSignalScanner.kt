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

// Kept in sync with the compose-core TestTagConvention.patterns set:
// comp:<Name>:<id>, screen:<Name>:<id>, comp.<Name>.<id>, screen.<Name>.<id>.
private val strictCompTestTagRegex =
    Regex("""(?:comp:[A-Za-z_][A-Za-z0-9_]*:.+|screen:[A-Za-z_][A-Za-z0-9_]*:.+|comp\.[A-Za-z_][A-Za-z0-9_]*\..+|screen\.[A-Za-z_][A-Za-z0-9_]*\..+)""")

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

internal data class KotlinLayoutRendererSignal(
    val range: IntRange,
    val renderer: String,
)

internal data class KotlinSlotWrapperSignal(
    val range: IntRange,
    val composable: String,
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

internal fun layoutRendererSignals(source: String): List<KotlinLayoutRendererSignal> {
    val ignoredRanges = source.layoutRendererIgnoredRanges()
    val importedRenderers = source.importedComposeLayoutRenderers(ignoredRanges)
    val localRendererDeclarations = source.localLayoutRendererDeclarationOffsets(ignoredRanges)
    return layoutRendererRegex.findAll(source)
        .mapNotNull { match ->
            if (ignoredRanges.any { range -> match.range.first in range } ||
                source.hasDeclarationKeywordBefore(match.range.first)
            ) {
                return@mapNotNull null
            }
            val renderer = match.groupValues[2]
            val delimiter = match.groupValues[3]
            if (renderer == "Layout" && delimiter == "{") {
                return@mapNotNull null
            }
            val hasComposeQualifier = match.groupValues[1].isNotEmpty()
            if (!hasComposeQualifier) {
                val isPartOfQualifiedCall = match.range.first > 0 && source[match.range.first - 1] == '.'
                val isShadowedByLocalDeclaration = localRendererDeclarations[renderer]
                    ?.any { declarationOffset -> declarationOffset < match.range.first }
                    ?: false
                if (isPartOfQualifiedCall || renderer !in importedRenderers || isShadowedByLocalDeclaration) {
                    return@mapNotNull null
                }
            }
            KotlinLayoutRendererSignal(
                range = match.range,
                renderer = renderer,
            )
        }
        .toList()
}

internal fun slotWrapperRendererSignals(source: String): List<KotlinSlotWrapperSignal> {
    val ignoredRanges = source.layoutRendererIgnoredRanges()
    return slotWrapperRegex.findAll(source)
        .mapNotNull { match ->
            if (ignoredRanges.any { range -> match.range.first in range }) return@mapNotNull null
            val nameGroup = match.groups[1] ?: return@mapNotNull null
            KotlinSlotWrapperSignal(range = nameGroup.range, composable = nameGroup.value)
        }
        .toList()
}

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
private val layoutRendererRegex = Regex("\\b(?:(androidx\\.compose\\.ui\\.layout)\\.)?(Layout|SubcomposeLayout)\\s*(\\(|\\{)")
private val composeLayoutImportRegex = Regex("""(?m)^\s*import\s+androidx\.compose\.ui\.layout\.(Layout|SubcomposeLayout|\*)\s*$""")
private val declarationKeywordBeforeRendererRegex = Regex("""\b(class|object|interface|fun)\s+$""")
private val localLayoutRendererDeclarationRegex = Regex("""\b(?:class|object|interface|fun)\s+(Layout|SubcomposeLayout)\b""")
private val layoutRendererNames = setOf("Layout", "SubcomposeLayout")
private val slotWrapperRegex =
    Regex(
        """@Composable\b[\s\S]{0,400}?\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\bcontent\s*:\s*@Composable\b[^()]*\([^)]*\)\s*->\s*Unit""",
    )

private fun String.layoutRendererIgnoredRanges(): List<IntRange> = kotlinSourceQuotedStringRegex.findAll(this).map { it.range }.toList() + commentRanges()

private fun String.hasDeclarationKeywordBefore(offset: Int): Boolean {
    val lineStart = lastIndexOf('\n', startIndex = offset).let { index ->
        if (index == -1) 0 else index + 1
    }
    return declarationKeywordBeforeRendererRegex.containsMatchIn(substring(lineStart, offset))
}

private fun String.importedComposeLayoutRenderers(ignoredRanges: List<IntRange>): Set<String> = composeLayoutImportRegex.findAll(this)
    .filterNot { match -> ignoredRanges.any { range -> match.range.first in range } }
    .flatMap { match ->
        if (match.groupValues[1] == "*") {
            layoutRendererNames
        } else {
            setOf(match.groupValues[1])
        }
    }
    .toSet()

private fun String.localLayoutRendererDeclarationOffsets(ignoredRanges: List<IntRange>): Map<String, List<Int>> = localLayoutRendererDeclarationRegex.findAll(this)
    .filterNot { match -> ignoredRanges.any { range -> match.range.first in range } }
    .groupBy(
        keySelector = { match -> match.groupValues[1] },
        valueTransform = { match -> match.range.first },
    )

internal fun String.commentRanges(): List<IntRange> {
    val stringRanges = kotlinSourceQuotedStringRegex.findAll(this).map { it.range }.toList()
    val ranges = mutableListOf<IntRange>()
    var stringIndex = 0
    var index = 0
    while (index < length - 1) {
        val stringRange = stringRanges.getOrNull(stringIndex)
        if (stringRange != null && index > stringRange.last) {
            stringIndex += 1
            continue
        }
        if (stringRange != null && index in stringRange) {
            index = stringRange.last + 1
            continue
        }
        when {
            this[index] == '/' && this[index + 1] == '/' -> {
                val lineEnd = indexOf('\n', startIndex = index + 2).let { end ->
                    if (end == -1) length - 1 else end - 1
                }
                ranges += index..lineEnd
                index = lineEnd + 1
            }
            this[index] == '/' && this[index + 1] == '*' -> {
                val commentStart = index
                index += 2
                var depth = 1
                while (index < length - 1 && depth > 0) {
                    when {
                        this[index] == '/' && this[index + 1] == '*' -> {
                            depth += 1
                            index += 2
                        }
                        this[index] == '*' && this[index + 1] == '/' -> {
                            depth -= 1
                            index += 2
                        }
                        else -> index += 1
                    }
                }
                val commentEnd = if (depth == 0) index - 1 else length - 1
                ranges += commentStart..commentEnd
                index = commentEnd + 1
            }
            else -> index += 1
        }
    }
    return ranges
}
