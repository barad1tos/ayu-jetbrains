package dev.ayuislands.syntax

import dev.ayuislands.settings.AyuIslandsConfigurable
import dev.ayuislands.settings.AyuIslandsSettingsPanel
import dev.ayuislands.settings.AyuIslandsSyntaxPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Syntax tab wiring assertions for `AyuIslandsConfigurable`.
 *
 * Coverage:
 *  - `AyuIslandsSyntaxPanel` implements the real `AyuIslandsSettingsPanel`
 *    interface (compile-time assignability assertion).
 *  - The configurable constructs `AyuIslandsSyntaxPanel` and registers it in
 *    the `panels` dispatch list.
 *  - `syntaxPanel` is registered in the `panels` list so apply/reset/
 *    isModified dispatches to it.
 *
 * Reflection covers dispatch wiring without reading production source. Tab
 * title order is covered by the Settings tab assembly tests.
 */
class SettingsConfigurableSyntaxTabWiringTest {
    @Test
    fun `AyuIslandsSyntaxPanel is assignable to AyuIslandsSettingsPanel`() {
        // Compile-time + runtime interface check. A future refactor that
        // accidentally drops the interface declaration (or renames the
        // base interface) would break this assertion.
        val panel: AyuIslandsSettingsPanel = AyuIslandsSyntaxPanel()
        assertTrue(
            panel is AyuIslandsSyntaxPanel,
            "AyuIslandsSyntaxPanel must remain assignable to the AyuIslandsSettingsPanel contract.",
        )
    }

    @Test
    fun `configurable constructs syntax panel and registers it in panel dispatch list`() {
        val configurable = AyuIslandsConfigurable()
        val syntaxPanel = readField<AyuIslandsSyntaxPanel>(configurable, "syntaxPanel")
        val panels = readField<List<*>>(configurable, "panels")

        assertEquals(
            1,
            panels.count { panel -> panel === syntaxPanel },
            "panels dispatch list must include the constructed syntaxPanel exactly once",
        )
        assertTrue(
            panels.all { panel -> panel is AyuIslandsSettingsPanel },
            "panels dispatch list must contain only AyuIslandsSettingsPanel instances",
        )
    }

    private inline fun <reified T> readField(
        instance: Any,
        fieldName: String,
    ): T {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        val value = field.get(instance)
        assertTrue(
            value is T,
            "Expected field '$fieldName' to be ${T::class.simpleName}, got ${value?.javaClass?.name}",
        )
        return value
    }
}
