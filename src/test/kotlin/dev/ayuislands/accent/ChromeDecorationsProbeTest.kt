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
import kotlin.test.assertEquals
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

    // --- computeOs branch coverage ---
    //
    // The defaultOsSupplier lambda reads SystemInfo.isMac/isWindows/isLinux,
    // which are public static final booleans that mockk cannot touch. To
    // exercise every OS branch anyway, `computeOs` accepts explicit booleans
    // with SystemInfo-backed defaults; these tests pass the booleans directly.

    @Test
    fun `computeOs returns MAC when isMac is true`() {
        assertEquals(
            ChromeDecorationsProbe.Os.MAC,
            ChromeDecorationsProbe.computeOs(isMac = true, isWindows = false, isLinux = false),
        )
    }

    @Test
    fun `computeOs returns WINDOWS when only isWindows is true`() {
        assertEquals(
            ChromeDecorationsProbe.Os.WINDOWS,
            ChromeDecorationsProbe.computeOs(isMac = false, isWindows = true, isLinux = false),
        )
    }

    @Test
    fun `computeOs returns LINUX when only isLinux is true`() {
        assertEquals(
            ChromeDecorationsProbe.Os.LINUX,
            ChromeDecorationsProbe.computeOs(isMac = false, isWindows = false, isLinux = true),
        )
    }

    @Test
    fun `computeOs falls through to UNKNOWN when all flags are false`() {
        assertEquals(
            ChromeDecorationsProbe.Os.UNKNOWN,
            ChromeDecorationsProbe.computeOs(isMac = false, isWindows = false, isLinux = false),
        )
    }

    @Test
    fun `computeOs prefers MAC when multiple flags are true (dispatch order lock)`() {
        // Defensive branch-order lock: SystemInfo flags should never be
        // mutually true in practice, but a refactor that reorders the `when`
        // would shift dispatch priority. This pins MAC-first semantics.
        assertEquals(
            ChromeDecorationsProbe.Os.MAC,
            ChromeDecorationsProbe.computeOs(isMac = true, isWindows = true, isLinux = true),
        )
    }

    // --- probe() typed result (Phase 40.3c Refactor 3) ---
    //
    // Every OS branch maps to a distinct ChromeSupport variant so the Settings UI
    // can render a tailored "disabled" tooltip without re-sampling SystemInfo on
    // its own. Tests lock one case per variant.

    @Test
    fun `probe returns Supported on macOS when unified-background is true`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.MAC }
        every { UIManager.getBoolean("TitlePane.unifiedBackground") } returns true

        assertEquals(ChromeSupport.Supported, ChromeDecorationsProbe.probe())
    }

    @Test
    fun `probe returns NativeMacTitleBar when macOS custom-header signals are both false`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.MAC }
        every { UIManager.getBoolean("TitlePane.unifiedBackground") } returns false
        every { Registry.`is`("ide.mac.transparentTitleBarAppearance", false) } returns false

        assertEquals(ChromeSupport.Unsupported.NativeMacTitleBar, ChromeDecorationsProbe.probe())
    }

    @Test
    fun `probe returns Supported on Windows when CustomWindowHeader is true`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.WINDOWS }
        every { UIManager.getBoolean("CustomWindowHeader") } returns true

        assertEquals(ChromeSupport.Supported, ChromeDecorationsProbe.probe())
    }

    @Test
    fun `probe returns WindowsNoCustomHeader when CustomWindowHeader is false`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.WINDOWS }
        every { UIManager.getBoolean("CustomWindowHeader") } returns false

        assertEquals(ChromeSupport.Unsupported.WindowsNoCustomHeader, ChromeDecorationsProbe.probe())
    }

    @Test
    fun `probe returns Supported on Linux when the custom-title registry flag is true`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.LINUX }
        every { Registry.`is`("ide.linux.custom.title.bar", false) } returns true

        assertEquals(ChromeSupport.Supported, ChromeDecorationsProbe.probe())
    }

    @Test
    fun `probe returns GnomeSsd when the Linux custom-title registry flag is false`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.LINUX }
        every { Registry.`is`("ide.linux.custom.title.bar", false) } returns false

        assertEquals(ChromeSupport.Unsupported.GnomeSsd, ChromeDecorationsProbe.probe())
    }

    @Test
    fun `probe returns UnknownOs when osSupplier resolves to UNKNOWN`() {
        ChromeDecorationsProbe.osSupplier = { ChromeDecorationsProbe.Os.UNKNOWN }

        assertEquals(ChromeSupport.Unsupported.UnknownOs, ChromeDecorationsProbe.probe())
    }

    @Test
    fun `ChromeSupport Unsupported variants expose distinct reason strings`() {
        // Regression guard: UI code maps reason strings into user-visible tooltips,
        // so collisions would make the tooltip lie about which OS branch fired.
        val reasons =
            setOf(
                ChromeSupport.Unsupported.NativeMacTitleBar.reason,
                ChromeSupport.Unsupported.GnomeSsd.reason,
                ChromeSupport.Unsupported.WindowsNoCustomHeader.reason,
                ChromeSupport.Unsupported.UnknownOs.reason,
            )
        assertEquals(4, reasons.size, "every Unsupported variant must have a unique reason string")
    }
}
