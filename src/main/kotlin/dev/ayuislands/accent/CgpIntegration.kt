package dev.ayuislands.accent

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.settings.AyuIslandsSettings
import java.lang.reflect.Method

/**
 * CodeGlance Pro integration helper. Owns the reflection chain that talks to
 * `com.nasller.codeglance.config.CodeGlanceConfigService` and the apply / revert
 * paths the rest of the plugin invokes through [AccentApplicator].
 *
 * Extracted from [AccentApplicator] in Phase 40.1 plan 02 (D-04, D-05) to keep
 * AccentApplicator below the detekt `TooManyFunctions` threshold for objects.
 * The cross-object test seam ([AccentApplicator.cgpRevertHook] +
 * [AccentApplicator.resetCgpRevertHookForTests]) stays on AccentApplicator
 * because Wave 0 source-regex tests bind those names there.
 *
 * Pattern G — apply/revert symmetry: every write path
 * ([syncCodeGlanceProViewport]) has a paired revert ([revertCodeGlanceProViewport])
 * so a theme switch / license loss closes the same surface that an apply opened.
 */
internal object CgpIntegration {
    private val log = logger<CgpIntegration>()

    private const val CGP_RESOLUTION_FAILED = "method resolution failed"
    private const val CGP_SYNC_FAILED = "sync failed"

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
        if (!AyuIslandsSettings.getInstance().state.cgpIntegrationEnabled) return

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
     * ([AccentApplicator.CGP_DEFAULT_VIEWPORT_COLOR] /
     * [AccentApplicator.CGP_DEFAULT_VIEWPORT_BORDER_COLOR] /
     * [AccentApplicator.CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS]) when Ayu's
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
        if (!AyuIslandsSettings.getInstance().state.cgpIntegrationEnabled) return

        // Hook check BEFORE null-guards so tests observe revert without forcing
        // non-null reflection refs (RESEARCH §Edge Cases §3 resolution).
        val hook = AccentApplicator.cgpRevertHook.get()
        if (hook != null) {
            hook.invoke(
                AccentApplicator.CGP_DEFAULT_VIEWPORT_COLOR,
                AccentApplicator.CGP_DEFAULT_VIEWPORT_BORDER_COLOR,
                AccentApplicator.CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS,
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
            setColor.invoke(config, AccentApplicator.CGP_DEFAULT_VIEWPORT_COLOR)
            setBorderColor.invoke(config, AccentApplicator.CGP_DEFAULT_VIEWPORT_BORDER_COLOR)
            setBorderThickness.invoke(config, AccentApplicator.CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS)

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
}
