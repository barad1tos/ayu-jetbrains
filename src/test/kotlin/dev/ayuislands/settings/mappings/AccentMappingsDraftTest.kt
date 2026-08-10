package dev.ayuislands.settings.mappings

import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccentMappingsDraftTest {
    @Test
    fun `load preserves platform and coroutine cancellation from language display lookup`() {
        val state =
            AccentMappingsState().apply {
                languageAccents["kotlin"] = "#112233"
            }
        val platformCancellation = ProcessCanceledException()
        val coroutineCancellation = CancellationException("settings closed")

        assertSame(
            platformCancellation,
            assertFailsWith<ProcessCanceledException> {
                AccentMappingsDraft().load(state, languageDisplayName = { throw platformCancellation })
            },
        )
        assertSame(
            coroutineCancellation,
            assertFailsWith<CancellationException> {
                AccentMappingsDraft().load(state, languageDisplayName = { throw coroutineCancellation })
            },
        )
    }

    @Test
    fun `load keeps valid sibling rows while reporting malformed rows and lookup failure`() {
        val state =
            AccentMappingsState().apply {
                projectAccents["/tmp/project"] = "#AABBCC"
                projectDisplayNames["/tmp/project"] = "Project"
                projectAccents["/tmp/broken-project"] = "not-a-color"
                languageAccents["kotlin"] = "#112233"
                languageAccents["groovy"] = "#334455"
                languageAccents["TypeScript"] = "#556677"
                languageAccents["python"] = "not-a-color"
                projectFallbackAccents["/tmp/project"] = " #5CCFE6 "
                projectFallbackAccents[" "] = "#FFB454"
                forcedProjectLanguages["/tmp/project"] = " TypeScript "
                languageFallbackAccent = " #73D0FF "
            }
        val lookupFailure = IllegalStateException("language registry unavailable")
        val warnings = mutableListOf<String>()
        val reportedFailures = mutableListOf<Pair<String, Throwable>>()

        val draft = AccentMappingsDraft()
        draft.load(
            state,
            languageDisplayName = { languageId ->
                when (languageId) {
                    "kotlin" -> "Kotlin"
                    "groovy" -> throw lookupFailure
                    else -> null
                }
            },
            warn = warnings::add,
            reportLookupFailure = { message, failure ->
                reportedFailures += message to failure
            },
        )

        assertEquals("#AABBCC", draft.projectAccent("/tmp/project"))
        assertNull(draft.projectAccent("/tmp/broken-project"))
        assertEquals("#112233", draft.languageAccent("kotlin"))
        assertEquals("#334455", draft.languageAccent("groovy"))
        assertEquals("groovy", draft.languageMappings.single { it.languageId == "groovy" }.displayName)
        assertNull(draft.languageAccent("TypeScript"))
        assertNull(draft.languageAccent("python"))
        assertEquals("#5CCFE6", draft.projectFallbackAccent("/tmp/project"))
        assertFalse(draft.hasProjectFallbackCandidate(" "))
        assertEquals("typescript", draft.forcedLanguageId("/tmp/project"))
        assertTrue(draft.hasForcedLanguageEntry("/tmp/project"))
        assertEquals("#73D0FF", draft.languageFallbackAccent)
        assertFalse(draft.isModified)
        assertTrue(warnings.any { "path='/tmp/broken-project'" in it && "not-a-color" in it })
        assertTrue(warnings.any { "id='TypeScript'" in it && "#556677" in it })
        assertTrue(warnings.any { "id='python'" in it && "not-a-color" in it })
        assertEquals(1, reportedFailures.size)
        assertTrue("id='groovy'" in reportedFailures.single().first)
        assertSame(lookupFailure, reportedFailures.single().second)
    }

    @Test
    fun `reset restores loaded state and mark committed advances the baseline`() {
        val state = AccentMappingsState()
        val draft = AccentMappingsDraft()
        draft.load(state, languageDisplayName = { _ -> null })

        draft.addProject(ProjectMapping("/tmp/project", "Project", "#AABBCC"))
        draft.setForcedLanguage("/tmp/project", "Kotlin")
        assertTrue(draft.isModified)

        draft.reset()
        assertTrue(draft.projectMappings.isEmpty())
        assertFalse(draft.isModified)

        draft.addProject(ProjectMapping("/tmp/project", "Project", "#AABBCC"))
        draft.setForcedLanguage("/tmp/project", "Kotlin")
        draft.writeTo(state)

        assertEquals(mapOf("/tmp/project" to "#AABBCC"), state.projectAccents)
        assertEquals(mapOf("/tmp/project" to "kotlin"), state.forcedProjectLanguages)
        assertTrue(draft.isModified)

        draft.markCommitted()

        assertFalse(draft.isModified)
    }

    @Test
    fun `reset restores every loaded mapping domain and project display name`() {
        val state =
            AccentMappingsState().apply {
                projectAccents["/tmp/project"] = "#AABBCC"
                projectDisplayNames["/tmp/project"] = "Custom project name"
                languageAccents["kotlin"] = "#112233"
                projectFallbackAccents["/tmp/project"] = "#5CCFE6"
                forcedProjectLanguages["/tmp/project"] = "kotlin"
                languageFallbackAccent = "#73D0FF"
            }
        val draft = AccentMappingsDraft()
        draft.load(state, languageDisplayName = { "Kotlin" })

        draft.removeProject(0)
        draft.removeLanguage(0)
        draft.setProjectFallbackAccent("/tmp/project", null)
        draft.setForcedLanguage("/tmp/project", null)
        draft.setLanguageFallbackAccent(null)

        draft.reset()
        val restored = AccentMappingsState()
        draft.writeTo(restored)

        assertEquals(mapOf("/tmp/project" to "#AABBCC"), restored.projectAccents)
        assertEquals(mapOf("/tmp/project" to "Custom project name"), restored.projectDisplayNames)
        assertEquals(mapOf("kotlin" to "#112233"), restored.languageAccents)
        assertEquals(mapOf("/tmp/project" to "#5CCFE6"), restored.projectFallbackAccents)
        assertEquals(mapOf("/tmp/project" to "kotlin"), restored.forcedProjectLanguages)
        assertEquals("#73D0FF", restored.languageFallbackAccent)
        assertFalse(draft.isModified)
    }

    @Test
    fun `mutations replace snapshots without changing previously exposed rows`() {
        val draft = AccentMappingsDraft()
        val firstProject = ProjectMapping("/tmp/project", "Project", "#AABBCC")
        val firstLanguage = LanguageMapping("kotlin", "Kotlin", "#112233")

        draft.addProject(firstProject)
        draft.addLanguage(firstLanguage)
        val exposedProjects = draft.projectMappings
        val exposedLanguages = draft.languageMappings

        draft.addProject(ProjectMapping("/tmp/other", "Other", "#334455"))
        draft.addLanguage(LanguageMapping("python", "Python", "#556677"))
        draft.updateProjectHex(0, "#778899")
        draft.updateLanguageHex(0, "#99AABB")

        assertEquals(listOf(firstProject), exposedProjects)
        assertEquals(listOf(firstLanguage), exposedLanguages)
        assertEquals("#778899", draft.projectAccent("/tmp/project"))
        assertEquals("#99AABB", draft.languageAccent("kotlin"))
        assertTrue(draft.hasLanguageAccents)

        draft.removeProject(-1)
        draft.removeProject(99)
        draft.removeLanguage(-1)
        draft.removeLanguage(99)
        assertEquals(2, draft.projectMappings.size)
        assertEquals(2, draft.languageMappings.size)

        draft.removeProject(1)
        draft.removeLanguage(1)
        assertEquals(1, draft.projectMappings.size)
        assertEquals(1, draft.languageMappings.size)
    }

    @Test
    fun `resolution mutations normalize values and remove cleared entries`() {
        val draft = AccentMappingsDraft()

        draft.setProjectFallbackAccent("/tmp/project", " #5CCFE6 ")
        draft.setForcedLanguage("/tmp/project", " TypeScript ")
        draft.setLanguageFallbackAccent(" #73D0FF ")

        assertEquals("#5CCFE6", draft.projectFallbackAccent("/tmp/project"))
        assertTrue(draft.hasProjectFallbackCandidate("/tmp/project"))
        assertEquals("typescript", draft.forcedLanguageId("/tmp/project"))
        assertTrue(draft.hasForcedLanguageEntry("/tmp/project"))
        assertEquals("#73D0FF", draft.languageFallbackAccent)

        draft.setProjectFallbackAccent("/tmp/project", null)
        draft.setForcedLanguage("/tmp/project", null)
        draft.setLanguageFallbackAccent(null)

        assertNull(draft.projectFallbackAccent("/tmp/project"))
        assertFalse(draft.hasProjectFallbackCandidate("/tmp/project"))
        assertNull(draft.forcedLanguageId("/tmp/project"))
        assertFalse(draft.hasForcedLanguageEntry("/tmp/project"))
        assertNull(draft.languageFallbackAccent)
    }

    @Test
    fun `writeTo replaces every persisted mapping collection`() {
        val state =
            AccentMappingsState().apply {
                projectAccents["/tmp/old"] = "#000000"
                projectDisplayNames["/tmp/old"] = "Old"
                languageAccents["java"] = "#000000"
                projectFallbackAccents["/tmp/old"] = "#000000"
                forcedProjectLanguages["/tmp/old"] = "java"
                languageFallbackAccent = "#000000"
            }
        val draft = AccentMappingsDraft()
        draft.addProject(ProjectMapping("/tmp/project", "Project", "#AABBCC"))
        draft.addLanguage(LanguageMapping("kotlin", "Kotlin", "#112233"))
        draft.setProjectFallbackAccent("/tmp/project", "#5CCFE6")
        draft.setForcedLanguage("/tmp/project", "typescript")
        draft.setLanguageFallbackAccent("#73D0FF")

        draft.writeTo(state)

        assertEquals(mapOf("/tmp/project" to "#AABBCC"), state.projectAccents)
        assertEquals(mapOf("/tmp/project" to "Project"), state.projectDisplayNames)
        assertEquals(mapOf("kotlin" to "#112233"), state.languageAccents)
        assertEquals(mapOf("/tmp/project" to "#5CCFE6"), state.projectFallbackAccents)
        assertEquals(mapOf("/tmp/project" to "typescript"), state.forcedProjectLanguages)
        assertEquals("#73D0FF", state.languageFallbackAccent)
    }

    @Test
    fun `duplicate row lookups preserve latest table mapping semantics`() {
        val draft = AccentMappingsDraft()
        draft.addProject(ProjectMapping("/tmp/project", "First", "#111111"))
        draft.addProject(ProjectMapping("/tmp/project", "Latest", "#222222"))
        draft.addLanguage(LanguageMapping("kotlin", "First", "#333333"))
        draft.addLanguage(LanguageMapping("kotlin", "Latest", "#444444"))

        assertEquals("#222222", draft.projectAccent("/tmp/project"))
        assertEquals("#444444", draft.languageAccent("kotlin"))
    }
}
