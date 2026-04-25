package dev.ayuislands.theme

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source-regex regression lock for the three `STICKY_LINES_*` color keys
 * across all three Ayu editor color schemes. Without these keys the platform
 * falls back to a transparent default for the sticky-line panel — editor
 * text bleeds through the scope-header line. A bulk-format pass, careless
 * merge, or "remove unused color keys" refactor could silently drop one of
 * these entries from one of the three variants and the rest of the test
 * suite would not notice. This source-regex check is the algorithmic
 * defense; per CLAUDE.md "source-regex regression locks count as
 * algorithmic" coverage.
 */
class StickyLinesSchemeKeysTest {
    private val requiredKeys =
        listOf(
            "STICKY_LINES_BACKGROUND",
            "STICKY_LINES_HOVERED_COLOR",
            "STICKY_LINES_BORDER_COLOR",
        )

    private val schemeResources =
        listOf(
            "/themes/AyuIslandsMirage.xml",
            "/themes/AyuIslandsDark.xml",
            "/themes/AyuIslandsLight.xml",
        )

    @Test
    fun `every Ayu editor scheme defines all three STICKY_LINES color keys`() {
        for (resource in schemeResources) {
            val xml = readResource(resource)
            for (key in requiredKeys) {
                val pattern = """<option\s+name="$key"\s+value="[0-9A-Fa-f]{6,8}"\s*/>"""
                assertTrue(
                    Regex(pattern).containsMatchIn(xml),
                    "Scheme $resource missing or malformed `$key` — sticky-line panel will " +
                        "render transparent on this variant. Re-add the option entry with a " +
                        "6 or 8-char hex value.",
                )
            }
        }
    }

    @Test
    fun `STICKY_LINES_BACKGROUND values are distinct per variant to match each editor bg`() {
        val mirageBg = extractStickyBackground("/themes/AyuIslandsMirage.xml")
        val darkBg = extractStickyBackground("/themes/AyuIslandsDark.xml")
        val lightBg = extractStickyBackground("/themes/AyuIslandsLight.xml")

        assertNotNull(mirageBg)
        assertNotNull(darkBg)
        assertNotNull(lightBg)

        // Each variant must have its own bg value — copy-paste of the same hex
        // across variants would mean two of the three render against the wrong
        // editor background and lose the slight-elevation contrast.
        assertTrue(
            mirageBg != darkBg && darkBg != lightBg && mirageBg != lightBg,
            "STICKY_LINES_BACKGROUND must be unique per variant; got Mirage=$mirageBg " +
                "Dark=$darkBg Light=$lightBg",
        )
    }

    private fun readResource(path: String): String {
        val stream =
            javaClass.getResourceAsStream(path)
                ?: error("Scheme XML not found on classpath: $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun extractStickyBackground(resource: String): String? {
        val xml = readResource(resource)
        val match =
            Regex("""<option\s+name="STICKY_LINES_BACKGROUND"\s+value="([0-9A-Fa-f]{6,8})"\s*/>""")
                .find(xml)
        return match?.groupValues?.get(1)
    }
}
