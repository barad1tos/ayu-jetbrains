package dev.ayuislands.accent

import com.intellij.openapi.util.registry.Registry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ChromeDecorationsProbe].
 *
 * OS dispatch goes through the package-private [ChromeDecorationsProbe.osSupplier] seam
 * (the [ChromeDecorationsProbe.Os] enum). `SystemInfo.isMac/isWindows/isLinux` are
 * `public static final boolean` JVM fields — they cannot be mocked via mockk (see
 * `SystemAccentProviderTest` docstring for precedent). The seam follows the same shape
 * used by [dev.ayuislands.licensing.LicenseChecker.nowMsSupplier].
 */
class ChromeDecorationsProbeTest {
    @BeforeTest
    fun setUp() {
        mockkStatic(UIManager::class)
        mockkStatic(Registry::class)
        every { UIManager.getBoolean(any<String>()) } returns false
        every { Registry.`is`(any<String>(), any<Boolean>()) } returns false
    }

    @AfterTest
    fun tearDown() {
        ChromeDecorationsProbe.resetOsSupplierForTests()
        unmockkAll()
    }

    @Test
    fun `macOS with unified-background key true returns true`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.MAC }
        every { UIManager.getBoolean("TitlePane.unifiedBackground") } returns true

        assertTrue(ChromeDecorationsProbe.isCustomHeaderActive())
    }

    @Test
    fun `macOS falls through to transparent-title registry when unified-background is false`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.MAC }
        every { UIManager.getBoolean("TitlePane.unifiedBackground") } returns false
        every { Registry.`is`("ide.mac.transparentTitleBarAppearance", false) } returns true

        assertTrue(ChromeDecorationsProbe.isCustomHeaderActive())
    }

    @Test
    fun `macOS with both keys false returns false`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.MAC }
        every { UIManager.getBoolean("TitlePane.unifiedBackground") } returns false
        every { Registry.`is`("ide.mac.transparentTitleBarAppearance", false) } returns false

        assertFalse(ChromeDecorationsProbe.isCustomHeaderActive())
    }

    @Test
    fun `Windows with custom-header key true returns true`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.WINDOWS }
        every { UIManager.getBoolean("CustomWindowHeader") } returns true

        assertTrue(ChromeDecorationsProbe.isCustomHeaderActive())
    }

    @Test
    fun `Windows with custom-header key false returns false`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.WINDOWS }
        every { UIManager.getBoolean("CustomWindowHeader") } returns false

        assertFalse(ChromeDecorationsProbe.isCustomHeaderActive())
    }

    @Test
    fun `Linux with custom-title-bar registry true returns true`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.LINUX }
        every { Registry.`is`("ide.linux.custom.title.bar", false) } returns true

        assertTrue(ChromeDecorationsProbe.isCustomHeaderActive())
    }

    @Test
    fun `unknown OS returns false`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.UNKNOWN }

        assertFalse(ChromeDecorationsProbe.isCustomHeaderActive())
    }
}
