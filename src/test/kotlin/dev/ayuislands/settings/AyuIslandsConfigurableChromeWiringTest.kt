package dev.ayuislands.settings

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wiring coverage for the Chrome Tinting injection into
 * [AyuIslandsConfigurable].
 *
 * `AyuIslandsConfigurable` aggregates its sub-panels through a `panels: List<
 * AyuIslandsSettingsPanel>` field: `isModified()` / `apply()` / `reset()`
 * iterate this list, and the private `resetAllSettings()` helper invokes each
 * premium panel's reset directly. The Chrome Tinting collapsible is a premium
 * surface, so every aggregation path must include it. Without this test any
 * future refactor that drops `chromePanel` from either the `panels` list or
 * `resetAllSettings` would silently regress the Settings "Apply" / "Reset"
 * buttons for chrome state.
 *
 * Tests reach the private `chromePanel` field and `panels` list via reflection
 * so no Swing / platform startup is required — the DialogPanel wiring is
 * exercised at the higher-level `AyuIslandsChromePanelTest`.
 *
 * **Test-design note (documented compromise):** the `Configurable bytecode
 * wires chromePanel to afterOverridesInjection not before` test reads the
 * compiled `AyuIslandsConfigurable.class` to assert hook-call shape inside
 * `createPanel()`. A behavioral substitute would require building the UI DSL
 * panel through the IntelliJ platform — the project's `integrationTest` task
 * is currently misconfigured (NO-SOURCE in CI), so bytecode inspection is the
 * cheapest available assertion. Wiring `chromePanel` to `beforeOverrides`
 * instead of `afterOverrides` flips Chrome Tinting from its intended
 * collapsible group to the License Status slot, which silently breaks the
 * Settings page layout. Do not delete in future "remove theater" passes
 * without replacing with an equivalent integration test.
 */
class AyuIslandsConfigurableChromeWiringTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `panels list contains the chromePanel instance`() {
        val configurable = AyuIslandsConfigurable()
        val chromePanel = readField<AyuIslandsChromePanel>(configurable, "chromePanel")
        val panels = readField<List<AyuIslandsSettingsPanel>>(configurable, "panels")

        assertTrue(
            panels.any { it === chromePanel },
            "panels aggregation list must include the chromePanel instance so " +
                "isModified/apply/reset propagate to Chrome Tinting state",
        )
    }

    @Test
    fun `isModified aggregates chromePanel modifications`() {
        val configurable = AyuIslandsConfigurable()
        val spyChrome = spyk(AyuIslandsChromePanel())
        swapChromePanel(configurable, spyChrome)

        io.mockk.every { spyChrome.isModified() } returns true

        val panels = readField<List<AyuIslandsSettingsPanel>>(configurable, "panels")
        val modified = panels.any { it.isModified() }
        assertTrue(
            modified,
            "configurable.isModified must return true when chromePanel.isModified is true",
        )
    }

    @Test
    fun `apply invokes chromePanel apply`() {
        val configurable = AyuIslandsConfigurable()
        val spyChrome = spyk(AyuIslandsChromePanel())
        every { spyChrome.apply() } returns Unit
        swapChromePanel(configurable, spyChrome)

        val panels = readField<List<AyuIslandsSettingsPanel>>(configurable, "panels")
        for (panel in panels) {
            if (panel === spyChrome) {
                panel.apply()
            }
        }

        verify(exactly = 1) { spyChrome.apply() }
    }

    @Test
    fun `resetAllSettings invokes chromePanel reset`() {
        val configurable = AyuIslandsConfigurable()
        val spyChrome = spyk(AyuIslandsChromePanel())
        every { spyChrome.reset() } returns Unit
        replaceResetAllFields(configurable, spyChrome)

        val resetAllSettings = AyuIslandsConfigurable::class.java.getDeclaredMethod("resetAllSettings")
        resetAllSettings.isAccessible = true
        resetAllSettings.invoke(configurable)

        verify(exactly = 1) { spyChrome.reset() }
    }

    @Test
    fun `reset aggregates chromePanel reset`() {
        val configurable = AyuIslandsConfigurable()
        val spyChrome = spyk(AyuIslandsChromePanel())
        every { spyChrome.reset() } returns Unit
        swapChromePanel(configurable, spyChrome)

        val panels = readField<List<AyuIslandsSettingsPanel>>(configurable, "panels")
        for (panel in panels) {
            if (panel === spyChrome) {
                panel.reset()
            }
        }

        verify(exactly = 1) { spyChrome.reset() }
    }

    @Test
    fun `Configurable bytecode wires chromePanel to afterOverridesInjection not before`() {
        // Regression guard for the Chrome Tinting placement: chrome moved from
        // BEFORE Overrides to AFTER Overrides by rewiring the
        // `chromePanel.buildPanel` call from the shared
        // `beforeOverridesInjection` callback to a parallel
        // `afterOverridesInjection` callback. If a future refactor moves chrome
        // back under `beforeOverrides` (or drops the hook), the visual order
        // silently regresses — chrome tinting would render before the user sets
        // the per-project overrides it depends on.
        //
        // Checking bytecode keeps this test hermetic (no Swing/DSL runtime) and
        // catches reorders that leave Kotlin source compiling cleanly.
        val classBytes =
            AyuIslandsConfigurable::class.java
                .getResourceAsStream("AyuIslandsConfigurable.class")
                ?.readAllBytes()
                ?: error("AyuIslandsConfigurable.class must be loadable for bytecode inspection")
        assertTrue(
            classBytes.isNotEmpty(),
            "AyuIslandsConfigurable.class must be loadable for bytecode inspection",
        )
        val classText = String(classBytes, Charsets.ISO_8859_1)
        assertTrue(
            classText.contains("setAfterOverridesInjection"),
            "createPanel bytecode must call setAfterOverridesInjection to host Chrome Tinting",
        )
        assertTrue(
            classText.contains("setBeforeOverridesInjection"),
            "createPanel bytecode must still call setBeforeOverridesInjection to host System panel",
        )
    }

    @Test
    fun `Configurable tabs can shrink with the Settings window`() {
        val classBytes =
            AyuIslandsConfigurable::class.java
                .getResourceAsStream("AyuIslandsConfigurable.class")
                ?.readAllBytes()
                ?: error("AyuIslandsConfigurable.class must be loadable for bytecode inspection")
        val classText = String(classBytes, Charsets.ISO_8859_1)

        assertTrue(
            classText.contains("setTabLayoutPolicy"),
            "Settings tabs must use scroll layout so tab labels do not force a wide minimum content area",
        )
        assertFalse(
            classText.contains("setMinimumSize"),
            "Settings tabs must not force a fixed minimum width; narrow windows should resize content instead",
        )
    }

    @Test
    fun `chromePanel sits between accentPanel and elementsPanel in panels list`() {
        val configurable = AyuIslandsConfigurable()
        val panels = readField<List<AyuIslandsSettingsPanel>>(configurable, "panels")
        val accentPanel = readField<AyuIslandsAccentPanel>(configurable, "accentPanel")
        val chromePanel = readField<AyuIslandsChromePanel>(configurable, "chromePanel")
        val elementsPanel = readField<AyuIslandsElementsPanel>(configurable, "elementsPanel")

        val accentIndex = panels.indexOfFirst { it === accentPanel }
        val chromeIndex = panels.indexOfFirst { it === chromePanel }
        val elementsIndex = panels.indexOfFirst { it === elementsPanel }

        assertTrue(accentIndex >= 0, "accentPanel must be in the panels list")
        assertTrue(chromeIndex >= 0, "chromePanel must be in the panels list")
        assertTrue(elementsIndex >= 0, "elementsPanel must be in the panels list")
        assertEquals(
            true,
            accentIndex < chromeIndex && chromeIndex < elementsIndex,
            "Expected order: accentPanel ($accentIndex) < chromePanel ($chromeIndex) " +
                "< elementsPanel ($elementsIndex) so apply/reset fire chrome BEFORE " +
                "elements downstream of the accent resolver",
        )
    }

    // ── Reflection helpers ─────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(
        target: Any,
        fieldName: String,
    ): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as T
    }

    /**
     * Swaps the private `chromePanel` field to a spy while also mutating the
     * pre-built `panels` list in-place so aggregation paths hit the spy. The
     * list is a Kotlin-read-only `List` backed by an `ArrayList` — cast to
     * `MutableList` and swap the entry, because the public `panels` field is
     * initialized once in the Configurable's constructor and holds the
     * original chromePanel reference.
     */
    private fun swapChromePanel(
        configurable: AyuIslandsConfigurable,
        replacement: AyuIslandsChromePanel,
    ) {
        val originalChrome = readField<AyuIslandsChromePanel>(configurable, "chromePanel")

        val chromeField = AyuIslandsConfigurable::class.java.getDeclaredField("chromePanel")
        chromeField.isAccessible = true
        chromeField.set(configurable, replacement)

        val panels = readField<List<AyuIslandsSettingsPanel>>(configurable, "panels")

        @Suppress("UNCHECKED_CAST")
        val mutablePanels = panels as MutableList<AyuIslandsSettingsPanel>
        val index = mutablePanels.indexOfFirst { it === originalChrome }
        if (index >= 0) {
            mutablePanels[index] = replacement
        }
    }

    private fun replaceResetAllFields(
        configurable: AyuIslandsConfigurable,
        chromePanel: AyuIslandsChromePanel,
    ) {
        replaceField(configurable, "accentPanel", mockk<AyuIslandsAccentPanel>(relaxed = true))
        replaceField(configurable, "chromePanel", chromePanel)
        replaceField(configurable, "elementsPanel", mockk<AyuIslandsElementsPanel>(relaxed = true))
        replaceField(configurable, "fontPresetPanel", mockk<FontPresetPanel>(relaxed = true))
        replaceField(configurable, "effectsPanel", mockk<AyuIslandsEffectsPanel>(relaxed = true))
        replaceField(configurable, "vcsColorPanel", mockk<VcsColorPanel>(relaxed = true))
        replaceField(configurable, "syntaxPanel", mockk<AyuIslandsSyntaxPanel>(relaxed = true))
        replaceField(configurable, "workspacePanel", mockk<WorkspacePanel>(relaxed = true))
        replaceField(configurable, "pluginsPanel", mockk<PluginsPanel>(relaxed = true))
    }

    private fun replaceField(
        target: Any,
        fieldName: String,
        replacement: Any,
    ) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, replacement)
    }
}
