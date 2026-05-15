package io.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import java.io.File

internal class KotlinSourceScanner(
    projectDirectory: File,
    rootProjectDirectory: File,
    private val json: Json,
) {
    private val projectDirectory = projectDirectory.canonicalFile
    private val rootProjectDirectory = rootProjectDirectory.canonicalFile

    fun scan(file: File): List<SourceIndexEntryAsset> = scanKotlinFile(file)

    private fun scanKotlinFile(file: File): List<SourceIndexEntryAsset> {
        val source = file.readText()
        val lines = source.lineSequence().toList()
        val lineStartOffsets = source.lineStartOffsets()
        val packageName = packageRegex.find(source)?.groupValues?.get(1)
        val classDeclarations = classRegex.findAll(source)
            .map { match -> match.range.first to match.groupValues[2] }
            .toList()
        val recognizedStringRanges = recognizedUiStringRanges(source)
        val entriesByLine = linkedMapOf<Int, SourceIndexEntryBuilder>()
        var pendingComposable = false

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            if (line.contains("@Composable")) {
                pendingComposable = true
            }

            val functionMatch = functionRegex.find(line)
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

        return entriesByLine.values.map { it.toAsset() }
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
    ) {
        quotedStringRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    text += value
                    if (recognizedStringRanges.none { it.contains(match.range) }) {
                        addSignal(SourceSignalKindAsset.ARBITRARY_STRING_LITERAL, value)
                    }
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
        textCallRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
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
    ) {
        stringResourceRegex.findAll(source).forEach { match ->
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
        testTagRegex.findAll(source).forEach { match ->
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
                    if (value.isStrictCompTestTag()) {
                        addSignal(SourceSignalKindAsset.STRICT_COMP_TEST_TAG, value)
                    }
                    addSignal(SourceSignalKindAsset.TEST_TAG, value)
                }
        }
        contentDescriptionRegex.findAll(source).forEach { match ->
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
            file = sourceFile.relativeToOrSelf(projectDirectory).invariantSeparatorsPath,
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
            textCallRegex.findAll(source) +
                testTagRegex.findAll(source) +
                contentDescriptionRegex.findAll(source)
        return recognizedMatches.map { it.range }.toList()
    }

    private fun IntRange.contains(other: IntRange): Boolean = first <= other.first && last >= other.last

    private fun String.isStrictCompTestTag(): Boolean = strictCompTestTagRegex.matches(this)

    private fun String.lineStartOffsets(): IntArray {
        val offsets = mutableListOf(0)
        forEachIndexed { index, char ->
            if (char == '\n' && index + 1 < length) {
                offsets += index + 1
            }
        }
        return offsets.toIntArray()
    }

    private fun MatchResult.startLine(lineStartOffsets: IntArray): Int {
        val offset = range.first
        val insertionPoint = lineStartOffsets.binarySearch(offset)
        return if (insertionPoint >= 0) {
            insertionPoint + 1
        } else {
            -insertionPoint - 1
        }
    }

    private companion object {
        val quotedStringRegex = Regex("\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\"")
        val textCallRegex = Regex("\\bText\\s*\\(\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
        val stringResourceRegex = Regex("\\bstringResource\\s*\\(\\s*R\\.string\\.([A-Za-z0-9_]+)")
        val testTagRegex = Regex("\\btestTag\\s*\\(\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
        val contentDescriptionRegex =
            Regex("\\bcontentDescription\\s*=\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
        val functionRegex = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        val packageRegex = Regex("\\bpackage\\s+([A-Za-z_][A-Za-z0-9_.]*)")
        val classRegex = Regex("\\b(class|object|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val strictCompTestTagRegex = Regex("""comp:[A-Za-z_][A-Za-z0-9_]*:.+""")
    }
}
