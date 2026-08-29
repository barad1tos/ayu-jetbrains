package dev.ayuislands.settings

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextLanguageControllerTest {
    private val project = mockk<Project> { every { name } returns "sample" }

    @Test
    fun `automatic refresh stays automatic while manual selection blocks later refresh`() {
        val detectedLanguages = ArrayDeque(listOf("Kotlin", "Swift", "Java"))
        lateinit var refresh: () -> Unit
        val controllerReference = AtomicReference<ContextLanguageController>()
        val applied = mutableListOf<String>()
        val controller =
            ContextLanguageController(
                resolve = { _, _, _ -> detectedLanguages.removeFirst() },
                subscribe = { _, listener ->
                    refresh = listener
                    {}
                },
            )
        controllerReference.set(controller)

        val initial =
            controller.start(project, activeFile = null, fallback = "Ruby") { detected ->
                controllerReference.get().select(detected)
                applied += detected
            }
        refresh()
        refresh()
        controller.select("Ruby")
        refresh()

        assertEquals("Kotlin", initial)
        assertEquals(listOf("Swift", "Java"), applied)
    }

    @Test
    fun `reopening re-evaluates active file while preserving the supplied fallback`() {
        val resolvedFiles = mutableListOf<String?>()
        val fallbacks = mutableListOf<String>()
        val controller =
            ContextLanguageController(
                resolve = { _, activeFile, fallback ->
                    resolvedFiles += activeFile?.fileName
                    fallbacks += fallback
                    activeFile?.languageIds?.single() ?: fallback
                },
                subscribe = { _, _ -> error("Active file must not subscribe to project refresh") },
            )

        val swift = controller.start(project, activeFile("Preview.swift", "Swift"), "Ruby") {}
        val kotlin = controller.start(project, activeFile("Preview.kt", "Kotlin"), "Ruby") {}

        assertEquals("Swift", swift)
        assertEquals("Kotlin", kotlin)
        assertEquals(listOf<String?>("Preview.swift", "Preview.kt"), resolvedFiles)
        assertEquals(listOf("Ruby", "Ruby"), fallbacks)
    }

    private fun activeFile(
        fileName: String,
        language: String,
    ): ActiveFileContext =
        ActiveFileContext(
            fileName = fileName,
            fileTypeName = language,
            languageIds = setOf(language),
        )
}
