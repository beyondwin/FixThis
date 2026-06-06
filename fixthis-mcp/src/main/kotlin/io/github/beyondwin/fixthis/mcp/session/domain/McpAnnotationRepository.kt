package io.github.beyondwin.fixthis.mcp.session.domain

import io.github.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationRepository
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.dto.toAnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.toDomainAnnotation

class McpAnnotationRepository(
    private val store: FeedbackSessionStore,
) : AnnotationRepository {
    override suspend fun save(annotation: Annotation): Annotation {
        val dto = annotation.toAnnotationDto()
        store.addOrReplaceAnnotationForDomain(annotation.sessionId.value, dto)
        return store
            .getSession(annotation.sessionId.value)
            .items
            .first { it.itemId == annotation.id.value }
            .toDomainAnnotation(annotation.sessionId.value)
    }
}
