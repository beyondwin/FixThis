package io.beyondwin.fixthis.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class FixThisExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val enabled: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val runtimeVersion: Property<String> =
        objects.property(String::class.java).convention(FixThisPluginVersion.defaultRuntimeVersion())

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
