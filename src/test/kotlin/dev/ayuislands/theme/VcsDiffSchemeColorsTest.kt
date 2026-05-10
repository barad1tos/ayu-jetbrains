package dev.ayuislands.theme

import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression lock for VCS/diff colors that are easy to judge only after
 * opening a diff editor. Dark, Mirage, and Light variants share the same
 * key set; this test pins the upstream Ayu blue ramp on every variant so a
 * future palette tweak can't silently regress one family while the other
 * stays correct. Modified backgrounds also stay visibly distinct from added
 * (green) backgrounds — a colour-distance assertion catches "they look too
 * similar on dense diffs" before users do.
 */
class VcsDiffSchemeColorsTest {
    @Test
    fun `selections stay translucent across all variants (alpha-led, not solid)`() {
        assertColorOption("/themes/AyuIslandsDark.xml", "SELECTION_BACKGROUND", "3388FF40")
        assertColorOption("/themes/AyuIslandsDark.xml", "INACTIVE_SELECTION_BACKGROUND", "80B5FF26")
        assertColorOption("/themes/AyuIslandsMirage.xml", "SELECTION_BACKGROUND", "409FFF40")
        assertColorOption("/themes/AyuIslandsMirage.xml", "INACTIVE_SELECTION_BACKGROUND", "409FFF21")
        assertColorOption("/themes/AyuIslandsLight.xml", "SELECTION_BACKGROUND", "035BD626")
        assertColorOption("/themes/AyuIslandsLight.xml", "INACTIVE_SELECTION_BACKGROUND", "035BD612")
    }

    @Test
    fun `modified VCS accents use the upstream Ayu blue ramp on every variant`() {
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
        assertModifiedVcsRamp(
            resource = "/themes/AyuIslandsLight.xml",
            expectedBlue = "478ACC",
            expectedDiffBackground = "478ACC1F",
        )
    }

    @Test
    fun `modified diff backgrounds are blue-led and separated from added backgrounds`() {
        assertModifiedBackgroundContrast("/themes/AyuIslandsDark.xml")
        assertModifiedBackgroundContrast("/themes/AyuIslandsMirage.xml")
        assertModifiedBackgroundContrast("/themes/AyuIslandsLight.xml")
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
        assertDiffModifiedOption(resource, "BACKGROUND", expectedDiffBackground)
        assertDiffModifiedOption(resource, "ERROR_STRIPE_COLOR", expectedBlue)
    }

    private fun assertModifiedBackgroundContrast(resource: String) {
        val added = colorOption(resource, "DIFF_INSERTED")
        val modified = diffModifiedOption(resource, "BACKGROUND")

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

    private fun assertDiffModifiedOption(
        resource: String,
        option: String,
        expected: String,
    ) {
        assertEquals(
            expected,
            diffModifiedOption(resource, option).hex,
            "$resource DIFF_MODIFIED $option",
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

    /**
     * Reads a nested DIFF_MODIFIED attribute option (`<option name="DIFF_MODIFIED">
     * <value><option name="$option" value="..."/></value></option>`) via DOM +
     * XPath so the test stops being brittle to whitespace, attribute ordering,
     * or intermediate `<option>` siblings inside `<value>`. Secure-XML defaults
     * mirror the licensing free-tier lockdown reader: DTDs disallowed, external
     * entities off, FEATURE_SECURE_PROCESSING on — the XML lives on our own
     * classpath so the threat surface is zero, but the hardening is free.
     */
    private fun diffModifiedOption(
        resource: String,
        option: String,
    ): HexColor {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        val document =
            javaClass
                .getResourceAsStream(resource)
                ?.use { factory.newDocumentBuilder().parse(it) }
                ?: error("Scheme XML not found on classpath: $resource")
        val xpath = "//option[@name='DIFF_MODIFIED']/value/option[@name='$option']"
        val node =
            XPathFactory
                .newInstance()
                .newXPath()
                .evaluate(xpath, document, XPathConstants.NODE) as? Element
        assertNotNull(node, "$resource missing $option in DIFF_MODIFIED attribute")
        val value = node.getAttribute("value")
        assertTrue(value.isNotBlank(), "$resource DIFF_MODIFIED/$option has blank value")
        return HexColor.from(value)
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
