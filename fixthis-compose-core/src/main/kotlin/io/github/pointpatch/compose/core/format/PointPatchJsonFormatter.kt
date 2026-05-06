package io.github.pointpatch.compose.core.format

import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PointPatchJsonFormatter {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun format(annotation: PointPatchAnnotation): String = json.encodeToString(annotation)
}
