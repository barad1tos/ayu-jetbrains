package dev.ayuislands.settings.mappings

import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ProjectLanguageVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectLanguageResolutionPanelTest {
    @Test
    fun `summary renders detected winner with active source`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "kotlin",
                        weights = mapOf("kotlin" to 900L, "java" to 100L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.LANGUAGE_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "Detected: Kotlin 90% - using Language override",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `summary renders no winner proportions with active source`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.NoWinner(
                        mapOf("typescript" to 500L, "javascript" to 500L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "No dominant language: JavaScript 50% - TypeScript 50% - using Global",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `summary prefers pending forced language over detector verdict`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "kotlin",
                        weights = mapOf("kotlin" to 1_000L),
                    ),
                forcedLanguageId = "typescript",
                fallbackHex = null,
                activeSource = AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "Forced language: TypeScript - using Forced language override",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `summary appends fallback hex when project fallback is active`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.NoWinner(
                        mapOf("typescript" to 500L, "javascript" to 500L),
                    ),
                forcedLanguageId = null,
                fallbackHex = "#5CCFE6",
                activeSource = AccentResolver.Source.PROJECT_FALLBACK,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "No dominant language: JavaScript 50% - TypeScript 50% - using Project fallback #5CCFE6",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `summary renders clear one-line states for cold empty and unavailable verdicts`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict = ProjectLanguageVerdict.Cold,
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )
        assertEquals("Detection pending - using Global", panel.currentSummaryForTest())

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict = ProjectLanguageVerdict.Empty,
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )
        assertEquals("No project languages detected - using Global", panel.currentSummaryForTest())

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict = ProjectLanguageVerdict.Unavailable,
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )
        assertEquals("Project language detection unavailable - using Global", panel.currentSummaryForTest())
    }

    @Test
    fun `no winner actions set fallback to current accent without forcing tied top language`() {
        val calls = ResolutionCalls()
        val panel = panel(calls = calls)

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.NoWinner(
                        mapOf("typescript" to 500L, "javascript" to 500L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )

        val labels = panel.labelsForTest().map { it.second }
        assertTrue(ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL in labels)
        assertFalse(labels.any { it.startsWith("Force ") })

        panel.clickLabelForTest(ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL)

        assertEquals(listOf("#5CCFE6"), calls.fallbacks)
        assertEquals(emptyList(), calls.forcedLanguages)
    }

    @Test
    fun `no winner actions force unique top language`() {
        val calls = ResolutionCalls()
        val panel = panel(calls = calls)

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.NoWinner(
                        mapOf("typescript" to 700L, "javascript" to 300L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )

        val labels = panel.labelsForTest().map { it.second }
        assertTrue("Force TypeScript" in labels)

        panel.clickLabelForTest("Force TypeScript")

        assertEquals(listOf("typescript"), calls.forcedLanguages)
    }

    @Test
    fun `detected action forces detected language`() {
        val calls = ResolutionCalls()
        val panel = panel(calls = calls)

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "kotlin",
                        weights = mapOf("kotlin" to 1_000L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.LANGUAGE_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        panel.clickLabelForTest("Force Kotlin")

        assertEquals(listOf("kotlin"), calls.forcedLanguages)
    }

    @Test
    fun `rescan action exposes tooltip metadata`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict = ProjectLanguageVerdict.Cold,
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = true,
            ),
        )

        val rescanLabel =
            panel
                .labelsForTest()
                .first { it.second == ProjectLanguageResolutionPanel.RESCAN_LABEL }

        assertEquals(
            ProjectLanguageResolutionPanel.RESCAN_TOOLTIP,
            rescanLabel.third,
        )
    }

    @Test
    fun `clear actions clear existing forced language and fallback`() {
        val calls = ResolutionCalls()
        val panel = panel(calls = calls)

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict = ProjectLanguageVerdict.Cold,
                forcedLanguageId = "typescript",
                fallbackHex = "#5CCFE6",
                activeSource = AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        val labels = panel.labelsForTest().map { it.second }
        assertTrue(ProjectLanguageResolutionPanel.CLEAR_FORCED_LANGUAGE_LABEL in labels)
        assertTrue(ProjectLanguageResolutionPanel.CLEAR_FALLBACK_LABEL in labels)

        panel.clickLabelForTest(ProjectLanguageResolutionPanel.CLEAR_FORCED_LANGUAGE_LABEL)
        panel.clickLabelForTest(ProjectLanguageResolutionPanel.CLEAR_FALLBACK_LABEL)

        assertEquals(1, calls.clearForcedCount)
        assertEquals(1, calls.clearFallbackCount)
    }

    @Test
    fun `diagnostics labels do not expose raw html for hostile language ids`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "<html><b>evil</b></html>",
                        weights = mapOf("<html><b>evil</b></html>" to 1_000L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.LANGUAGE_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        val labels = panel.labelsForTest().map { it.second }
        assertFalse(labels.any { it.contains("<html", ignoreCase = true) })
    }

    private fun panel(
        calls: ResolutionCalls = ResolutionCalls(),
        currentAccentHex: () -> String? = { "#5CCFE6" },
        canRescanNow: () -> Boolean = { true },
    ): ProjectLanguageResolutionPanel =
        ProjectLanguageResolutionPanel(
            currentAccentHex = currentAccentHex,
            onSetFallback = calls.fallbacks::add,
            onSetForcedLanguage = calls.forcedLanguages::add,
            onClearForcedLanguage = { calls.clearForcedCount += 1 },
            onClearFallback = { calls.clearFallbackCount += 1 },
            onRescan = { calls.rescanCount += 1 },
            canRescanNow = canRescanNow,
        )

    private data class ResolutionCalls(
        val fallbacks: MutableList<String> = mutableListOf(),
        val forcedLanguages: MutableList<String> = mutableListOf(),
        var clearForcedCount: Int = 0,
        var clearFallbackCount: Int = 0,
        var rescanCount: Int = 0,
    )
}
