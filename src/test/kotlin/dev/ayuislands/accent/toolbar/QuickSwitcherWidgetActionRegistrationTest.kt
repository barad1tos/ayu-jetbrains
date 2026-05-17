package dev.ayuislands.accent.toolbar

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the `plugin.xml` registration of the Quick Switcher widget action.
 *
 * The widget is consumed by the platform action system at IDE-load time; if
 * the registration drifts (wrong `class` FQN, missing `add-to-group`, wrong
 * `group-id`) the chip silently fails to appear in [MainToolbarRight] with
 * no compile-time signal. Reading `plugin.xml` from the classpath catches
 * the drift at test time.
 */
class QuickSwitcherWidgetActionRegistrationTest {
    @Test
    fun `plugin xml registers AyuIslands QuickSwitcher action with QuickSwitcherWidgetAction class`() {
        val xml = readPluginXml()
        val actionRegex =
            Regex(
                "<action[^>]*id=\"AyuIslands\\.QuickSwitcher\"[^>]*" +
                    "class=\"dev\\.ayuislands\\.accent\\.toolbar\\.QuickSwitcherWidgetAction\"",
                RegexOption.DOT_MATCHES_ALL,
            )
        val match =
            actionRegex.find(xml)
                ?: Regex(
                    "<action[^>]*class=\"dev\\.ayuislands\\.accent\\.toolbar\\.QuickSwitcherWidgetAction\"" +
                        "[^>]*id=\"AyuIslands\\.QuickSwitcher\"",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(xml)
        assertNotNull(
            match,
            "plugin.xml must register <action id=\"AyuIslands.QuickSwitcher\" " +
                "class=\"dev.ayuislands.accent.toolbar.QuickSwitcherWidgetAction\"...> " +
                "(either attribute order accepted)",
        )
    }

    @Test
    fun `plugin xml registers QuickSwitcher action into MainToolbarRight anchor last`() {
        val xml = readPluginXml()
        val actionBlockRegex =
            Regex(
                "<action[^>]*id=\"AyuIslands\\.QuickSwitcher\".*?</action>",
                RegexOption.DOT_MATCHES_ALL,
            )
        val actionBlock = actionBlockRegex.find(xml)?.value
        assertNotNull(actionBlock, "QuickSwitcher action block not found in plugin.xml")
        assertTrue(
            actionBlock.contains("group-id=\"MainToolbarRight\""),
            "QuickSwitcher action must add-to-group MainToolbarRight; got: $actionBlock",
        )
        assertTrue(
            actionBlock.contains("anchor=\"last\""),
            "QuickSwitcher action must anchor=\"last\" inside MainToolbarRight; got: $actionBlock",
        )
    }

    private fun readPluginXml(): String {
        val stream =
            javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
                ?: error("META-INF/plugin.xml not on classpath")
        return stream.bufferedReader().use { it.readText() }
    }
}
