package dev.ayuislands

import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KotlinRuntimeCompatibilityTest {
    @Test
    fun `generated coroutine metadata remains readable by the oldest supported IDE`() {
        val probe: suspend () -> Unit = { yield() }
        val debugMetadata =
            assertNotNull(
                probe.javaClass.annotations.firstOrNull {
                    it.annotationClass.qualifiedName == DEBUG_METADATA_CLASS
                },
            )
        val metadataVersion =
            debugMetadata.annotationClass.java
                .getDeclaredMethod(METADATA_VERSION_MEMBER)
                .invoke(debugMetadata) as Int

        assertEquals(
            SUPPORTED_METADATA_VERSION,
            metadataVersion,
            "IntelliJ Platform 2025.1 only supports coroutine debug metadata version 1",
        )
    }

    private companion object {
        const val DEBUG_METADATA_CLASS = "kotlin.coroutines.jvm.internal.DebugMetadata"
        const val METADATA_VERSION_MEMBER = "v"
        const val SUPPORTED_METADATA_VERSION = 1
    }
}
