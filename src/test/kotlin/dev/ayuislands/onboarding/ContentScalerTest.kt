package dev.ayuislands.onboarding

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.Box
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentScalerTest {
    private val scaler = ContentScaler()

    // --- currentScale tracking ---

    @Test
    fun `initial currentScale is 1`() {
        assertEquals(1.0f, scaler.currentScale)
    }

    @Test
    fun `apply updates currentScale`() {
        scaler.apply(0.75f)
        assertEquals(0.75f, scaler.currentScale)
    }

    @Test
    fun `apply with different scale updates currentScale again`() {
        scaler.apply(0.5f)
        scaler.apply(1.2f)
        assertEquals(1.2f, scaler.currentScale)
    }

    // --- Card scaling ---

    @Test
    fun `card at scale 1 keeps original JBUI-scaled dimensions`() {
        val card = JPanel()
        val baseW = 200
        val baseH = 100
        scaler.registerCard(card, baseW, baseH)

        scaler.apply(1.0f)

        val expectedW = JBUI.scale(baseW)
        val expectedH = JBUI.scale(baseH)
        assertEquals(expectedW, card.preferredSize.width)
        assertEquals(expectedH, card.preferredSize.height)
        assertEquals(expectedW, card.minimumSize.width)
        assertEquals(expectedH, card.minimumSize.height)
        assertEquals(expectedW, card.maximumSize.width)
        assertEquals(expectedH, card.maximumSize.height)
    }

    @Test
    fun `card at scale 0_5 halves dimensions`() {
        val card = JPanel()
        val baseW = 200
        val baseH = 100
        scaler.registerCard(card, baseW, baseH)

        scaler.apply(0.5f)

        val expectedW = JBUI.scale((baseW * 0.5f).toInt())
        val expectedH = JBUI.scale((baseH * 0.5f).toInt())
        assertEquals(expectedW, card.preferredSize.width)
        assertEquals(expectedH, card.preferredSize.height)
    }

    @Test
    fun `card at scale 2 doubles dimensions`() {
        val card = JPanel()
        val baseW = 100
        val baseH = 60
        scaler.registerCard(card, baseW, baseH)

        scaler.apply(2.0f)

        val expectedW = JBUI.scale((baseW * 2.0f).toInt())
        val expectedH = JBUI.scale((baseH * 2.0f).toInt())
        assertEquals(expectedW, card.preferredSize.width)
        assertEquals(expectedH, card.preferredSize.height)
    }

    @Test
    fun `multiple cards all get scaled`() {
        val card1 = JPanel()
        val card2 = JPanel()
        scaler.registerCard(card1, 200, 100)
        scaler.registerCard(card2, 300, 150)

        scaler.apply(0.5f)

        assertEquals(JBUI.scale(100), card1.preferredSize.width)
        assertEquals(JBUI.scale(50), card1.preferredSize.height)
        assertEquals(JBUI.scale(150), card2.preferredSize.width)
        assertEquals(JBUI.scale(75), card2.preferredSize.height)
    }

    // --- Label font scaling ---

    @Test
    fun `label font scales with factor`() {
        val label = JBLabel("Test")
        val baseFontPx = 14
        scaler.registerLabel(label, baseFontPx, Font.BOLD)

        scaler.apply(1.0f)

        val expectedSize = JBUI.scale(baseFontPx).toFloat()
        assertEquals(expectedSize, label.font.size2D)
        assertEquals(Font.BOLD, label.font.style)
    }

    @Test
    fun `label font at half scale`() {
        val label = JBLabel("Test")
        val baseFontPx = 20
        scaler.registerLabel(label, baseFontPx, Font.PLAIN)

        scaler.apply(0.5f)

        val expectedSize = JBUI.scale((baseFontPx * 0.5f).toInt()).toFloat()
        assertEquals(expectedSize, label.font.size2D)
    }

    @Test
    fun `label font never goes below MIN_FONT_PX of 6`() {
        val label = JBLabel("Tiny")
        val baseFontPx = 8
        scaler.registerLabel(label, baseFontPx, Font.PLAIN)

        // 8 * 0.1 = 0.8 -> toInt() = 0, coerceAtLeast(6) = 6
        scaler.apply(0.1f)

        val expectedSize = JBUI.scale(6).toFloat()
        assertEquals(expectedSize, label.font.size2D)
    }

    @Test
    fun `label font at scale where raw value equals MIN_FONT_PX`() {
        val label = JBLabel("Edge")
        val baseFontPx = 12
        scaler.registerLabel(label, baseFontPx, Font.ITALIC)

        // 12 * 0.5 = 6 -> exactly MIN_FONT_PX
        scaler.apply(0.5f)

        val expectedSize = JBUI.scale(6).toFloat()
        assertEquals(expectedSize, label.font.size2D)
        assertEquals(Font.ITALIC, label.font.style)
    }

    @Test
    fun `label font preserves style across rescales`() {
        val label = JBLabel("Styled")
        scaler.registerLabel(label, 16, Font.BOLD)

        scaler.apply(1.0f)
        assertEquals(Font.BOLD, label.font.style)

        scaler.apply(0.5f)
        assertEquals(Font.BOLD, label.font.style)
    }

    // --- Gap strut scaling ---

    @Test
    fun `vertical gap strut scales height`() {
        val strut = Box.createVerticalStrut(20)
        scaler.registerGap(strut, 20, horizontal = false)

        scaler.apply(0.5f)

        val expectedPx = JBUI.scale(10)
        assertEquals(0, strut.preferredSize.width)
        assertEquals(expectedPx, strut.preferredSize.height)
        assertEquals(0, strut.minimumSize.width)
        assertEquals(expectedPx, strut.minimumSize.height)
        assertEquals(Short.MAX_VALUE.toInt(), strut.maximumSize.width)
        assertEquals(expectedPx, strut.maximumSize.height)
    }

    @Test
    fun `horizontal gap strut scales width`() {
        val strut = Box.createHorizontalStrut(30)
        scaler.registerGap(strut, 30, horizontal = true)

        scaler.apply(0.5f)

        val expectedPx = JBUI.scale(15)
        assertEquals(expectedPx, strut.preferredSize.width)
        assertEquals(0, strut.preferredSize.height)
        assertEquals(expectedPx, strut.minimumSize.width)
        assertEquals(0, strut.minimumSize.height)
        assertEquals(expectedPx, strut.maximumSize.width)
        assertEquals(Short.MAX_VALUE.toInt(), strut.maximumSize.height)
    }

    @Test
    fun `vertical gap at full scale keeps base size`() {
        val strut = Box.createVerticalStrut(16)
        scaler.registerGap(strut, 16, horizontal = false)

        scaler.apply(1.0f)

        assertEquals(JBUI.scale(16), strut.preferredSize.height)
    }

    // --- Hideable visibility ---

    @Test
    fun `hideable visible when scale above threshold`() {
        val component = JPanel()
        scaler.registerHideable(component, hideBelow = 0.7f)

        scaler.apply(0.8f)

        assertTrue(component.isVisible)
    }

    @Test
    fun `hideable hidden when scale below threshold`() {
        val component = JPanel()
        scaler.registerHideable(component, hideBelow = 0.7f)

        scaler.apply(0.5f)

        assertFalse(component.isVisible)
    }

    @Test
    fun `hideable visible when scale equals threshold`() {
        val component = JPanel()
        scaler.registerHideable(component, hideBelow = 0.7f)

        scaler.apply(0.7f)

        assertTrue(component.isVisible)
    }

    @Test
    fun `forceHideCompact hides all hideables regardless of scale`() {
        val component1 = JPanel()
        val component2 = JPanel()
        scaler.registerHideable(component1, hideBelow = 0.5f)
        scaler.registerHideable(component2, hideBelow = 0.3f)

        scaler.apply(1.0f, forceHideCompact = true)

        assertFalse(component1.isVisible)
        assertFalse(component2.isVisible)
    }

    @Test
    fun `forceHideCompact false respects per-component thresholds`() {
        val highThreshold = JPanel()
        val lowThreshold = JPanel()
        scaler.registerHideable(highThreshold, hideBelow = 0.8f)
        scaler.registerHideable(lowThreshold, hideBelow = 0.3f)

        scaler.apply(0.5f, forceHideCompact = false)

        assertFalse(highThreshold.isVisible)
        assertTrue(lowThreshold.isVisible)
    }

    @Test
    fun `hideable toggles visibility across multiple apply calls`() {
        val component = JPanel()
        scaler.registerHideable(component, hideBelow = 0.6f)

        scaler.apply(0.8f)
        assertTrue(component.isVisible)

        scaler.apply(0.4f)
        assertFalse(component.isVisible)

        scaler.apply(0.6f)
        assertTrue(component.isVisible)
    }

    // --- clear() ---

    @Test
    fun `clear empties all registrations`() {
        val card = JPanel()
        val label = JBLabel("L")
        val strut = Box.createVerticalStrut(10)
        val hideable = JPanel()

        scaler.registerCard(card, 100, 50)
        scaler.registerLabel(label, 14, Font.PLAIN)
        scaler.registerGap(strut, 10)
        scaler.registerHideable(hideable, 0.5f)

        scaler.clear()

        // Capture current state before apply
        val cardWidthBefore = card.preferredSize.width
        val labelSizeBefore = label.font.size2D
        val strutHeightBefore = strut.preferredSize.height
        hideable.isVisible = true

        // Apply should not affect cleared registrations
        scaler.apply(0.1f)

        assertEquals(cardWidthBefore, card.preferredSize.width)
        assertEquals(labelSizeBefore, label.font.size2D)
        assertEquals(strutHeightBefore, strut.preferredSize.height)
        assertTrue(hideable.isVisible)
    }

    @Test
    fun `clear allows re-registration`() {
        val card = JPanel()
        scaler.registerCard(card, 200, 100)
        scaler.clear()

        val newCard = JPanel()
        scaler.registerCard(newCard, 300, 150)
        scaler.apply(0.5f)

        // newCard should be scaled, original card untouched
        assertEquals(JBUI.scale(150), newCard.preferredSize.width)
    }
}
