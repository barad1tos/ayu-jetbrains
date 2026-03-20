package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
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

class AyuIslandsAppListenerTest {
    private lateinit var state: AyuIslandsState
    private val listener = AyuIslandsAppListener()

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        every {
            settings.getAccentForVariant(any())
        } answers {
            val variant = firstArg<AyuVariant>()
            variant.defaultAccent
        }

        mockkObject(AyuIslandsSettings.Companion)
        every {
            AyuIslandsSettings.getInstance()
        } returns settings

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
        @Suppress("UnstableApiUsage")
        every {
            lafManager.currentUIThemeLookAndFeel
        } returns laf
        every { LafManager.getInstance() } returns lafManager

        listener.appFrameCreated(mutableListOf())

        verify(exactly = 1) {
            AccentApplicator.apply(AyuVariant.MIRAGE.defaultAccent)
        }
    }

    @Test
    fun `appFrameCreated skips non-Ayu theme`() {
        val laf = mockk<UIThemeLookAndFeelInfo>()
        every { laf.name } returns "Darcula"
        val lafManager = mockk<LafManager>()
        @Suppress("UnstableApiUsage")
        every {
            lafManager.currentUIThemeLookAndFeel
        } returns laf
        every { LafManager.getInstance() } returns lafManager

        listener.appFrameCreated(mutableListOf())

        verify(exactly = 0) {
            AccentApplicator.apply(any())
        }
    }
}
