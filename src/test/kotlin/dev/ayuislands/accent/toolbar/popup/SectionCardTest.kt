package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the [SectionCard] contract per 48-REDESIGN-SPEC §3.2:
 *  - BorderLayout NORTH = caps-header strip; CENTER = content slot,
 *  - header strip preferred height == JBUI.scale(Density.SECTION_HEADER_H),
 *  - header text is uppercase (caps treatment),
 *  - setContent installs the provided JComponent in the CENTER slot,
 *  - paintForTest renders without throwing.
 */
class SectionCardTest {
    @Test
    fun `card uses BorderLayout with NORTH header and CENTER content`() {
        val card = SectionCard(title = "Variant")
        assertTrue(card.layout is BorderLayout, "SectionCard should use BorderLayout")
        val layout = card.layout as BorderLayout
        val north = layout.getLayoutComponent(BorderLayout.NORTH)
        assertTrue(north is JLabel, "NORTH slot should hold the caps header JLabel; got ${north?.javaClass}")
    }

    @Test
    fun `header strip preferred height equals JBUI scaled SECTION_HEADER_H`() {
        val card = SectionCard(title = "Variant")
        val layout = card.layout as BorderLayout
        val header = layout.getLayoutComponent(BorderLayout.NORTH) as JLabel
        assertEquals(JBUI.scale(Density.SECTION_HEADER_H), header.preferredSize.height)
    }

    @Test
    fun `header text is uppercase caps treatment`() {
        val card = SectionCard(title = "Variant")
        val layout = card.layout as BorderLayout
        val header = layout.getLayoutComponent(BorderLayout.NORTH) as JLabel
        assertEquals("VARIANT", header.text, "header should uppercase the title")
    }

    @Test
    fun `setContent installs the provided JComponent in the CENTER slot`() {
        val card = SectionCard(title = "Accent")
        val content = JLabel("inside")
        card.setContent(content)
        val layout = card.layout as BorderLayout
        val center = layout.getLayoutComponent(BorderLayout.CENTER)
        assertEquals(content, center, "setContent must place the JComponent in BorderLayout.CENTER")
    }

    @Test
    fun `paintForTest renders without throwing`() {
        val card = SectionCard(title = "Toggles")
        card.setSize(SAMPLE_WIDTH, SAMPLE_HEIGHT)
        val img = BufferedImage(SAMPLE_WIDTH, SAMPLE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g2: Graphics2D = img.createGraphics()
        try {
            card.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        // No assertion on pixel content — the colors depend on the LAF; we only lock that
        // the paint pipeline runs end-to-end without an exception (compile-time + Pattern Q
        // discipline are the binding contracts).
    }

    @Test
    fun `setContent replaces previous content`() {
        val card = SectionCard(title = "Actions")
        val first = JLabel("first")
        val second = JLabel("second")
        card.setContent(first)
        card.setContent(second)
        val layout = card.layout as BorderLayout
        val center = layout.getLayoutComponent(BorderLayout.CENTER)
        assertEquals(second, center, "second setContent must replace the first")
    }

    private companion object {
        const val SAMPLE_WIDTH: Int = 240
        const val SAMPLE_HEIGHT: Int = 90
    }
}
