package io.beyondwin.fixthis.compose.core.domain.annotation

interface AnnotationRepository {
    suspend fun save(annotation: Annotation): Annotation
}
