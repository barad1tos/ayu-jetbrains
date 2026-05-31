package dev.ayuislands.theme

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression lock for JetBrains `.ignore` plugin color keys. Ayu Islands does
 * not depend on the optional plugin; editor schemes define the static
 * TextAttributesKey names so the plugin can consume them when installed.
 */
class IgnorePluginSchemeKeysTest {
    private val requiredIgnoreKeys =
        setOf(
            "IGNORE.COMMENT",
            "IGNORE.SECTION",
            "IGNORE.HEADER",
            "IGNORE.NEGATION",
            "IGNORE.BRACKET",
            "IGNORE.SLASH",
            "IGNORE.SYNTAX",
            "IGNORE.VALUE",
            "IGNORE.UNUSED_ENTRY",
        )

    private val baseSchemes =
        listOf(
            "/themes/AyuIslandsDark.xml",
            "/themes/AyuIslandsMirage.xml",
            "/themes/AyuIslandsLight.xml",
        )

    private val extendedOverlays =
        listOf(
            "/themes/extended/AyuIslandsDark.extended.xml",
            "/themes/extended/AyuIslandsMirage.extended.xml",
            "/themes/extended/AyuIslandsLight.extended.xml",
        )

    private val expectedDarkIgnoreColors =
        mapOf(
            "IGNORE.COMMENT" to "AAD94C",
            "IGNORE.SECTION" to "FFB454",
            "IGNORE.HEADER" to "D2A6FF",
            "IGNORE.NEGATION" to "F07178",
            "IGNORE.BRACKET" to "F29668",
            "IGNORE.SLASH" to "ACB6BF",
            "IGNORE.SYNTAX" to "FF8F40",
            "IGNORE.VALUE" to "95E6CB",
            "IGNORE.UNUSED_ENTRY" to "ACB6BF",
        )

    private val expectedMirageIgnoreColors =
        mapOf(
            "IGNORE.COMMENT" to "D5FF80",
            "IGNORE.SECTION" to "FFD173",
            "IGNORE.HEADER" to "DFBFFF",
            "IGNORE.NEGATION" to "F28779",
            "IGNORE.BRACKET" to "F29E74",
            "IGNORE.SLASH" to "B8CFE6",
            "IGNORE.SYNTAX" to "FFAD66",
            "IGNORE.VALUE" to "95E6CB",
            "IGNORE.UNUSED_ENTRY" to "B8CFE6",
        )

    private val expectedLightIgnoreColors =
        mapOf(
            "IGNORE.COMMENT" to "86B300",
            "IGNORE.SECTION" to "EBA400",
            "IGNORE.HEADER" to "A37ACC",
            "IGNORE.NEGATION" to "F07171",
            "IGNORE.BRACKET" to "F2A191",
            "IGNORE.SLASH" to "ADAEB1",
            "IGNORE.SYNTAX" to "FA8532",
            "IGNORE.VALUE" to "4CBF99",
            "IGNORE.UNUSED_ENTRY" to "ADAEB1",
        )

    private val expectedIgnoreForegrounds =
        mapOf(
            "/themes/AyuIslandsDark.xml" to expectedDarkIgnoreColors,
            "/themes/AyuIslandsMirage.xml" to expectedMirageIgnoreColors,
            "/themes/AyuIslandsLight.xml" to expectedLightIgnoreColors,
            "/themes/extended/AyuIslandsDark.extended.xml" to expectedDarkIgnoreColors,
            "/themes/extended/AyuIslandsMirage.extended.xml" to expectedMirageIgnoreColors,
            "/themes/extended/AyuIslandsLight.extended.xml" to expectedLightIgnoreColors,
        )

    @Test
    fun `base editor schemes define complete ignore plugin key set`() {
        for (resource in baseSchemes) {
            assertIgnoreKeys(resource)
        }
    }

    @Test
    fun `extended overlays define complete ignore plugin key set`() {
        for (resource in extendedOverlays) {
            assertIgnoreKeys(resource)
        }
    }

    @Test
    fun `ignore plugin remains optional`() {
        val pluginXml = readPluginXml()

        assertFalse(
            pluginXml.contains("mobi.hsz.idea.gitignore"),
            "Ayu Islands should expose static IGNORE.* color keys without depending on the optional .ignore plugin.",
        )
    }

    @Test
    fun `ignore keys use the full variant palette`() {
        for ((resource, expectedColors) in expectedIgnoreForegrounds) {
            for ((key, expectedColor) in expectedColors) {
                assertEquals(
                    expectedColor,
                    foregroundOption(resource, key),
                    "$resource must render $key with its assigned variant color.",
                )
            }
        }
    }

    private fun assertIgnoreKeys(resource: String) {
        val actual = optionNames(resource).filterTo(mutableSetOf()) { it.startsWith("IGNORE.") }

        assertEquals(
            requiredIgnoreKeys,
            actual,
            "$resource must define exactly the upstream .ignore plugin color key set.",
        )
    }

    private fun optionNames(resource: String): Set<String> {
        val document = readDocument(resource)
        val nodes = document.documentElement.getElementsByTagName("option")
        return buildSet {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                if (name.isNotBlank()) add(name)
            }
        }
    }

    private fun foregroundOption(
        resource: String,
        key: String,
    ): String {
        val document = readDocument(resource)
        val nodes = document.documentElement.getElementsByTagName("option")

        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttribute("name") != key) continue

            return childOption(element, "FOREGROUND")
                ?: error("$resource missing FOREGROUND for $key")
        }

        error("$resource missing $key")
    }

    private fun childOption(
        parent: Element,
        name: String,
    ): String? {
        val childOptions = parent.getElementsByTagName("option")
        for (index in 0 until childOptions.length) {
            val child = childOptions.item(index) as? Element ?: continue
            if (child.getAttribute("name") == name) return child.getAttribute("value")
        }

        return null
    }

    private fun readDocument(resource: String): Document =
        readResourceStream(resource).use { stream ->
            documentBuilderFactory().newDocumentBuilder().parse(stream)
        }

    private fun readPluginXml(): String {
        val stream = readResourceStream("/META-INF/plugin.xml")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun readResourceStream(path: String) =
        javaClass.getResourceAsStream(path)
            ?: error("Resource not found on classpath: $path")

    private fun documentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature(
                "http" + "://apache.org/xml/features/disallow-doctype-decl",
                true,
            )
            setFeature(
                "http" + "://xml.org/sax/features/external-general-entities",
                false,
            )
            setFeature(
                "http" + "://xml.org/sax/features/external-parameter-entities",
                false,
            )
        }
}
