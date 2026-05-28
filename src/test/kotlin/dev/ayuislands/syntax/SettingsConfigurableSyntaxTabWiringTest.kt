package dev.ayuislands.syntax

import dev.ayuislands.settings.AyuIslandsConfigurable
import dev.ayuislands.settings.AyuIslandsSettingsPanel
import dev.ayuislands.settings.AyuIslandsSyntaxPanel
import java.nio.file.Files
import java.nio.file.Path
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
 *  - The "Syntax" tab sits between "Glow" and "VCS" (placement contract).
 *
 * Reflection covers dispatch wiring without reading production source. The
 * remaining source assertion is limited to tab title order, which is the
 * user-visible contract not exposed by a cheap unit-level API.
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
    fun `SYNTAX_TAB_INDEX compiled constant places Syntax between Glow and VCS`() {
        val field = AyuIslandsConfigurable::class.java.getDeclaredField("SYNTAX_TAB_INDEX")
        assertEquals(
            3,
            field.getInt(null),
            "SYNTAX_TAB_INDEX must be 3 — placement contract: " +
                "Accent | Font | Glow | Syntax | VCS | Workspace | Plugins",
        )
    }

    @Test
    fun `createPanel inserts Syntax tab between Glow and VCS`() {
        val source = readConfigurableSource()
        val titlePattern = Regex("""tabs\.(?:addTab|insertTab)\("([^"]+)"""")
        val contentTitles =
            titlePattern
                .findAll(source)
                .map { match -> match.groupValues[1] }
                .filter { title -> title.isNotEmpty() }
                .take(7)
                .toList()
        assertEquals(
            listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
            contentTitles,
            "Settings tabs must keep Syntax between Glow and VCS",
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

    private fun readConfigurableSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/settings/AyuIslandsConfigurable.kt"),
        )

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
