package dev.ayuislands

import com.intellij.ide.ui.LafManager
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioThemeChange
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AyuIslandsLafListenerTest {
    private val studio = mockk<StudioService>()
    private val manager = mockk<LafManager>()
    private val listener = AyuIslandsLafListener()

    @BeforeTest
    fun setUp() {
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns studio
        every { studio.nativeChanged(any()) } returns Job().apply { complete() }
        mockkObject(AyuLaf)
        every { AyuLaf.ownsThemeChange(manager) } returns false
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `manual Ayu choices retain the exact selected variant and Islands flavor in shared admission`() {
        for (name in AyuVariant.entries.flatMap { it.themeNames }) {
            every { AyuLaf.currentThemeName(manager) } returns name
            listener.lookAndFeelChanged(manager)
            verify(exactly = 1) { studio.nativeChanged(StudioNativeChange.Theme(StudioThemeChange.Explicit(name))) }
        }
    }

    @Test
    fun `external theme events refresh accepted effects without manufacturing an explicit Ayu choice`() {
        every { AyuLaf.currentThemeName(manager) } returns "User theme"
        listener.lookAndFeelChanged(manager)
        verify(exactly = 1) { studio.nativeChanged(StudioNativeChange.Appearance) }
    }

    @Test
    fun `owned theme application and rollback do not recursively submit another operation`() {
        every { AyuLaf.ownsThemeChange(manager) } returns true
        listener.lookAndFeelChanged(manager)
        verify(exactly = 0) { studio.nativeChanged(any()) }
        verify(exactly = 0) { AyuLaf.currentThemeName(manager) }
    }

    @Test
    fun `plugin registers the shared LAF producer only once`() {
        val xml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))
        assertEquals(1, Regex("class=\"dev\\.ayuislands\\.AyuIslandsLafListener\"").findAll(xml).count())
    }
}
