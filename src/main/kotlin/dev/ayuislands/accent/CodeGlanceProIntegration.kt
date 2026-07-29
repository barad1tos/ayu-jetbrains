package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.AyuPlugin
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import org.jetbrains.annotations.TestOnly
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * CodeGlance Pro integration helper. Owns the reflection chain that talks
 * to BOTH CGP classes the integration depends on:
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
 *     future maintainer who bumps the CGP version must target the state
 *     class, not the service class.
 *
 * Extracted from [AccentApplicator] to keep that object below the detekt
 * `TooManyFunctions` threshold. The cross-object test seam
 * ([AccentApplicator.codeGlanceProRevertHook] +
 * [AccentApplicator.resetCodeGlanceProRevertHookForTests]) stays on
 * [AccentApplicator] because source-regex tests bind those names there.
 *
 * Pattern G — apply/revert symmetry: every write path
 * ([syncCodeGlanceProViewport]) has a paired revert
 * ([revertCodeGlanceProViewport]) so a theme switch / license loss closes
 * the same surface that an apply opened.
 */
internal object CodeGlanceProIntegration {
    private val log = logger<CodeGlanceProIntegration>()

    private const val CGP_RESOLUTION_FAILED = "method resolution failed"
    private const val CGP_SYNC_FAILED = "sync failed"
    private const val CGP_RESTORE_FAILED = "restore failed"
    private const val AYU_BORDER_THICKNESS = 1

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
     * Owned by this object — the constants are exclusively read here. The compiled
     * values are locked in `AccentApplicatorCodeGlanceProDefaultsDocTest` (test
     * source set); the javap recipe above stays the re-verification path when
     * bumping CGP.
     */
    internal const val CGP_DEFAULT_VIEWPORT_COLOR = "00FF00"
    internal const val CGP_DEFAULT_VIEWPORT_BORDER_COLOR = "A0A0A0"
    internal const val CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS = 0

    // Cached CodeGlance Pro reflection objects (resolved once per session)
    @Volatile private var cgpService: Any? = null

    @Volatile private var cgpGetState: Method? = null

    @Volatile private var cgpGetColor: Method? = null

    @Volatile private var cgpGetBorder: Method? = null

    @Volatile private var cgpGetThickness: Method? = null

    @Volatile private var cgpSetColor: Method? = null

    @Volatile private var cgpSetBorder: Method? = null

    @Volatile private var cgpSetThickness: Method? = null

    @Volatile private var cgpMethodsResolved = false

    @Volatile private var cgpResolutionFailure: Throwable? = null

    private fun resolveCgpMethods() {
        if (cgpMethodsResolved) return
        cgpMethodsResolved = true

        try {
            val pluginId = PluginId.getId("com.nasller.CodeGlancePro")
            val cgpPlugin = AyuPlugin.findLoadedPlugin(pluginId) ?: return
            val cgpClassLoader = cgpPlugin.pluginClassLoader ?: return

            val serviceClass =
                Class.forName(
                    "com.nasller.codeglance.config.CodeGlanceConfigService",
                    true,
                    cgpClassLoader,
                )

            val service = resolveApplicationService(serviceClass) ?: return

            val getState = service.javaClass.getMethod("getState")
            val config = getState.invoke(service) ?: return
            val configClass = config.javaClass
            val getColor = configClass.getMethod("getViewportColor")
            val getBorder = configClass.getMethod("getViewportBorderColor")
            val getThickness = configClass.getMethod("getViewportBorderThickness")
            val setColor = configClass.getMethod("setViewportColor", String::class.java)
            val setBorder = configClass.getMethod("setViewportBorderColor", String::class.java)
            val setThickness = configClass.getMethod("setViewportBorderThickness", Int::class.java)

            cgpService = service
            cgpGetState = getState
            cgpGetColor = getColor
            cgpGetBorder = getBorder
            cgpGetThickness = getThickness
            cgpSetColor = setColor
            cgpSetBorder = setBorder
            cgpSetThickness = setThickness
        } catch (exception: ReflectiveOperationException) {
            cgpResolutionFailure = exception
            log.warn(
                "CodeGlance Pro $CGP_RESOLUTION_FAILED (reflective lookup, " +
                    "CGP plugin API change suspected): " +
                    "${exception.javaClass.simpleName}: ${exception.message}",
            )
        } catch (exception: RuntimeException) {
            cgpResolutionFailure = exception
            log.warn(
                "CodeGlance Pro $CGP_RESOLUTION_FAILED (unexpected runtime error " +
                    "during method resolution): " +
                    "${exception.javaClass.simpleName}: ${exception.message}",
            )
        }
    }

    /**
     * Push [accentHex] into CGP's app-scoped `CodeGlanceConfigService` cache so
     * the minimap viewport repaints with the active Ayu accent. Called from
     * [AccentApplicator.apply] (full theme apply path) and from
     * [AccentApplicator.syncCodeGlanceProViewportForSwap] (per-project focus
     * swap, same-hex fast path).
     */
    fun syncCodeGlanceProViewport(accentHex: String): IntegrationOutcome = syncCodeGlanceProViewport(accentHex, null)

    fun syncCodeGlanceProViewport(
        accentHex: String,
        context: AccentContext?,
    ): IntegrationOutcome {
        val state = AyuIslandsSettings.getInstance().state
        if (state.migrateCgpOwnership()) {
            log.warn(
                "CodeGlance Pro ownership snapshot is absent for an existing enabled integration; " +
                    "preserving the current viewport until the user explicitly re-enables sync",
            )
        }
        resolveSyncGate(state, context)?.let { return it }

        resolveCgpMethods()
        val resolutionFailure = cgpResolutionFailure
        if (resolutionFailure != null) {
            suspendOwnership(state)
            return IntegrationOutcome.Failed(CGP_RESOLUTION_FAILED, resolutionFailure)
        }
        val access = resolvedAccess() ?: return IntegrationOutcome.Skipped
        return syncResolvedViewport(state, access, accentHex.removePrefix("#"))
    }

    private fun resolveSyncGate(
        state: AyuIslandsState,
        context: AccentContext?,
    ): IntegrationOutcome? {
        if (!state.cgpIntegrationEnabled) {
            return restoreOwnedState()
        }
        if (context == AccentContext.External && !state.isExternalCodeGlanceProAllowed()) {
            return restoreOwnedState()
        }
        if (!LicenseChecker.isLicensedOrGrace()) {
            return restoreOwnedState()
        }
        if (IntegrationOwnership.fromName(state.cgpOwnership) == IntegrationOwnership.SUSPENDED) {
            return IntegrationOutcome.Skipped
        }
        return null
    }

    private fun syncResolvedViewport(
        state: AyuIslandsState,
        access: CgpAccess,
        color: String,
    ): IntegrationOutcome {
        try {
            recoverPendingViewport(state, access)?.let { return it }
            val current = access.readViewport()
            val ownership = IntegrationOwnership.fromName(state.cgpOwnership)
            when (ownership) {
                IntegrationOwnership.UNOWNED -> Unit
                IntegrationOwnership.OWNED -> {
                    val applied = state.cgpAppliedViewport()
                    if (applied == null || current != applied) {
                        suspendOwnership(state)
                        return IntegrationOutcome.Skipped
                    }
                }
                IntegrationOwnership.RECOVERY_PENDING ->
                    error("CodeGlance Pro recovery remained pending after a successful restore")
                IntegrationOwnership.SUSPENDED -> return IntegrationOutcome.Skipped
            }

            val target = CgpViewport(color, color, AYU_BORDER_THICKNESS)
            if (ownership == IntegrationOwnership.UNOWNED) {
                state.storeCgpBase(current)
            }
            val writeError = access.writeViewportOrError(target)
            if (writeError != null) {
                handleSyncRollback(state, access, current, ownership, writeError)
                return failedOutcome(CGP_SYNC_FAILED, writeError)
            }

            state.storeCgpApplied(target)
            state.cgpOwnership = IntegrationOwnership.OWNED.name
            log.info("CodeGlance Pro viewport color synced to $color")
            return IntegrationOutcome.Applied
        } catch (exception: InvocationTargetException) {
            val cause = exception.cause ?: exception
            return failedOutcome(CGP_SYNC_FAILED, cause)
        } catch (exception: ReflectiveOperationException) {
            return failedOutcome(CGP_SYNC_FAILED, exception)
        } catch (exception: RuntimeException) {
            return failedOutcome(CGP_SYNC_FAILED, exception)
        }
    }

    /**
     * Restores the exact pre-Ayu viewport only while all current values still
     * match the last successful Ayu write.
     */
    fun restoreOwnedState(): IntegrationOutcome {
        val state = AyuIslandsSettings.getInstance().state
        val ownership = IntegrationOwnership.fromName(state.cgpOwnership)
        if (ownership == IntegrationOwnership.RECOVERY_PENDING) {
            return restorePendingViewport(state)
        }
        if (ownership != IntegrationOwnership.OWNED) {
            return IntegrationOutcome.Skipped
        }
        val base = state.cgpBaseViewport()
        val applied = state.cgpAppliedViewport()
        if (base == null || applied == null) {
            suspendOwnership(state)
            return IntegrationOutcome.Skipped
        }

        restoreWithHook(state, base)?.let { return it }

        resolveCgpMethods()
        val resolutionFailure = cgpResolutionFailure
        if (resolutionFailure != null) {
            suspendOwnership(state)
            return IntegrationOutcome.Failed(CGP_RESOLUTION_FAILED, resolutionFailure)
        }
        val access = resolvedAccess() ?: return IntegrationOutcome.Skipped

        return try {
            val current = access.readViewport()
            if (current != applied) {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
            access.restoreViewport(current, base)?.let { return failedOutcome(CGP_RESTORE_FAILED, it) }
            clearOwnership(state)
            log.info("CodeGlance Pro viewport restored to its pre-Ayu values")
            IntegrationOutcome.Restored
        } catch (exception: InvocationTargetException) {
            val cause = exception.cause ?: exception
            failedOutcome(CGP_RESTORE_FAILED, cause)
        } catch (exception: ReflectiveOperationException) {
            failedOutcome(CGP_RESTORE_FAILED, exception)
        } catch (exception: RuntimeException) {
            failedOutcome(CGP_RESTORE_FAILED, exception)
        }
    }

    fun revertCodeGlanceProViewport(): IntegrationOutcome = restoreOwnedState()

    fun prepareExplicitEnable() {
        val state = AyuIslandsSettings.getInstance().state
        state.isCgpOwnershipMigrated = true
        clearOwnership(state)
    }

    private fun resolvedAccess(): CgpAccess? {
        val service = cgpService ?: return null
        val getState = cgpGetState ?: return null
        val config = getState.invoke(service) ?: return null
        return CgpAccess(
            config = config,
            getColor = cgpGetColor ?: return null,
            getBorder = cgpGetBorder ?: return null,
            getThickness = cgpGetThickness ?: return null,
            setColor = cgpSetColor ?: return null,
            setBorder = cgpSetBorder ?: return null,
            setThickness = cgpSetThickness ?: return null,
        )
    }

    private fun failedOutcome(
        operation: String,
        error: Throwable,
    ): IntegrationOutcome.Failed {
        log.warn(
            "CodeGlance Pro $operation: ${error.javaClass.simpleName}: ${error.message}",
            error,
        )
        return IntegrationOutcome.Failed(operation, error)
    }

    private fun handleSyncRollback(
        state: AyuIslandsState,
        access: CgpAccess,
        current: CgpViewport,
        ownership: IntegrationOwnership,
        error: Throwable,
    ) {
        if (!access.rollbackViewport(current, error)) {
            captureRecoveryViewport(state, access, error)
        } else if (ownership == IntegrationOwnership.UNOWNED) {
            clearOwnership(state)
        }
    }

    private fun captureRecoveryViewport(
        state: AyuIslandsState,
        access: CgpAccess,
        error: Throwable,
    ) {
        try {
            state.storeCgpApplied(access.readViewport())
            state.cgpOwnership = IntegrationOwnership.RECOVERY_PENDING.name
        } catch (captureError: ReflectiveOperationException) {
            error.addSuppressed(captureError)
            suspendOwnership(state)
        } catch (captureError: RuntimeException) {
            error.addSuppressed(captureError)
            suspendOwnership(state)
        }
    }

    private fun recoverPendingViewport(
        state: AyuIslandsState,
        access: CgpAccess,
    ): IntegrationOutcome? {
        if (IntegrationOwnership.fromName(state.cgpOwnership) != IntegrationOwnership.RECOVERY_PENDING) {
            return null
        }
        val base =
            state.cgpBaseViewport() ?: run {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
        val pending =
            state.cgpAppliedViewport() ?: run {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
        return try {
            if (access.readViewport() != pending) {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
            access.writeViewport(base)
            clearOwnership(state)
            null
        } catch (exception: InvocationTargetException) {
            failedOutcome(CGP_RESTORE_FAILED, exception.cause ?: exception)
        } catch (exception: ReflectiveOperationException) {
            failedOutcome(CGP_RESTORE_FAILED, exception)
        } catch (exception: RuntimeException) {
            failedOutcome(CGP_RESTORE_FAILED, exception)
        }
    }

    private fun restorePendingViewport(state: AyuIslandsState): IntegrationOutcome {
        val base =
            state.cgpBaseViewport() ?: run {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }

        resolveCgpMethods()
        val resolutionFailure = cgpResolutionFailure
        if (resolutionFailure != null) {
            return IntegrationOutcome.Failed(CGP_RESOLUTION_FAILED, resolutionFailure)
        }
        val access = resolvedAccess() ?: return IntegrationOutcome.Skipped
        return recoverPendingViewport(state, access) ?: IntegrationOutcome.Restored
    }

    private fun restoreWithHook(
        state: AyuIslandsState,
        base: CgpViewport,
    ): IntegrationOutcome? {
        val hook = AccentApplicator.codeGlanceProRevertHook.get() ?: return null
        return try {
            hook.invoke(base.color, base.border, base.thickness)
            clearOwnership(state)
            IntegrationOutcome.Restored
        } catch (exception: RuntimeException) {
            failedOutcome(CGP_RESTORE_FAILED, exception)
        }
    }

    private fun resolveApplicationService(serviceClass: Class<*>): Any? {
        // CGP's service class is resolved from CGP's plugin classloader, so DevKit
        // cannot prove registration at compile time. Reflecting the platform
        // lookup keeps this cross-plugin integration dynamic without adding CGP as
        // a compile-time dependency.
        val application = ApplicationManager.getApplication()
        val getService = application.javaClass.getMethod("getService", Class::class.java)
        return getService.invoke(application, serviceClass)
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
        cgpGetColor = null
        cgpGetBorder = null
        cgpGetThickness = null
        cgpSetColor = null
        cgpSetBorder = null
        cgpSetThickness = null
        cgpMethodsResolved = false
        cgpResolutionFailure = null
    }
}

private fun suspendOwnership(state: AyuIslandsState) {
    state.cgpOwnership = IntegrationOwnership.SUSPENDED.name
}

private fun clearOwnership(state: AyuIslandsState) {
    state.cgpOwnership = IntegrationOwnership.UNOWNED.name
    state.cgpBaseColor = null
    state.cgpBaseBorder = null
    state.cgpBaseThickness = 0
    state.cgpAppliedColor = null
    state.cgpAppliedBorder = null
    state.cgpAppliedThickness = 0
}

private fun AyuIslandsState.storeCgpBase(viewport: CgpViewport) {
    cgpBaseColor = viewport.color
    cgpBaseBorder = viewport.border
    cgpBaseThickness = viewport.thickness
}

private fun AyuIslandsState.storeCgpApplied(viewport: CgpViewport) {
    cgpAppliedColor = viewport.color
    cgpAppliedBorder = viewport.border
    cgpAppliedThickness = viewport.thickness
}

private fun AyuIslandsState.cgpBaseViewport(): CgpViewport? =
    CgpViewport(
        color = cgpBaseColor ?: return null,
        border = cgpBaseBorder ?: return null,
        thickness = cgpBaseThickness,
    )

private fun AyuIslandsState.cgpAppliedViewport(): CgpViewport? =
    CgpViewport(
        color = cgpAppliedColor ?: return null,
        border = cgpAppliedBorder ?: return null,
        thickness = cgpAppliedThickness,
    )

private fun AyuIslandsState.migrateCgpOwnership(): Boolean {
    if (isCgpOwnershipMigrated) return false
    isCgpOwnershipMigrated = true
    if (!cgpIntegrationEnabled ||
        IntegrationOwnership.fromName(cgpOwnership) != IntegrationOwnership.UNOWNED
    ) {
        return false
    }
    cgpOwnership = IntegrationOwnership.SUSPENDED.name
    return true
}

private data class CgpViewport(
    val color: String,
    val border: String,
    val thickness: Int,
)

private data class CgpAccess(
    val config: Any,
    val getColor: Method,
    val getBorder: Method,
    val getThickness: Method,
    val setColor: Method,
    val setBorder: Method,
    val setThickness: Method,
) {
    fun readViewport(): CgpViewport =
        CgpViewport(
            color = getColor.invoke(config) as? String ?: error("viewportColor getter returned non-string"),
            border = getBorder.invoke(config) as? String ?: error("viewportBorderColor getter returned non-string"),
            thickness =
                (getThickness.invoke(config) as? Number)?.toInt()
                    ?: error("viewportBorderThickness getter returned non-number"),
        )

    fun writeViewport(viewport: CgpViewport) {
        setColor.invoke(config, viewport.color)
        setBorder.invoke(config, viewport.border)
        setThickness.invoke(config, viewport.thickness)
    }
}

private fun CgpAccess.writeViewportOrError(viewport: CgpViewport): Throwable? =
    try {
        writeViewport(viewport)
        null
    } catch (exception: InvocationTargetException) {
        exception.cause ?: exception
    } catch (exception: ReflectiveOperationException) {
        exception
    } catch (exception: RuntimeException) {
        exception
    }

private fun CgpAccess.restoreViewport(
    current: CgpViewport,
    base: CgpViewport,
): Throwable? =
    try {
        writeViewport(base)
        null
    } catch (exception: InvocationTargetException) {
        val error = exception.cause ?: exception
        rollbackViewport(current, error)
        error
    } catch (exception: ReflectiveOperationException) {
        rollbackViewport(current, exception)
        exception
    } catch (exception: RuntimeException) {
        rollbackViewport(current, exception)
        exception
    }

private fun CgpAccess.rollbackViewport(
    current: CgpViewport,
    originalError: Throwable,
): Boolean =
    try {
        writeViewport(current)
        true
    } catch (rollbackError: ReflectiveOperationException) {
        originalError.addSuppressed(rollbackError)
        false
    } catch (rollbackError: RuntimeException) {
        originalError.addSuppressed(rollbackError)
        false
    }
