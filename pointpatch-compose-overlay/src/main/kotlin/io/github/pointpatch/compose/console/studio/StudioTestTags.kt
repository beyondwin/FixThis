package io.github.pointpatch.compose.console.studio

internal object StudioTestTags {
    const val ToolSelect = "studio:tool:select"
    const val ToolAnnotate = "studio:tool:annotate"
    const val EmptyStartAnnotating = "studio:empty:start-annotating"
    const val AnnotationDetail = "studio:annotation:detail"

    fun annotationRow(index: Int): String = "studio:annotation:row:$index"

    fun widget(tag: String): String = "studio:widget:$tag"
}
