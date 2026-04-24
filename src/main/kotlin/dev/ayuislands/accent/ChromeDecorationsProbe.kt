package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean
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
 * behavior. The override is held in a [ThreadLocal] so parallel JUnit workers cannot
 * leak a pinned OS from one test into a concurrent sibling.
 */
object ChromeDecorationsProbe {
    private const val MAC_UNIFIED_KEY = "TitlePane.unifiedBackground"
    private const val MAC_REGISTRY_KEY = "ide.mac.transparentTitleBarAppearance"
    private const val WINDOWS_HEADER_KEY = "CustomWindowHeader"
    private const val LINUX_REGISTRY_KEY = "ide.linux.custom.title.bar"

    /** OS branches the probe dispatches over; `UNKNOWN` is the safe-default else branch. */
    enum class Os { MAC, WINDOWS, LINUX, UNKNOWN }

    /**
     * Pure OS-dispatch function extracted from the default supplier so tests can
     * exercise every branch without having to mock `SystemInfo`'s final static
     * fields (which mockk cannot touch — see class KDoc). Default args read
     * [SystemInfo] so production behaviour is unchanged; tests pass explicit
     * booleans to reach the [Os.WINDOWS], [Os.LINUX], and [Os.UNKNOWN] branches.
     */
    @VisibleForTesting
    internal fun computeOs(
        isMac: Boolean = SystemInfo.isMac,
        isWindows: Boolean = SystemInfo.isWindows,
        isLinux: Boolean = SystemInfo.isLinux,
    ): Os =
        when {
            isMac -> Os.MAC
            isWindows -> Os.WINDOWS
            isLinux -> Os.LINUX
            else -> Os.UNKNOWN
        }

    private val defaultOsSupplier: () -> Os = { computeOs() }

    /**
     * Per-thread override storage for the OS-detection seam. `null` means "use the
     * production supplier" — only tests ever write a non-null entry, and they must
     * clear it in `@AfterTest` via [resetOsSupplierForTests].
     *
     * Why ThreadLocal rather than the prior `@Volatile var`? Gradle runs JUnit tests
     * in parallel workers by default; a shared `@Volatile` supplier leaks a pinned
     * OS from one test into a concurrent sibling's probe call, producing
     * intermittent "looks like macOS in a Windows test" false negatives. The
     * ThreadLocal isolates the override to the mutating test's own thread.
     */
    private val osSupplierOverride: ThreadLocal<(() -> Os)?> = ThreadLocal.withInitial { null }

    /**
     * Test seam for OS detection. Production code reads [SystemInfo]; tests override this
     * supplier to exercise each branch. Always restore via [resetOsSupplierForTests] in
     * teardown to prevent leaking state across tests.
     *
     * Backed by a per-thread override ([osSupplierOverride]) so concurrent test
     * workers cannot leak OS overrides into each other. The `var` shape with
     * get/set accessors is preserved for test-API compatibility — `osSupplier = { ... }`
     * on the mutating test's own thread writes into the thread-local; the read side
     * falls back to [defaultOsSupplier] when the thread-local is null (production
     * path and any thread that never set an override).
     */
    internal var osSupplier: () -> Os
        @VisibleForTesting
        get() = osSupplierOverride.get() ?: defaultOsSupplier

        @VisibleForTesting
        set(value) {
            osSupplierOverride.set(value)
        }

    /** Restore the production [osSupplier] on the calling thread. Intended for test teardown. */
    @VisibleForTesting
    internal fun resetOsSupplierForTests() {
        osSupplierOverride.remove()
    }

    private val log = logger<ChromeDecorationsProbe>()

    /**
     * One-shot gate for the per-session INFO diagnostic log (Phase 40.2 H-5). The
     * first `isCustomHeaderActive()` call records which OS branch ran and which
     * raw inputs fed the decision, so a user whose Main Toolbar row is disabled
     * can point at their `idea.log` to see WHY the probe said no. Subsequent
     * calls within the same session stay silent.
     */
    private val diagnosticLogged = AtomicBoolean(false)

    /**
     * Returns a typed [ChromeSupport] result describing whether chrome tinting can
     * reach the title bar and, when it cannot, why. Never throws: a missing UIManager
     * key resolves to `false`, and a missing Registry key returns the supplied default.
     *
     * Phase 40.3c Refactor 3: introduced so the Settings UI can read the reason code
     * straight from the probe instead of re-sampling `SystemInfo.isMac/isWindows/isLinux`
     * on its own to build the "disabled" tooltip.
     */
    fun probe(): ChromeSupport {
        val os = osSupplier()
        val unified = UIManager.getBoolean(MAC_UNIFIED_KEY)
        val tpab = Registry.`is`(MAC_REGISTRY_KEY, false)
        val winHeader = UIManager.getBoolean(WINDOWS_HEADER_KEY)
        val linuxReg = Registry.`is`(LINUX_REGISTRY_KEY, false)
        val result: ChromeSupport =
            when (os) {
                Os.MAC -> {
                    if (unified || tpab) {
                        ChromeSupport.Supported
                    } else {
                        ChromeSupport.Unsupported.NativeMacTitleBar
                    }
                }
                Os.WINDOWS -> {
                    if (winHeader) {
                        ChromeSupport.Supported
                    } else {
                        ChromeSupport.Unsupported.WindowsNoCustomHeader
                    }
                }
                Os.LINUX -> {
                    if (linuxReg) {
                        ChromeSupport.Supported
                    } else {
                        ChromeSupport.Unsupported.GnomeSsd
                    }
                }
                Os.UNKNOWN -> ChromeSupport.Unsupported.UnknownOs
            }
        // H-5: one-shot INFO diagnostic per session so users can see WHY the
        // Main Toolbar toggle was disabled (or enabled) from idea.log alone.
        // Gate with AtomicBoolean.compareAndSet so concurrent first calls from
        // settings panel + MainToolbarElement.apply can race without duplicate
        // log lines.
        if (diagnosticLogged.compareAndSet(false, true)) {
            log.info(
                "ChromeDecorationsProbe: os=$os result=$result sources=[" +
                    "unified=$unified,tpab=$tpab,winHeader=$winHeader,linuxReg=$linuxReg]",
            )
        }
        return result
    }

    /**
     * Back-compat boolean delegate — returns `true` iff [probe] reports [ChromeSupport.Supported].
     * Call sites that only need the boolean answer (the Settings row `.enabledIf` predicate,
     * MainToolbarElement's probe gate) keep using this method; [probe] is the typed entry for
     * callers that also need the reason code.
     */
    fun isCustomHeaderActive(): Boolean = probe() is ChromeSupport.Supported

    /** Test-only reset for the one-shot diagnostic gate. */
    @TestOnly
    internal fun resetDiagnosticLoggedForTests() {
        diagnosticLogged.set(false)
    }
}
