package dev.ayuislands.theme

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression lock for VCS/diff colors that are easy to judge only after
 * opening a diff editor. Dark-family selections must stay translucent, and
 * modified highlights must stay visibly blue instead of drifting toward the
 * same green band used for added lines.
 */
class VcsDiffSchemeColorsTest {
    @Test
    fun `dark-family selections match upstream Ayu translucent selection colors`() {
        assertColorOption("/themes/AyuIslandsDark.xml", "SELECTION_BACKGROUND", "3388FF40")
        assertColorOption("/themes/AyuIslandsDark.xml", "INACTIVE_SELECTION_BACKGROUND", "80B5FF26")
        assertColorOption("/themes/AyuIslandsMirage.xml", "SELECTION_BACKGROUND", "409FFF40")
        assertColorOption("/themes/AyuIslandsMirage.xml", "INACTIVE_SELECTION_BACKGROUND", "409FFF21")
    }

    @Test
    fun `dark-family modified VCS accents use the upstream Ayu blue ramp`() {
        assertModifiedVcsRamp(
            resource = "/themes/AyuIslandsDark.xml",
            expectedBlue = "73B8FF",
            expectedDiffBackground = "2E4560",
        )
        assertModifiedVcsRamp(
            resource = "/themes/AyuIslandsMirage.xml",
            expectedBlue = "80BFFF",
            expectedDiffBackground = "405672",
        )
    }

    @Test
    fun `modified diff backgrounds are blue-led and separated from added backgrounds`() {
        assertModifiedBackgroundContrast("/themes/AyuIslandsDark.xml")
        assertModifiedBackgroundContrast("/themes/AyuIslandsMirage.xml")
    }

    private fun assertModifiedVcsRamp(
        resource: String,
        expectedBlue: String,
        expectedDiffBackground: String,
    ) {
        val modifiedKeys =
            listOf(
                "MODIFIED_LINES_COLOR",
                "IGNORED_MODIFIED_LINES_BORDER_COLOR",
                "FILESTATUS_MODIFIED",
                "FILESTATUS_NOT_CHANGED_IMMEDIATE",
                "FILESTATUS_NOT_CHANGED_RECURSIVE",
                "FILESTATUS_RENAMED",
                "FILESTATUS_modifiedOutside",
                "DIFF_MODIFIED",
            )

        for (key in modifiedKeys) {
            assertColorOption(resource, key, expectedBlue)
        }
        assertDiffAttributeOption(resource, "DIFF_MODIFIED", "BACKGROUND", expectedDiffBackground)
        assertDiffAttributeOption(resource, "DIFF_MODIFIED", "ERROR_STRIPE_COLOR", expectedBlue)
    }

    private fun assertModifiedBackgroundContrast(resource: String) {
        val added = colorOption(resource, "DIFF_INSERTED")
        val modified = diffAttributeOption(resource, "DIFF_MODIFIED", "BACKGROUND")

        assertTrue(
            modified.blue > modified.green && modified.blue > modified.red,
            "$resource DIFF_MODIFIED background should be led by blue, got ${modified.hex}",
        )
        assertTrue(
            colorDistance(added, modified) >= MIN_ADDED_MODIFIED_DISTANCE,
            "$resource added (${added.hex}) and modified (${modified.hex}) diff backgrounds " +
                "are too close; keep modified visibly blue.",
        )
    }

    private fun assertColorOption(
        resource: String,
        key: String,
        expected: String,
    ) {
        assertEquals(expected, colorOption(resource, key).hex, "$resource $key")
    }

    private fun assertDiffAttributeOption(
        resource: String,
        attribute: String,
        option: String,
        expected: String,
    ) {
        assertEquals(
            expected,
            diffAttributeOption(resource, attribute, option).hex,
            "$resource $attribute $option",
        )
    }

    private fun colorOption(
        resource: String,
        key: String,
    ): HexColor {
        val xml = readResource(resource)
        val match = Regex("""<option\s+name="$key"\s+value="([0-9A-Fa-f]{6,8})"\s*/>""").find(xml)
        assertNotNull(match, "$resource missing top-level color option $key")
        return HexColor.from(match.groupValues[1])
    }

    private fun diffAttributeOption(
        resource: String,
        attribute: String,
        option: String,
    ): HexColor {
        val xml = readResource(resource)
        val patternSource =
            """<option\s+name="$attribute">\s*<value>""" +
                """.*?<option\s+name="$option"\s+value="([0-9A-Fa-f]{6,8})"\s*/>""" +
                """.*?</value>\s*</option>"""
        val pattern = Regex(patternSource, RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(xml)
        assertNotNull(match, "$resource missing $option in $attribute attribute")
        return HexColor.from(match.groupValues[1])
    }

    private fun readResource(path: String): String {
        val stream =
            javaClass.getResourceAsStream(path)
                ?: error("Scheme XML not found on classpath: $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private data class HexColor(
        val hex: String,
        val red: Int,
        val green: Int,
        val blue: Int,
    ) {
        companion object {
            fun from(value: String): HexColor {
                val normalized = value.uppercase()
                return HexColor(
                    hex = normalized,
                    red = normalized.substring(0, 2).toInt(16),
                    green = normalized.substring(2, 4).toInt(16),
                    blue = normalized.substring(4, 6).toInt(16),
                )
            }
        }
    }

    private companion object {
        const val MIN_ADDED_MODIFIED_DISTANCE = 50.0

        fun colorDistance(
            first: HexColor,
            second: HexColor,
        ): Double {
            val red = first.red - second.red
            val green = first.green - second.green
            val blue = first.blue - second.blue
            return sqrt((red * red + green * green + blue * blue).toDouble())
        }
    }
}
