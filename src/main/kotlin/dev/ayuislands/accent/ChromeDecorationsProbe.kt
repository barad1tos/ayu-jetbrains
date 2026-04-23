package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import org.jetbrains.annotations.VisibleForTesting
import javax.swing.UIManager

/**
 * Runtime probe for JBR custom window decorations.
 *
 * When [isCustomHeaderActive] returns `false`, the IDE paints a native OS title bar
 * (native macOS title, GNOME SSD, Windows without custom-header), and chrome tinting
 * for the main toolbar / title bar cannot reach that surface from plugin code — the
 * same limitation VSCode Peacock hits on macOS.
 *
 * Called from `MainToolbarElement.apply` (short-circuits) and from `AyuIslandsChromePanel`'s
 * Main Toolbar row `.enabledIf` predicate so users see a disabled toggle with a comment
 * instead of a silent no-op (CHROME-02).
 *
 * ### Key verification
 *
 * Per D-11/D-12 and the VERIFY-BEFORE-ASSUMING rule, the probe only relies on platform
 * primitives whose presence was javap-verified against platformVersion 2025.1:
 * `SystemInfo.isMac/isWindows/isLinux` (util-8.jar) and `Registry.is(String, Boolean)`
 * (util-8.jar). The per-OS probe keys below are read via `UIManager.getBoolean(key)` and
 * `Registry.is(key, false)`; both APIs return `false` safely when the key is absent,
 * which is the production fallthrough when a user switches off custom decorations.
 *
 * ### OS dispatch seam
 *
 * `SystemInfo.isMac/isWindows/isLinux` are `public static final boolean` JVM fields and
 * cannot be mocked with mockk (see `SystemAccentProviderTest` for the precedent). The
 * probe therefore funnels OS detection through [osSupplier], an overridable seam in the
 * same shape as `LicenseChecker.nowMsSupplier`. Tests pin [osSupplier] to the desired
 * branch and must call [resetOsSupplierForTests] in `@AfterTest` to restore production
 * behavior.
 */
object ChromeDecorationsProbe {
    private const val MAC_UNIFIED_KEY = "TitlePane.unifiedBackground"
    private const val MAC_REGISTRY_KEY = "ide.mac.transparentTitleBarAppearance"
    private const val WINDOWS_HEADER_KEY = "CustomWindowHeader"
    private const val LINUX_REGISTRY_KEY = "ide.linux.custom.title.bar"

    /** OS branches the probe dispatches over; `UNKNOWN` is the safe-default else branch. */
    enum class Os { MAC, WINDOWS, LINUX, UNKNOWN }

    private val defaultOsSupplier: () -> Os = {
        when {
            SystemInfo.isMac -> Os.MAC
            SystemInfo.isWindows -> Os.WINDOWS
            SystemInfo.isLinux -> Os.LINUX
            else -> Os.UNKNOWN
        }
    }

    /**
     * Test seam for OS detection. Production code reads [SystemInfo]; tests override this
     * supplier to exercise each branch. Always restore via [resetOsSupplierForTests] in
     * teardown to prevent leaking state across tests.
     */
    @VisibleForTesting
    @Volatile
    internal var osSupplier: () -> Os = defaultOsSupplier

    /** Restore the production [osSupplier]. Intended for test teardown. */
    @VisibleForTesting
    internal fun resetOsSupplierForTests() {
        osSupplier = defaultOsSupplier
    }

    /**
     * Returns `true` when the IDE is currently painting a JBR custom window header for
     * the current OS. Never throws: a missing UIManager key resolves to `false`, and a
     * missing Registry key returns the supplied default (`false`).
     */
    fun isCustomHeaderActive(): Boolean =
        when (osSupplier()) {
            Os.MAC -> macCustomHeader()
            Os.WINDOWS -> UIManager.getBoolean(WINDOWS_HEADER_KEY)
            Os.LINUX -> Registry.`is`(LINUX_REGISTRY_KEY, false)
            Os.UNKNOWN -> false
        }

    private fun macCustomHeader(): Boolean =
        UIManager.getBoolean(MAC_UNIFIED_KEY) ||
            Registry.`is`(MAC_REGISTRY_KEY, false)
}
