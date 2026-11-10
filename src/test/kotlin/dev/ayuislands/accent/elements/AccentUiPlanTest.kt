package dev.ayuislands.accent.elements

import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.theme.AyuEditorSchemeScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.awt.Color
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccentUiPlanTest {
    @Test
    fun `plain UI plans retain native order alpha and color identity without effects`() {
        mockkObject(AyuEditorSchemeScope)
        every { AyuEditorSchemeScope.activeScheme() } throws IllegalStateException("Planning must not access a scheme")
        val events = mutableListOf<String>()
        val listener = PropertyChangeListener { events += it.propertyName }
        UIManager.getDefaults().addPropertyChangeListener(listener)
        try {
            val progress = ProgressBarElement().uiColors(Color.ORANGE)
            assertEquals(
                listOf("ProgressBar.foreground", "ProgressBar.progressCounterBackground"),
                progress.keys.toList(),
            )
            progress.values.forEach { assertSame(Color.ORANGE, it) }
            val links = LinksElement().uiColors(Color.ORANGE)
            assertEquals(
                listOf(
                    "Link.activeForeground",
                    "Link.hoverForeground",
                    "Link.secondaryForeground",
                    "Notification.linkForeground",
                    "GotItTooltip.linkForeground",
                    "Tooltip.Learning.linkForeground",
                ),
                links.keys.toList(),
            )
            links.values.forEach { assertSame(Color.ORANGE, it) }
            val scrollbar = ScrollbarElement().uiColors(Color.ORANGE)
            assertEquals(16, scrollbar.size)
            assertEquals("ScrollBar.hoverThumbBorderColor", scrollbar.keys.first())
            assertEquals("ScrollBar.Mac.Transparent.thumbColor", scrollbar.keys.last())
            assertEquals(List(8) { 0x8C } + List(8) { 0x59 }, scrollbar.values.map { it.alpha })
            assertTrue(events.isEmpty())
        } finally {
            UIManager.getDefaults().removePropertyChangeListener(listener)
            unmockkAll()
        }
    }

    @Test
    fun `search observes six output-specific backgrounds before pure blending`() =
        withDefaults {
            val element = SearchResultsElement()
            val backgrounds = listOf(Color.BLACK, Color.WHITE, Color.GREEN, Color.BLUE, Color.GRAY, Color.CYAN)
            var reads = 0
            for (key in BACKGROUNDS) {
                UIManager.put(key, UIDefaults.ActiveValue { backgrounds[reads++] })
            }
            val observed = element.observeBackgrounds(Color.RED)
            assertEquals(6, reads)
            assertEquals(backgrounds, observed.values.toList())
            assertEquals(element.uiKeys, observed.keys.toList())
            BACKGROUNDS.forEach {
                UIManager.put(
                    it,
                    UIDefaults.ActiveValue { error("Pure blend performed a native read") },
                )
            }
            val plan = element.uiColors(Color.RED, observed)
            val expected = listOf(0x260000, 0xFFE5E5, 0x26D900, 0x1A00E5, 0x936D6D, 0x1AE5E5)
            assertEquals(expected, plan.values.map { it.rgb and 0xFFFFFF })
            assertTrue(plan.values.all { it.alpha == 255 })
            assertEquals(6, reads)
        }

    @Test
    fun `search legacy apply preserves read write interleaving and sees a callback changed background`() =
        withDefaults {
            val element = SearchResultsElement()
            val events = mutableListOf<String>()
            BACKGROUNDS.forEach { key ->
                UIManager.put(
                    key,
                    UIDefaults.ActiveValue {
                        events += "read:$key"
                        Color.BLACK
                    },
                )
            }
            val listener =
                PropertyChangeListener {
                    if (it.propertyName in element.uiKeys) events += "write:${it.propertyName}"
                    if (it.propertyName == "List.selectionBackground") {
                        UIManager.put(
                            "List.background",
                            UIDefaults.ActiveValue {
                                events += "read:List.background"
                                Color.WHITE
                            },
                        )
                    }
                }
            UIManager.getDefaults().addPropertyChangeListener(listener)
            try {
                element.apply(Color.RED)
            } finally {
                UIManager.getDefaults().removePropertyChangeListener(listener)
            }
            val expected =
                element.uiKeys.flatMap {
                    listOf(
                        "read:${it.substringBefore(".selection")}.background",
                        "write:$it",
                    )
                }
            assertEquals(expected, events)
            assertEquals(0xFFE5E5, UIManager.getColor("List.selectionInactiveBackground").rgb and 0xFFFFFF)
        }

    private fun withDefaults(action: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val keys = BACKGROUNDS + SearchResultsElement().uiKeys
            val original = keys.associateWith { readRawUiDefault(UIManager.getDefaults(), it) }
            try {
                action()
            } finally {
                original.forEach { (key, value) -> UIManager.put(key, value) }
            }
        }
    }

    companion object {
        private val BACKGROUNDS = listOf("List.background", "Tree.background", "Table.background")
    }
}
