package dev.ayuislands.accent

import com.intellij.openapi.util.registry.Registry
import io.mockk.every
import io.mockk.mockkObject
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
        mockkObject(Registry.Companion)
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

    // ── Plan 40-11 Gap 3: canEnableCustomHeaderOnMac helper ───────────────────

    @Test
    fun `canEnableCustomHeaderOnMac returns true on macOS when custom header is NOT active`() {
        // macOS + both probe keys false ⇒ native title bar ⇒ the Settings panel should
        // offer the merged-menu link so the user has a path forward (VERIFICATION Gap 3).
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.MAC }
        every { UIManager.getBoolean("TitlePane.unifiedBackground") } returns false
        every { Registry.`is`("ide.mac.transparentTitleBarAppearance", false) } returns false

        assertTrue(ChromeDecorationsProbe.canEnableCustomHeaderOnMac())
    }

    @Test
    fun `canEnableCustomHeaderOnMac returns false on macOS when custom header IS active`() {
        // macOS + merged menu already ON ⇒ custom header active ⇒ offering to enable it
        // again is nonsense. The helper must return false so the link stays hidden.
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.MAC }
        every { UIManager.getBoolean("TitlePane.unifiedBackground") } returns true

        assertFalse(ChromeDecorationsProbe.canEnableCustomHeaderOnMac())
    }

    @Test
    fun `canEnableCustomHeaderOnMac returns false on non-macOS platforms across all three branches`() {
        // Windows, Linux, and UNKNOWN all short-circuit to false — the merged-menu offer
        // is macOS-specific (Windows/Linux have their own custom-header paths and showing
        // a macOS hint on them would be misleading).
        fun runScenario(os: ChromeDecorationsProbe.Os) {
            ChromeDecorationsProbe.osSupplier = { os }
            assertFalse(
                ChromeDecorationsProbe.canEnableCustomHeaderOnMac(),
                "canEnableCustomHeaderOnMac must be false on $os",
            )
        }

        runScenario(ChromeDecorationsProbe.Os.WINDOWS)
        runScenario(ChromeDecorationsProbe.Os.LINUX)
        runScenario(ChromeDecorationsProbe.Os.UNKNOWN)
    }
}
