package io.github.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import java.io.File

internal class KotlinSourceScanner(
    projectDirectory: File,
    rootProjectDirectory: File,
    private val json: Json,
    private val includeLayoutRendererSignals: Boolean = true,
    private val conventionPatterns: List<Regex> = emptyList(),
) {
    private val projectDirectory = projectDirectory.canonicalFile
    private val rootProjectDirectory = rootProjectDirectory.canonicalFile

    fun scan(
        file: File,
        stringResourceResolver: Map<String, String> = emptyMap(),
    ): List<SourceIndexEntryAsset> = scanKotlinFile(file, stringResourceResolver)

    private fun scanKotlinFile(
        file: File,
        stringResourceResolver: Map<String, String>,
    ): List<SourceIndexEntryAsset> {
        val source = file.readText()
        val lines = source.lineSequence().toList()
        val lineStartOffsets = source.lineStartOffsets()
        val packageName = kotlinSourcePackageRegex.find(source)?.groupValues?.get(1)
        val classDeclarations = kotlinSourceClassRegex.findAll(source)
            .map { match -> match.range.first to match.groupValues[2] }
            .toList()
        val recognizedStringRanges = recognizedUiStringRanges(source)
        val ownersByLine = composableOwnerByLine(lines)
        val entriesByLine = linkedMapOf<Int, SourceIndexEntryBuilder>()
        var pendingComposable = false

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            if (line.contains("@Composable")) {
                pendingComposable = true
            }

            val functionMatch = kotlinSourceFunctionRegex.find(line)
            if (functionMatch != null) {
                if (pendingComposable || line.contains("@Composable")) {
                    val symbol = functionMatch.groupValues[1]
                    entriesByLine.entryFor(
                        file = file,
                        lineNumber = lineNumber,
                        line = line,
                        packageName = packageName,
                        className = classNameAt(lineStartOffsets.getOrElse(index) { 0 }, classDeclarations),
                    ).apply {
                        symbols += symbol
                        addSignal(SourceSignalKindAsset.COMPOSABLE_SYMBOL, symbol)
                    }
                }
                pendingComposable = false
            }
        }

        collectRecognizedStringSignals(
            file = file,
            source = source,
            lines = lines,
            lineStartOffsets = lineStartOffsets,
            recognizedStringRanges = recognizedStringRanges,
            entriesByLine = entriesByLine,
            packageName = packageName,
            classDeclarations = classDeclarations,
            ownersByLine = ownersByLine,
        )
        collectTextCallSignals(
            file = file,
            source = source,
            lines = lines,
            lineStartOffsets = lineStartOffsets,
            entriesByLine = entriesByLine,
            packageName = packageName,
            classDeclarations = classDeclarations,
        )
        collectStringResourceSignals(
            file = file,
            source = source,
            lines = lines,
            lineStartOffsets = lineStartOffsets,
            entriesByLine = entriesByLine,
            packageName = packageName,
            classDeclarations = classDeclarations,
            resolver = stringResourceResolver,
        )
        collectModifierSignals(
            file = file,
            source = source,
            lines = lines,
            lineStartOffsets = lineStartOffsets,
            entriesByLine = entriesByLine,
            packageName = packageName,
            classDeclarations = classDeclarations,
        )
        if (includeLayoutRendererSignals) {
            collectLayoutRendererSignals(
                file = file,
                source = source,
                lines = lines,
                lineStartOffsets = lineStartOffsets,
                entriesByLine = entriesByLine,
                packageName = packageName,
                classDeclarations = classDeclarations,
            )
        }
        collectSemanticModifierSignals(source, stringResourceResolver) { range ->
            entriesByLine.entryFor(
                file = file,
                lineNumber = range.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(range.first, classDeclarations),
            )
        }

        entriesByLine.forEach { (lineNumber, builder) ->
            ownersByLine.getOrNull(lineNumber - 1)?.let { ownerName ->
                builder.addSignal(SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION, ownerName)
            }
        }

        return entriesByLine.toSortedMap().values.map { it.toAsset() }
    }

    private fun collectRecognizedStringSignals(
        file: File,
        source: String,
        lines: List<String>,
        lineStartOffsets: IntArray,
        recognizedStringRanges: List<IntRange>,
        entriesByLine: MutableMap<Int, SourceIndexEntryBuilder>,
        packageName: String?,
        classDeclarations: List<Pair<Int, String>>,
        ownersByLine: Array<String?>,
    ) {
        kotlinSourceQuotedStringRegex.findAll(source).forEach { match ->
            val lineNumber = match.startLine(lineStartOffsets)
            val outsideRecognizedRange = recognizedStringRanges.none { it.contains(match.range) }
            if (outsideRecognizedRange && ownersByLine.getOrNull(lineNumber - 1) == null) return@forEach
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = lineNumber,
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    text += value
                    if (outsideRecognizedRange) addSignal(SourceSignalKindAsset.ARBITRARY_STRING_LITERAL, value)
                }
        }
    }

    private fun collectTextCallSignals(
        file: File,
        source: String,
        lines: List<String>,
        lineStartOffsets: IntArray,
        entriesByLine: MutableMap<Int, SourceIndexEntryBuilder>,
        packageName: String?,
        classDeclarations: List<Pair<Int, String>>,
    ) {
        kotlinSourceTextCallRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.literalStartLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    text += value
                    addSignal(SourceSignalKindAsset.UI_TEXT, value)
                }
        }
    }

    private fun collectStringResourceSignals(
        file: File,
        source: String,
        lines: List<String>,
        lineStartOffsets: IntArray,
        entriesByLine: MutableMap<Int, SourceIndexEntryBuilder>,
        packageName: String?,
        classDeclarations: List<Pair<Int, String>>,
        resolver: Map<String, String>,
    ) {
        kotlinSourceStringResourceRegex.findAll(source).forEach { match ->
            val resourceName = match.groupValues[1]
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    stringResources += resourceName
                    addSignal(SourceSignalKindAsset.STRING_RESOURCE, resourceName)
                    resolver[resourceName]?.let { resolved ->
                        text += resolved
                        addSignal(SourceSignalKindAsset.STRING_RESOURCE_RESOLVED, resolved)
                    }
                }
        }
    }

    private fun collectModifierSignals(
        file: File,
        source: String,
        lines: List<String>,
        lineStartOffsets: IntArray,
        entriesByLine: MutableMap<Int, SourceIndexEntryBuilder>,
        packageName: String?,
        classDeclarations: List<Pair<Int, String>>,
    ) {
        kotlinSourceTestTagRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    testTags += value
                    if (value.isStrictCompTestTag(conventionPatterns)) {
                        addSignal(SourceSignalKindAsset.STRICT_COMP_TEST_TAG, value)
                    }
                    addSignal(SourceSignalKindAsset.TEST_TAG, value)
                }
        }
        kotlinSourceContentDescriptionRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    contentDescriptions += value
                    addSignal(SourceSignalKindAsset.CONTENT_DESCRIPTION, value)
                }
        }
    }

    private fun collectLayoutRendererSignals(
        file: File,
        source: String,
        lines: List<String>,
        lineStartOffsets: IntArray,
        entriesByLine: MutableMap<Int, SourceIndexEntryBuilder>,
        packageName: String?,
        classDeclarations: List<Pair<Int, String>>,
    ) {
        layoutRendererSignals(source).forEach { signal ->
            entriesByLine.entryFor(
                file = file,
                lineNumber = signal.range.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(signal.range.first, classDeclarations),
            ).apply {
                addSignal(SourceSignalKindAsset.LAYOUT_RENDERER, signal.renderer)
            }
        }
        slotWrapperRendererSignals(source).forEach { signal ->
            entriesByLine.entryFor(
                file = file,
                lineNumber = signal.range.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(signal.range.first, classDeclarations),
            ).apply {
                addSignal(SourceSignalKindAsset.LAYOUT_RENDERER, signal.composable)
            }
        }
        lazyItemOwnerSignals(source).forEach { signal ->
            entriesByLine.entryFor(
                file = file,
                lineNumber = signal.range.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(signal.range.first, classDeclarations),
            ).apply {
                addSignal(SourceSignalKindAsset.LAZY_ITEM_OWNER, signal.composable)
            }
        }
        navDestinationOwnerSignals(source).forEach { signal ->
            entriesByLine.entryFor(
                file = file,
                lineNumber = signal.range.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(signal.range.first, classDeclarations),
            ).apply {
                addSignal(SourceSignalKindAsset.NAV_DESTINATION_OWNER, signal.composable)
            }
        }
        modifierTargetSignals(source).forEach { signal ->
            entriesByLine.entryFor(
                file = file,
                lineNumber = signal.range.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(signal.range.first, classDeclarations),
            ).apply {
                addSignal(SourceSignalKindAsset.MODIFIER_TARGET, signal.value)
                contentDescriptions += signal.value
            }
        }
    }

    private fun decodeKotlinString(match: MatchResult): String {
        val singleLineLiteral = match.groups[2]?.value
        if (singleLineLiteral != null) {
            return runCatching { json.decodeFromString<String>("\"$singleLineLiteral\"") }
                .getOrElse { singleLineLiteral }
        }
        return match.groups[1]?.value.orEmpty()
    }

    private fun MutableMap<Int, SourceIndexEntryBuilder>.entryFor(
        file: File,
        lineNumber: Int,
        line: String,
        packageName: String? = null,
        className: String? = null,
    ): SourceIndexEntryBuilder = getOrPut(lineNumber) {
        val sourceFile = file.canonicalFile
        SourceIndexEntryBuilder(
            file = sourceFile.relativeSourcePath(projectDirectory, rootProjectDirectory),
            repoFile = sourceFile.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath,
            line = lineNumber,
            excerpt = line.trim(),
            packageName = packageName,
            className = className,
        )
    }

    private fun MutableMap<Int, SourceIndexEntryBuilder>.entryFor(
        file: File,
        lineNumber: Int,
        lines: List<String>,
        packageName: String? = null,
        className: String? = null,
    ): SourceIndexEntryBuilder = entryFor(
        file = file,
        lineNumber = lineNumber,
        line = lines.getOrNull(lineNumber - 1).orEmpty(),
        packageName = packageName,
        className = className,
    )

    private fun classNameAt(
        offset: Int,
        classDeclarations: List<Pair<Int, String>>,
    ): String? = classDeclarations.lastOrNull { (classOffset, _) -> classOffset <= offset }?.second

    private fun recognizedUiStringRanges(source: String): List<IntRange> {
        val recognizedMatches =
            kotlinSourceTextCallRegex.findAll(source) +
                kotlinSourceTestTagRegex.findAll(source) +
                kotlinSourceContentDescriptionRegex.findAll(source)
        return recognizedMatches.map { it.range }.toList()
    }
}
