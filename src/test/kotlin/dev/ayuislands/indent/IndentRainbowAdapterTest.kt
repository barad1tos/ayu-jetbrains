package dev.ayuislands.indent

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.integration.indent.IndentRainbowAdapter
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.EditorSchemeChange
import indent.rainbow.settings.IrCachedData
import indent.rainbow.settings.IrColorsPaletteType
import indent.rainbow.settings.IrConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class IndentRainbowAdapterTest {
    private val application = mockk<Application>()
    private val config =
        IrConfig().apply {
            paletteType = IrColorsPaletteType.PASTEL
            customPalette = "FF123456, 80654321"
            customPaletteNumberColors = 4
            enabled = false
            highlightEmptyLines = true
            opacityMultiplier = 0.37f
        }
    private val original = config.copy()

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.getService(IrConfig::class.java) } returns config
        mockkObject(EditorSchemeChange)
        every { EditorSchemeChange.publish() } returns Unit
    }

    @AfterTest
    fun tearDown() {
        IrCachedData.update(original)
        unmockkAll()
    }

    @Test
    fun `typed pinned config restores owned parts and preserves the foreign palette and dormant choices`() {
        val access = assertNotNull(IndentRainbowAdapter().resolve())
        val state = AyuIslandsState().apply { indentCustomAlpha = 19 }
        val checkpoint = IrCheckpoint(state, { true }, { it.isCurrent() }, { error("Unexpected retry") })
        access.checkpoint = checkpoint

        assertEquals(original, config, "Capture must not mutate any plugin field")
        assertEquals(IrPalette("PASTEL", original.customPalette, 4), access.readPalette().palette)
        assertSame(IrColorsPaletteType.PASTEL, access.readPalette().typeValue)
        val target = IrPalette("CUSTOM", "FF112233, 80445566", 2)
        access.writePalette(target, assertNotNull(access.paletteTypes["CUSTOM"]))
        assertEquals(target, access.readPalette().palette)
        state.irOwnership = "OWNED"
        checkpoint.captureMetadata()
        config.customPalette = "FFABCDEF, 80887766"

        checkpoint.restore()

        assertEquals(original.copy(customPalette = "FFABCDEF, 80887766"), config)
        assertEquals("SUSPENDED", state.irOwnership)
        assertEquals(19, state.indentCustomAlpha)
        assertFalse(checkpoint.hasPending)
        verify(exactly = 2) { EditorSchemeChange.publish() }
    }

    @Test
    fun `replacement config service fences the captured checkpoint without changing either config`() {
        val access = assertNotNull(IndentRainbowAdapter().resolve())
        val state = AyuIslandsState()
        val checkpoint = IrCheckpoint(state, { true }, { it.isCurrent() }, { error("Unexpected retry") })
        access.checkpoint = checkpoint
        access.writePalette(IrPalette("CUSTOM", "FF112233, 80445566", 2), assertNotNull(access.paletteTypes["CUSTOM"]))
        state.irOwnership = "OWNED"
        checkpoint.captureMetadata()
        val applied = config.copy()
        val replacement = original.copy(customPaletteNumberColors = 6)
        val replacementBefore = replacement.copy()
        every { application.getService(IrConfig::class.java) } returns replacement

        assertFalse(access.isCurrent())
        checkpoint.restore()

        assertEquals(applied, config)
        assertEquals(replacementBefore, replacement)
        assertEquals("SUSPENDED", state.irOwnership)
        verify(exactly = 1) { EditorSchemeChange.publish() }
    }
}
