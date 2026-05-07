package io.beyondwin.fixthis.compose.core.format

import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object FixThisJsonFormatter {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun format(annotation: FixThisAnnotation): String = json.encodeToString(annotation)
}
