package dev.ayuislands.settings.mappings

import com.intellij.util.ui.EmptyIcon
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ProjectLanguageVerdict
import javax.swing.Icon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            "Accent source: Language override (Kotlin, 90%)\n" +
                "Detected in this project: Kotlin (90%) • Java (10%)",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `summary renders detected winner when scan proportions are unavailable`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "kotlin",
                        weights = null,
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.LANGUAGE_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "Accent source: Language override (Kotlin)\n" +
                "Detected in this project: Kotlin",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `detected language icon renders beside scan value instead of source value`() {
        val icon = EmptyIcon.create(16)
        val panel = panel(languageIconForId = { icon })

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "java",
                        weights = mapOf("java" to 1_000L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.PROJECT_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        val labels = panel.labelsForTest()

        assertNull(labels.first { it.second == "Project override" }.first)
        assertEquals(icon, labels.first { it.second == "Java (100%)" }.first)
    }

    @Test
    fun `detected language with visible tail renders icon beside detected breakdown`() {
        val icon = EmptyIcon.create(16)
        val panel = panel(languageIconForId = { icon })

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

        val labels = panel.labelsForTest()

        assertEquals(icon, labels.first { it.second == "Kotlin (90%) • Java (10%)" }.first)
    }

    @Test
    fun `summary renders detected winner with language fallback source`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "typescript",
                        weights = mapOf("typescript" to 900L, "javascript" to 100L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            listOf(
                "Accent source: Language fallback override (TypeScript, 90%)",
                "Detected in this project: TypeScript (90%) • JavaScript (10%)",
            ).joinToString("\n"),
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `summary renders manual language when fallback source is active`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "kotlin",
                        weights = mapOf("kotlin" to 1_000L),
                    ),
                forcedLanguageId = "typescript",
                fallbackHex = "#5CCFE6",
                activeSource = AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "Accent source: Language fallback override (TypeScript, manual)\n" +
                "Detected in this project: TypeScript (manual)",
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
            "Accent source: Global\n" +
                "Detected in this project: Polyglot — no single dominant language. Global accent applies.\n" +
                "JavaScript 50% · TypeScript 50%",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `summary explains polyglot project override keeps project accent`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.NoWinner(
                        mapOf("typescript" to 500L, "javascript" to 500L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.PROJECT_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "Accent source: Project override\n" +
                "Detected in this project: Polyglot — no single dominant language. Project override applies.\n" +
                "JavaScript 50% · TypeScript 50%",
            panel.currentSummaryForTest(),
        )
    }

    @Test
    fun `no winner detail renders language icons beside each named percentage`() {
        val icon = EmptyIcon.create(16)
        val panel = panel(languageIconForId = { icon })

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.NoWinner(
                        mapOf("kotlin" to 700L, "java" to 300L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )

        val labels = panel.labelsForTest()

        assertEquals(icon, labels.first { it.second == "Kotlin 70%" }.first)
        assertEquals(icon, labels.first { it.second == "Java 30%" }.first)
        assertNull(labels.first { it.second == "·" }.first)
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
            "Accent source: Language override (TypeScript, manual)\n" +
                "Detected in this project: TypeScript (manual)",
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
            listOf(
                "Accent source: Project fallback #5CCFE6",
                "Detected in this project: Polyglot — no single dominant language. Project fallback applies.",
                "JavaScript 50% · TypeScript 50%",
            ).joinToString("\n"),
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
        assertEquals(
            "Accent source: Global\nDetected in this project: Detection pending",
            panel.currentSummaryForTest(),
        )

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
        assertEquals(
            "Accent source: Global\nDetected in this project: No project languages detected",
            panel.currentSummaryForTest(),
        )

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
        assertEquals(
            "Accent source: Global\nDetected in this project: Project language detection unavailable",
            panel.currentSummaryForTest(),
        )
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

        panel.triggerActionForTest(ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL)

        assertEquals(listOf("#5CCFE6"), calls.fallbacks)
        assertEquals(emptyList(), calls.forcedLanguages)
    }

    @Test
    fun `no winner without current accent hides fallback action`() {
        val calls = ResolutionCalls()
        val panel = panel(calls = calls, currentAccentHex = { null })

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
                canSetFallbackToCurrentAccent = false,
            ),
        )

        val labels = panel.labelsForTest().map { it.second }
        assertFalse(ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL in labels)
        assertTrue("Force TypeScript" in labels)
        assertEquals(emptyList(), calls.fallbacks)
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

        panel.triggerActionForTest("Force TypeScript")

        assertEquals(listOf("typescript"), calls.forcedLanguages)
    }

    @Test
    fun `empty no-winner scan explains unresolved proportions`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict = ProjectLanguageVerdict.NoWinner(emptyMap()),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.GLOBAL,
                canMutate = true,
                canRescan = false,
            ),
        )

        assertEquals(
            "Accent source: Global\n" +
                "Detected in this project: Polyglot — no single dominant language. Global accent applies.\n" +
                "unresolved",
            panel.currentSummaryForTest(),
        )
        assertTrue("unresolved" in panel.labelsForTest().map { it.second })
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

        panel.triggerActionForTest("Force Kotlin")

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
    fun `rescan action ignores duplicate click while detector is busy`() {
        val calls = ResolutionCalls()
        val panel = panel(calls = calls, canRescanNow = { false })

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

        panel.triggerActionForTest(ProjectLanguageResolutionPanel.RESCAN_LABEL)

        assertEquals(0, calls.rescanCount)
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

        panel.triggerActionForTest(ProjectLanguageResolutionPanel.CLEAR_FORCED_LANGUAGE_LABEL)
        panel.triggerActionForTest(ProjectLanguageResolutionPanel.CLEAR_FALLBACK_LABEL)

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

    @Test
    fun `diagnostics labels escape ampersands in language ids`() {
        val panel = panel()

        panel.refresh(
            ProjectLanguageResolutionPanel.State(
                verdict =
                    ProjectLanguageVerdict.Detected(
                        languageId = "bridge&router",
                        weights = mapOf("bridge&router" to 1_000L),
                    ),
                forcedLanguageId = null,
                fallbackHex = null,
                activeSource = AccentResolver.Source.LANGUAGE_OVERRIDE,
                canMutate = true,
                canRescan = false,
            ),
        )

        val labels = panel.labelsForTest().map { it.second }
        assertTrue(labels.any { it.contains("Bridge&amp;router") })
        assertFalse(labels.any { it.contains("Bridge&router") })
    }

    private fun panel(
        calls: ResolutionCalls = ResolutionCalls(),
        currentAccentHex: () -> String? = { "#5CCFE6" },
        canRescanNow: () -> Boolean = { true },
        languageIconForId: (String) -> Icon? = { null },
    ): ProjectLanguageResolutionPanel =
        ProjectLanguageResolutionPanel(
            currentAccentHex = currentAccentHex,
            onSetFallback = calls.fallbacks::add,
            onSetForcedLanguage = calls.forcedLanguages::add,
            onClearForcedLanguage = { calls.clearForcedCount += 1 },
            onClearFallback = { calls.clearFallbackCount += 1 },
            onRescan = { calls.rescanCount += 1 },
            canRescanNow = canRescanNow,
            languageIconForId = languageIconForId,
        )

    private data class ResolutionCalls(
        val fallbacks: MutableList<String> = mutableListOf(),
        val forcedLanguages: MutableList<String> = mutableListOf(),
        var clearForcedCount: Int = 0,
        var clearFallbackCount: Int = 0,
        var rescanCount: Int = 0,
    )
}
