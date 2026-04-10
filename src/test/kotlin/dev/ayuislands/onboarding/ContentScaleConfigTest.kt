package dev.ayuislands.onboarding

import com.intellij.util.ui.JBUI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentScaleConfigTest {
    private val defaultConfig =
        ContentScaleConfig(
            bottomMarginPx = 20,
            designWidth = 800,
            designContentHeight = 500,
            designContentHeightCompact = 300,
            compactThreshold = 0.7f,
            minScale = 0.4f,
            maxScale = 1.5f,
        )

    @Test
    fun `full scale when panel is large enough`() {
        val scaler = ContentScaler()
        // Large panel: plenty of room for design content at scale 1.0+
        val panelWidth = JBUI.scale(defaultConfig.designWidth) * 2
        val panelHeight = JBUI.scale(defaultConfig.designContentHeight) * 2 + JBUI.scale(defaultConfig.bottomMarginPx)

        updateContentScale(panelWidth, panelHeight, topStrutHeight = 0, defaultConfig, scaler)

        // Scale should be capped at maxScale
        assertEquals(defaultConfig.maxScale, scaler.currentScale)
    }

    @Test
    fun `scale is capped at maxScale`() {
        val config = defaultConfig.copy(maxScale = 1.2f)
        val scaler = ContentScaler()
        // Very large panel that would produce scale > maxScale
        val panelWidth = JBUI.scale(config.designWidth) * 10
        val panelHeight = JBUI.scale(config.designContentHeight) * 10

        updateContentScale(panelWidth, panelHeight, topStrutHeight = 0, config, scaler)

        assertEquals(config.maxScale, scaler.currentScale)
    }

    @Test
    fun `scale is floored at minScale`() {
        val config = defaultConfig.copy(minScale = 0.4f)
        val scaler = ContentScaler()
        // Tiny panel that would produce scale < minScale
        val panelWidth = JBUI.scale(config.designWidth) / 10
        val panelHeight = JBUI.scale(config.designContentHeight) / 10

        updateContentScale(panelWidth, panelHeight, topStrutHeight = 0, config, scaler)

        assertEquals(config.minScale, scaler.currentScale)
    }

    @Test
    fun `compact mode triggers when fullScale below compactThreshold`() {
        val scaler = ContentScaler()
        val hideable = javax.swing.JPanel()
        scaler.registerHideable(hideable, hideBelow = 0.0f)

        // Construct panel size so fullScale < 0.7
        // fullScale = min(available / designH, panelW / designW)
        // available = panelH - topStrut - bottomMargin
        // We want both ratios < 0.7
        val panelWidth = (JBUI.scale(defaultConfig.designWidth) * 0.5f).toInt()
        val panelHeight =
            (JBUI.scale(defaultConfig.designContentHeight) * 0.5f).toInt() + JBUI.scale(defaultConfig.bottomMarginPx)

        updateContentScale(panelWidth, panelHeight, topStrutHeight = 0, defaultConfig, scaler)

        // In compact mode, forceHideCompact=true hides all hideables
        assertFalse(hideable.isVisible, "forceHideCompact should hide hideables in compact mode")
    }

    @Test
    fun `non-compact mode keeps hideables visible`() {
        val scaler = ContentScaler()
        val hideable = javax.swing.JPanel()
        scaler.registerHideable(hideable, hideBelow = 0.0f)

        // Large panel: fullScale well above compactThreshold
        val panelWidth = JBUI.scale(defaultConfig.designWidth)
        val panelHeight = JBUI.scale(defaultConfig.designContentHeight) + JBUI.scale(defaultConfig.bottomMarginPx)

        updateContentScale(panelWidth, panelHeight, topStrutHeight = 0, defaultConfig, scaler)

        assertTrue(hideable.isVisible, "hideables should remain visible above compact threshold")
    }

    @Test
    fun `topStrutHeight reduces available space`() {
        val config = defaultConfig.copy(maxScale = 2.0f, compactThreshold = 0.0f)
        val scaler = ContentScaler()
        val panelWidth = JBUI.scale(config.designWidth) * 3
        val panelHeight = JBUI.scale(config.designContentHeight) + JBUI.scale(config.bottomMarginPx)

        // Without top strut: available = designH, heightScale = 1.0
        updateContentScale(panelWidth, panelHeight, topStrutHeight = 0, config, scaler)
        val scaleNoStrut = scaler.currentScale

        // With top strut: available = designH - 200, heightScale < 1.0
        updateContentScale(panelWidth, panelHeight, topStrutHeight = 200, config, scaler)
        val scaleWithStrut = scaler.currentScale

        assertTrue(
            scaleWithStrut < scaleNoStrut,
            "topStrutHeight should reduce scale: $scaleWithStrut >= $scaleNoStrut",
        )
    }

    @Test
    fun `zero or negative panel dimensions are no-op`() {
        val scaler = ContentScaler()
        val initialScale = scaler.currentScale

        updateContentScale(0, 600, topStrutHeight = 0, defaultConfig, scaler)
        assertEquals(initialScale, scaler.currentScale, "zero width should be no-op")

        updateContentScale(800, 0, topStrutHeight = 0, defaultConfig, scaler)
        assertEquals(initialScale, scaler.currentScale, "zero height should be no-op")

        updateContentScale(-100, 600, topStrutHeight = 0, defaultConfig, scaler)
        assertEquals(initialScale, scaler.currentScale, "negative width should be no-op")

        updateContentScale(800, -100, topStrutHeight = 0, defaultConfig, scaler)
        assertEquals(initialScale, scaler.currentScale, "negative height should be no-op")
    }

    @Test
    fun `width-constrained layout uses width scale`() {
        val config = defaultConfig.copy(maxScale = 5.0f, minScale = 0.1f, compactThreshold = 0.0f)
        val scaler = ContentScaler()

        // Very tall but narrow panel — width should be the constraining axis
        val panelWidth = JBUI.scale(config.designWidth) / 2
        val panelHeight = JBUI.scale(config.designContentHeight) * 5 + JBUI.scale(config.bottomMarginPx)

        updateContentScale(panelWidth, panelHeight, topStrutHeight = 0, config, scaler)

        // widthScale = 0.5, heightScale would be much larger
        // contentScale = min(heightScale, widthScale) = 0.5
        val expectedWidthScale = panelWidth.toFloat() / JBUI.scale(config.designWidth).toFloat()
        assertEquals(expectedWidthScale, scaler.currentScale)
    }

    private fun assertEquals(
        expected: Float,
        actual: Float,
    ) {
        val tolerance = 0.01f
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "Expected $expected +/- $tolerance but got $actual",
        )
    }
}
