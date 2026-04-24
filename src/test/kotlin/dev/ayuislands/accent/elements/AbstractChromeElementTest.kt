package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.ClassFqn
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for the Phase 40.3c Refactor 1 [AbstractChromeElement] base class.
 *
 * Locks the shared recipe that every chrome element inherits: apply walks
 * [AbstractChromeElement.backgroundKeys], blends + writes to UIManager, then
 * picks a WCAG-contrast fg for [AbstractChromeElement.foregroundKeys] and pushes
 * the tinted sample to [LiveChromeRefresher] via the typed [ChromeTarget] peer
 * descriptor. Revert nulls every bg + fg key and clears the peer.
 */
class AbstractChromeElementTest {
    private val accent = Color(0xE6, 0xB4, 0x50)
    private val blended = Color(0x33, 0x44, 0x55)
    private val contrastFg = Color.WHITE
    private val stockBase = Color(0x2A, 0x2F, 0x3A)

    private val peerFqn = ClassFqn.require("test.fake.ChromePeer")

    @BeforeTest
    fun setUp() {
        mockkStatic(UIManager::class)
        every { UIManager.put(any<String>(), any()) } returns Unit

        mockkObject(ChromeBaseColors)
        every { ChromeBaseColors.get(any()) } returns stockBase

        mockkObject(ChromeTintBlender)
        every { ChromeTintBlender.blend(any(), any<Color>(), any()) } returns blended

        mockkObject(WcagForeground)
        every { WcagForeground.pickForeground(any(), any()) } returns contrastFg

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refresh(any(), any()) } returns Unit
        every { LiveChromeRefresher.clear(any()) } returns Unit

        val state = AyuIslandsState().apply { chromeTintIntensity = 30 }
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state

        val application = mockk<Application>(relaxed = true)
        every { application.getService(AyuIslandsSettings::class.java) } returns settings
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private class FakeElement(
        override val backgroundKeys: List<String>,
        override val foregroundKeys: List<String>,
        override val peerTarget: ChromeTarget?,
        override val foregroundTextTarget: WcagForeground.TextTarget = WcagForeground.TextTarget.PRIMARY_TEXT,
        private val enabled: Boolean = true,
    ) : AbstractChromeElement() {
        override val id = AccentElementId.STATUS_BAR
        override val displayName = "Fake"
        override val isEnabled: Boolean get() = enabled
    }

    @Test
    fun `apply writes tinted bg plus contrast fg and refreshes the class-name peer`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = listOf("Fake.foreground"),
                peerTarget = ChromeTarget.ByClassName(peerFqn),
            )

        element.apply(accent)

        verify(exactly = 1) { UIManager.put("Fake.background", blended) }
        verify(exactly = 1) {
            WcagForeground.pickForeground(blended, WcagForeground.TextTarget.PRIMARY_TEXT)
        }
        verify(exactly = 1) { UIManager.put("Fake.foreground", contrastFg) }
        verify(exactly = 1) { LiveChromeRefresher.refresh(ChromeTarget.ByClassName(peerFqn), blended) }
    }

    @Test
    fun `revert nulls every bg + fg key and clears the class-name peer`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = listOf("Fake.foreground"),
                peerTarget = ChromeTarget.ByClassName(peerFqn),
            )

        element.revert()

        verify(exactly = 1) { UIManager.put("Fake.background", null) }
        verify(exactly = 1) { UIManager.put("Fake.foreground", null) }
        verify(exactly = 1) { LiveChromeRefresher.clear(ChromeTarget.ByClassName(peerFqn)) }
    }

    @Test
    fun `apply short-circuits when isEnabled is false`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = listOf("Fake.foreground"),
                peerTarget = ChromeTarget.ByClassName(peerFqn),
                enabled = false,
            )

        element.apply(accent)

        verify(exactly = 0) { UIManager.put(any<String>(), any()) }
        verify(exactly = 0) { LiveChromeRefresher.refresh(any(), any()) }
    }

    @Test
    fun `revert ignores isEnabled so keys written before the gate flipped still get cleaned`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = listOf("Fake.foreground"),
                peerTarget = ChromeTarget.ByClassName(peerFqn),
                enabled = false,
            )

        element.revert()

        verify(exactly = 1) { UIManager.put("Fake.background", null) }
        verify(exactly = 1) { UIManager.put("Fake.foreground", null) }
        verify(exactly = 1) { LiveChromeRefresher.clear(ChromeTarget.ByClassName(peerFqn)) }
    }

    @Test
    fun `apply with empty foregroundKeys skips WcagForeground entirely`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = emptyList(),
                peerTarget = ChromeTarget.ByClassName(peerFqn),
            )

        element.apply(accent)

        verify(exactly = 0) { WcagForeground.pickForeground(any(), any()) }
        verify(exactly = 1) { UIManager.put("Fake.background", blended) }
        verify(exactly = 1) { LiveChromeRefresher.refresh(ChromeTarget.ByClassName(peerFqn), blended) }
    }

    @Test
    fun `apply with null peerTarget skips the live peer refresh`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = emptyList(),
                peerTarget = null,
            )

        element.apply(accent)

        verify(exactly = 0) { LiveChromeRefresher.refresh(any(), any()) }
    }

    @Test
    fun `apply routes StatusBar peer target through the refresh entry point`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = emptyList(),
                peerTarget = ChromeTarget.StatusBar,
            )

        element.apply(accent)

        verify(exactly = 1) { LiveChromeRefresher.refresh(ChromeTarget.StatusBar, blended) }
    }

    @Test
    fun `revert routes StatusBar peer target through the clear entry point`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = emptyList(),
                peerTarget = ChromeTarget.StatusBar,
            )

        element.revert()

        verify(exactly = 1) { LiveChromeRefresher.clear(ChromeTarget.StatusBar) }
    }

    @Test
    fun `apply routes ByClassNameInside ancestor target through the refresh entry point`() {
        val target = ClassFqn.require("test.fake.Target")
        val ancestor = ClassFqn.require("test.fake.Ancestor")
        val peerTarget = ChromeTarget.ByClassNameInside(target = target, ancestor = ancestor)
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = emptyList(),
                peerTarget = peerTarget,
            )

        element.apply(accent)

        verify(exactly = 1) { LiveChromeRefresher.refresh(peerTarget, blended) }
    }

    @Test
    fun `revert routes ByClassNameInside ancestor target through the clear entry point`() {
        val target = ClassFqn.require("test.fake.Target")
        val ancestor = ClassFqn.require("test.fake.Ancestor")
        val peerTarget = ChromeTarget.ByClassNameInside(target = target, ancestor = ancestor)
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.background"),
                foregroundKeys = emptyList(),
                peerTarget = peerTarget,
            )

        element.revert()

        verify(exactly = 1) { LiveChromeRefresher.clear(peerTarget) }
    }

    @Test
    fun `apply skips bg keys ChromeBaseColors cannot resolve but still writes matches`() {
        val element =
            FakeElement(
                backgroundKeys = listOf("Fake.resolvable", "Fake.missing"),
                foregroundKeys = emptyList(),
                peerTarget = null,
            )
        every { ChromeBaseColors.get("Fake.resolvable") } returns stockBase
        every { ChromeBaseColors.get("Fake.missing") } returns null

        element.apply(accent)

        verify(exactly = 1) { UIManager.put("Fake.resolvable", blended) }
        verify(exactly = 0) { UIManager.put("Fake.missing", any()) }
    }
}
