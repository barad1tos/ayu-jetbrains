package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@Suppress("UnstableApiUsage")
class AyuIslandsAppListenerTest {
    private lateinit var state: AyuIslandsState
    private val listener = AyuIslandsAppListener()

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        state.mirageAccent = "#FF0000"
        every {
            settings.getAccentForVariant(AyuVariant.MIRAGE)
        } returns "#FF0000"
        every {
            settings.getAccentForVariant(AyuVariant.DARK)
        } returns "#00FF00"
        every {
            settings.getAccentForVariant(AyuVariant.LIGHT)
        } returns "#0000FF"

        mockkObject(AyuIslandsSettings.Companion)
        every {
            AyuIslandsSettings.getInstance()
        } returns settings

        // AccentResolver consults AccentMappingsSettings before falling through to global;
        // an empty state preserves pre-override behavior (resolver returns the global accent).
        val mappingsSettings = mockk<AccentMappingsSettings>()
        every { mappingsSettings.state } returns AccentMappingsState()
        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns mappingsSettings

        mockkStatic(LafManager::class)
        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } just runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `appFrameCreated applies accent for Ayu theme`() {
        val laf = mockk<UIThemeLookAndFeelInfo>()
        every { laf.name } returns "Ayu Mirage (Islands UI)"
        val lafManager = mockk<LafManager>()
        every {
            lafManager.currentUIThemeLookAndFeel
        } returns laf
        every { LafManager.getInstance() } returns lafManager

        listener.appFrameCreated(mutableListOf())

        verify(exactly = 1) {
            AccentApplicator.apply("#FF0000")
        }
    }

    @Test
    fun `appFrameCreated skips non-Ayu theme`() {
        val laf = mockk<UIThemeLookAndFeelInfo>()
        every { laf.name } returns "Darcula"
        val lafManager = mockk<LafManager>()
        every {
            lafManager.currentUIThemeLookAndFeel
        } returns laf
        every { LafManager.getInstance() } returns lafManager

        listener.appFrameCreated(mutableListOf())

        verify(exactly = 0) {
            AccentApplicator.apply(any())
        }
    }

    @Test
    fun `appFrameCreated applies accent for each variant theme name`() {
        // Regression guard: every registered theme name must map to its variant's stored accent.
        // The AyuIslandsAppListenerTest setUp stubs per-variant accents ("#FF0000" mirage,
        // "#00FF00" dark, "#0000FF" light) — walk all six theme names and verify the expected
        // accent is applied. A bug that collapses variant detection would show up as the wrong
        // accent, not no call at all.
        val cases =
            listOf(
                "Ayu Mirage" to "#FF0000",
                "Ayu Mirage (Islands UI)" to "#FF0000",
                "Ayu Dark" to "#00FF00",
                "Ayu Dark (Islands UI)" to "#00FF00",
                "Ayu Light" to "#0000FF",
                "Ayu Light (Islands UI)" to "#0000FF",
            )

        for ((themeName, expectedAccent) in cases) {
            val laf = mockk<UIThemeLookAndFeelInfo>()
            every { laf.name } returns themeName
            val lafManager = mockk<LafManager>()
            every { lafManager.currentUIThemeLookAndFeel } returns laf
            every { LafManager.getInstance() } returns lafManager

            listener.appFrameCreated(mutableListOf())

            verify(atLeast = 1) {
                AccentApplicator.apply(expectedAccent)
            }
        }
    }

    @Test
    fun `appFrameCreated handles both Islands UI and non-Islands UI theme names`() {
        // Direct comparison: "Ayu Mirage" and "Ayu Mirage (Islands UI)" are two packaged
        // theme files but share the same variant — they must resolve to the same accent.
        val lafManager = mockk<LafManager>()
        every { LafManager.getInstance() } returns lafManager

        val nonIslandsLaf = mockk<UIThemeLookAndFeelInfo>()
        every { nonIslandsLaf.name } returns "Ayu Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns nonIslandsLaf
        listener.appFrameCreated(mutableListOf())

        val islandsLaf = mockk<UIThemeLookAndFeelInfo>()
        every { islandsLaf.name } returns "Ayu Mirage (Islands UI)"
        every { lafManager.currentUIThemeLookAndFeel } returns islandsLaf
        listener.appFrameCreated(mutableListOf())

        // Both calls must land on the Mirage accent — never the Dark or Light one.
        verify(exactly = 2) { AccentApplicator.apply("#FF0000") }
        verify(exactly = 0) { AccentApplicator.apply("#00FF00") }
        verify(exactly = 0) { AccentApplicator.apply("#0000FF") }
    }
}
