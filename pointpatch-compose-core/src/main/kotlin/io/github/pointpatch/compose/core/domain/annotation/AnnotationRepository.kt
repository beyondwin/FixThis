package io.github.pointpatch.compose.core.domain.annotation

interface AnnotationRepository {
    suspend fun save(annotation: Annotation): Annotation
}
