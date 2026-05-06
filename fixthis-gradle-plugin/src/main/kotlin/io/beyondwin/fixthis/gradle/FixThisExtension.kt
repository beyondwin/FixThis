package io.beyondwin.fixthis.gradle

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

abstract class FixThisExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val enabled: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val runtimeVersion: Property<String> =
        objects.property(String::class.java).convention("0.1.0")

    val addDebugRuntime: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val generateSourceIndex: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val generateProjectMetadata: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val includeScreenshots: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val redactEditableText: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)
}
