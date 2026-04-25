package dev.ayuislands.accent

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.settings.AyuIslandsSettings
import org.jetbrains.annotations.TestOnly
import java.lang.reflect.Method

/**
 * CodeGlance Pro integration helper. Owns the reflection chain that talks
 * to BOTH CGP classes the integration depends on (CA-I3, plan 40.1-02
 * review-loop):
 *
 *   - `com.nasller.codeglance.config.CodeGlanceConfigService` — app-scoped
 *     service, looked up via `ApplicationManager.getService(...)`.
 *     `getState()` returns the cached state object below.
 *   - `com.nasller.codeglance.config.CodeGlanceConfig` — the state value
 *     class whose `<init>` bytecode supplies the documented viewport
 *     defaults this object pins ([CGP_DEFAULT_VIEWPORT_COLOR] /
 *     [CGP_DEFAULT_VIEWPORT_BORDER_COLOR] /
 *     [CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS]). Re-verifying the defaults
 *     runs `javap` against `CodeGlanceConfig.class` specifically — a
 *     future agent who bumps the CGP version must target the state class,
 *     not the service class.
 *
 * Extracted from [AccentApplicator] in Phase 40.1 plan 02 (D-04, D-05) to
 * keep AccentApplicator below the detekt `TooManyFunctions` threshold for
 * objects. The cross-object test seam ([AccentApplicator.cgpRevertHook] +
 * [AccentApplicator.resetCgpRevertHookForTests]) stays on AccentApplicator
 * because Wave 0 source-regex tests bind those names there.
 *
 * Pattern G — apply/revert symmetry: every write path
 * ([syncCodeGlanceProViewport]) has a paired revert
 * ([revertCodeGlanceProViewport]) so a theme switch / license loss closes
 * the same surface that an apply opened.
 */
internal object CgpIntegration {
    private val log = logger<CgpIntegration>()

    private const val CGP_RESOLUTION_FAILED = "method resolution failed"
    private const val CGP_SYNC_FAILED = "sync failed"

    /**
     * CodeGlance Pro viewport defaults extracted via javap from
     * `com.nasller.codeglance.config.CodeGlanceConfig.<init>` in `CodeGlancePro-2.0.2.jar`.
     *
     * Re-verification command (run on any dev machine with CGP installed; the bash
     * backslash continuations keep the shell command runnable when copy-pasted):
     * ```
     * CGP_PLUGIN_DIR=~/Library/Application\ Support/JetBrains/IntelliJIdea2025.3/plugins
     * CGP_JAR="$CGP_PLUGIN_DIR/CodeGlancePro/lib/CodeGlancePro-2.0.2.jar"
     * unzip -p "$CGP_JAR" com/nasller/codeglance/config/CodeGlanceConfig.class \
     *   > /tmp/CodeGlanceConfig.class && \
     *   javap -c -p /tmp/CodeGlanceConfig.class \
     *     | grep -A 2 -E "ldc.*(00FF00|A0A0A0)|iconst_0"
     * ```
     *
     * No `#` prefix — CGP stores hex as plain uppercase 6-char strings.
     * `setViewportColor("")` is NOT a reset sentinel — the setter stores the empty
     * string as-is. When bumping CGP version, re-run the javap command and update
     * these constants ONLY if upstream changed them.
     *
     * Owned here in [CgpIntegration] (TD-I1, plan 40.1-02 review-loop). The
     * constants are exclusively read inside this object; the prior placement on
     * [AccentApplicator] inverted the dependency direction (peer object reaching
     * into the orchestrator for CGP-private values). Source-regex provenance lock
     * lives in [AccentApplicatorCgpDefaultsDocTest], rebound to this file in the
     * same commit as the move.
     */
    internal const val CGP_DEFAULT_VIEWPORT_COLOR = "00FF00"
    internal const val CGP_DEFAULT_VIEWPORT_BORDER_COLOR = "A0A0A0"
    internal const val CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS = 0

    // Cached CodeGlance Pro reflection objects (resolved once per session)
    @Volatile private var cgpService: Any? = null

    @Volatile private var cgpGetState: Method? = null

    @Volatile private var cgpSetViewportColor: Method? = null

    @Volatile private var cgpSetViewportBorderColor: Method? = null

    @Volatile private var cgpSetViewportBorderThickness: Method? = null

    @Volatile private var cgpMethodsResolved = false

    private fun resolveCgpMethods() {
        if (cgpMethodsResolved) return
        cgpMethodsResolved = true

        try {
            val pluginId = PluginId.getId("com.nasller.CodeGlancePro")
            val cgpPlugin = PluginManagerCore.getPlugin(pluginId) ?: return
            val cgpClassLoader = cgpPlugin.pluginClassLoader ?: return

            val serviceClass =
                Class.forName(
                    "com.nasller.codeglance.config.CodeGlanceConfigService",
                    true,
                    cgpClassLoader,
                )

            val service = ApplicationManager.getApplication().getService(serviceClass) ?: return

            cgpService = service
            cgpGetState = service.javaClass.getMethod("getState")

            // Resolve config methods from the state object's class
            val config = cgpGetState!!.invoke(service) ?: return
            val configClass = config.javaClass
            cgpSetViewportColor = configClass.getMethod("setViewportColor", String::class.java)
            cgpSetViewportBorderColor = configClass.getMethod("setViewportBorderColor", String::class.java)
            cgpSetViewportBorderThickness = configClass.getMethod("setViewportBorderThickness", Int::class.java)
        } catch (exception: ReflectiveOperationException) {
            log.warn("CodeGlance Pro $CGP_RESOLUTION_FAILED: ${exception.javaClass.simpleName}: ${exception.message}")
        } catch (exception: RuntimeException) {
            log.warn("CodeGlance Pro $CGP_RESOLUTION_FAILED: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    /**
     * Push [accentHex] into CGP's app-scoped `CodeGlanceConfigService` cache so
     * the minimap viewport repaints with the active Ayu accent. Called from
     * [AccentApplicator.apply] (full theme apply path) and from
     * [AccentApplicator.syncCodeGlanceProViewportForSwap] (per-project focus
     * swap, same-hex fast path).
     */
    fun syncCodeGlanceProViewport(accentHex: String) {
        if (!AyuIslandsSettings.getInstance().state.cgpIntegrationEnabled) {
            // Pattern G + J — toggle-off symmetry. Mirror of
            // [IndentRainbowSync.apply], which reverts when its integration is
            // disabled. Without this, flipping the CGP toggle off after an
            // apply leaves the minimap viewport tinted with the previous Ayu
            // accent forever — the apply path stamped CGP's app-scoped cache
            // and the toggle prevented any further write. Driving revert here
            // restores the documented defaults so the visible state matches the
            // user's "off" intent.
            revertCodeGlanceProViewport()
            return
        }

        resolveCgpMethods()

        val service = cgpService ?: return
        val getState = cgpGetState ?: return
        val setColor = cgpSetViewportColor ?: return
        val setBorderColor = cgpSetViewportBorderColor ?: return
        val setBorderThickness = cgpSetViewportBorderThickness ?: return

        try {
            val hexWithoutHash = accentHex.removePrefix("#")
            val config = getState.invoke(service) ?: return

            setColor.invoke(config, hexWithoutHash)
            setBorderColor.invoke(config, hexWithoutHash)
            setBorderThickness.invoke(config, 1)

            // CGP panels repaint via globalSchemeChange notification (no manual walk needed)
            log.info("CodeGlance Pro viewport color synced to $hexWithoutHash")
        } catch (exception: java.lang.reflect.InvocationTargetException) {
            val cause = exception.cause ?: exception
            log.warn("CodeGlance Pro $CGP_SYNC_FAILED: ${cause.javaClass.simpleName}: ${cause.message}")
        } catch (exception: ReflectiveOperationException) {
            log.warn("CodeGlance Pro $CGP_SYNC_FAILED: ${exception.javaClass.simpleName}: ${exception.message}")
        } catch (exception: RuntimeException) {
            log.warn("CodeGlance Pro $CGP_SYNC_FAILED: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    /**
     * Reset CodeGlance Pro viewport to its documented stock defaults
     * ([CGP_DEFAULT_VIEWPORT_COLOR] / [CGP_DEFAULT_VIEWPORT_BORDER_COLOR] /
     * [CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS]) when Ayu's
     * accent is being reverted (theme switch away from Ayu, license loss).
     * Mirror of [syncCodeGlanceProViewport]. Pattern G — apply/revert symmetry.
     *
     * The [AccentApplicator.cgpRevertHook] check runs BEFORE the [cgpService]
     * null-guard chain so tests can observe the revert without injecting
     * non-null reflection refs (matches [ChromeDecorationsProbe.osSupplier]
     * precedent — RESEARCH §Edge Cases §3 resolution).
     *
     * Not idempotent across config drift: if the user manually edits CGP
     * settings between invocations, this function overwrites them with the
     * documented defaults. Acceptable degradation — see CONTEXT.md §specifics.
     */
    fun revertCodeGlanceProViewport() {
        // Pattern G + J — revert path MUST work regardless of the
        // [cgpIntegrationEnabled] toggle. Driven from two distinct call sites:
        //   1. [AccentApplicator.revertAll] on theme switch / license loss — at
        //      this point the toggle state is irrelevant; the surface needs
        //      cleanup so the next theme starts clean.
        //   2. [syncCodeGlanceProViewport] when the toggle was just flipped to
        //      false — the gate is checked at the apply entry, not here.
        // Adding a gate here would silently skip case 1 when the user had the
        // integration disabled at theme-switch time, leaving CGP tinted with
        // the previous accent until the next apply re-fires.

        // Hook check BEFORE null-guards so tests observe revert without forcing
        // non-null reflection refs (RESEARCH §Edge Cases §3 resolution).
        val hook = AccentApplicator.cgpRevertHook.get()
        if (hook != null) {
            hook.invoke(
                CGP_DEFAULT_VIEWPORT_COLOR,
                CGP_DEFAULT_VIEWPORT_BORDER_COLOR,
                CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS,
            )
            return
        }

        resolveCgpMethods()
        val service = cgpService ?: return
        val getState = cgpGetState ?: return
        val setColor = cgpSetViewportColor ?: return
        val setBorderColor = cgpSetViewportBorderColor ?: return
        val setBorderThickness = cgpSetViewportBorderThickness ?: return

        try {
            val config = getState.invoke(service) ?: return
            setColor.invoke(config, CGP_DEFAULT_VIEWPORT_COLOR)
            setBorderColor.invoke(config, CGP_DEFAULT_VIEWPORT_BORDER_COLOR)
            setBorderThickness.invoke(config, CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS)

            log.info("CodeGlance Pro viewport reset to documented defaults")
        } catch (exception: java.lang.reflect.InvocationTargetException) {
            val cause = exception.cause ?: exception
            log.warn(
                "CodeGlance Pro revert failed: ${cause.javaClass.simpleName}: ${cause.message}",
            )
        } catch (exception: ReflectiveOperationException) {
            log.warn(
                "CodeGlance Pro revert failed: ${exception.javaClass.simpleName}: ${exception.message}",
            )
        } catch (exception: RuntimeException) {
            log.warn(
                "CodeGlance Pro revert failed: ${exception.javaClass.simpleName}: ${exception.message}",
            )
        }
    }

    /**
     * Test-only helper that resets the cached CGP reflection chain so a
     * subsequent invocation re-runs [resolveCgpMethods]. Tests that drive the
     * reflection path (CGP installed, real setters reachable via mocks)
     * MUST call this in `@AfterTest` so subsequent tests start from a clean
     * slate; without it, a leaked stub from one test poisons the next.
     *
     * Lives here rather than in the test file so the field set is owned by
     * the producer of those fields — drift between test reflection and
     * production declarations breaks at compile time, not at runtime.
     * Pattern I — typed test seam matches the production state owner.
     */
    @TestOnly
    internal fun resetReflectionCacheForTests() {
        cgpService = null
        cgpGetState = null
        cgpSetViewportColor = null
        cgpSetViewportBorderColor = null
        cgpSetViewportBorderThickness = null
        cgpMethodsResolved = false
    }
}
