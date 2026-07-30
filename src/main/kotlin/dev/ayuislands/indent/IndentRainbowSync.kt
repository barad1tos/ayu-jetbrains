package dev.ayuislands.indent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object IndentRainbowSync {
    private val log = logger<IndentRainbowSync>()
    private const val IR_PLUGIN_ID = "indent-rainbow.indent-rainbow"
    private const val RESOLUTION_FAILED = "IR method resolution failed"
    private const val SYNC_FAILED = "IR sync failed"
    private const val RESTORE_FAILED = "IR restore failed"
    private const val MAX_ALPHA_VALUE = 255

    // Cached reflection objects (resolved once per session)
    @Volatile private var irConfig: Any? = null

    @Volatile private var paletteTypeField: Field? = null

    @Volatile private var customPaletteField: Field? = null

    @Volatile private var customPaletteNumberColorsField: Field? = null

    @Volatile private var customEnumValue: Any? = null

    @Volatile private var defaultEnumValue: Any? = null

    @Volatile private var paletteEnumValues: Map<String, Any>? = null

    @Volatile private var cachedDataUpdateMethod: Method? = null

    @Volatile private var cachedDataCompanion: Any? = null

    @Volatile private var refreshMethod: Method? = null

    @Volatile private var irColorsInstance: Any? = null

    @Volatile private var methodsResolved = false

    @Volatile private var resolutionFailure: Throwable? = null

    /**
     * Syncs Indent Rainbow's custom palette to [accentHex] for the given [variant].
     *
     * Production callers:
     *   - [dev.ayuislands.accent.AccentApplicator.apply] — full theme apply path,
     *     fires once per accent change with the resolved hex.
     *   - [dev.ayuislands.settings.mappings.ProjectAccentSwapService.handleWindowActivated]
     *     — same-hex focus-swap fast path, pushes the per-project hex into IR's
     *     app-scoped IrConfig so the newly-focused project's indent palette matches
     *     the visible chrome without re-running the full apply.
     *
     * Both callers pass the resolved accent — the one that went through per-project /
     * per-language override resolution — so IR reflects the SAME color the rest of
     * the plugin just applied, not the global accent stored in settings (which
     * rotation mutates to a different value from what the focused project shows).
     */
    internal fun apply(
        variant: AyuVariant,
        accentHex: String,
    ): IntegrationOutcome =
        applyPalette(
            palette = IndentPalette.forAccent(accentHex, variant),
            logContext = "for ${variant.name}",
        )

    internal fun apply(
        context: AccentContext,
        accentHex: String,
    ): IntegrationOutcome =
        when (context) {
            is AccentContext.Ayu -> apply(context.ayuVariant, accentHex)
            AccentContext.External ->
                if (AyuIslandsSettings.getInstance().state.isExternalIndentRainbowAllowed()) {
                    applyPalette(
                        palette = IndentPalette.forExternalAccent(accentHex),
                        logContext = "for external theme",
                    )
                } else {
                    restoreOwnedState()
                }
        }

    private fun applyPalette(
        palette: IndentPalette,
        logContext: String,
    ): IntegrationOutcome {
        val state = AyuIslandsSettings.getInstance().state
        if (state.migrateIrOwnership()) {
            log.warn(
                "Indent Rainbow ownership snapshot is absent for an existing enabled integration; " +
                    "preserving the current palette until the user explicitly re-enables sync",
            )
        }
        resolveApplyGate(state)?.let { return it }

        val resolved = resolveOrReturn() ?: return unresolvedOutcome(SYNC_FAILED)
        val enumValue =
            customEnumValue ?: return schemaFailure(state, SYNC_FAILED, "CUSTOM palette enum is unavailable")

        val preset =
            IndentPreset.fromName(
                state.indentPresetName ?: IndentPreset.AMBIENT.name,
            )
        val rawAlpha = preset.alpha ?: state.indentCustomAlpha
        val alpha = rawAlpha.coerceIn(1, MAX_ALPHA_VALUE)
        val colorStrings =
            palette.toColorStrings(
                alpha,
                highlightErrors = state.irErrorHighlightEnabled,
            )
        val target =
            IrPalette(
                type = enumName(enumValue),
                palette = colorStrings.joinToString(", "),
                colorCount = colorStrings.size,
            )
        return applyResolvedPalette(
            state = state,
            resolved = resolved,
            enumValue = enumValue,
            target = target,
            logContext = logContext,
        )
    }

    private fun resolveApplyGate(state: AyuIslandsState): IntegrationOutcome? {
        if (!state.irIntegrationEnabled) {
            return restoreOwnedState()
        }
        if (!LicenseChecker.isLicensedOrGrace()) {
            return restoreOwnedState()
        }
        if (IntegrationOwnership.fromName(state.irOwnership) == IntegrationOwnership.SUSPENDED) {
            return IntegrationOutcome.Skipped
        }
        return null
    }

    private fun applyResolvedPalette(
        state: AyuIslandsState,
        resolved: ResolvedIrState,
        enumValue: Any,
        target: IrPalette,
        logContext: String,
    ): IntegrationOutcome {
        try {
            recoverPendingPalette(state, resolved)?.let { return it }
            val current = resolved.readPalette()
            val ownership = IntegrationOwnership.fromName(state.irOwnership)
            when (ownership) {
                IntegrationOwnership.UNOWNED -> Unit
                IntegrationOwnership.OWNED -> {
                    val applied = state.irAppliedPalette()
                    if (applied == null || current.palette != applied) {
                        suspendOwnership(state)
                        return IntegrationOutcome.Skipped
                    }
                }
                IntegrationOwnership.RECOVERY_PENDING ->
                    error("Indent Rainbow recovery remained pending after a successful restore")
                IntegrationOwnership.SUSPENDED -> return IntegrationOutcome.Skipped
            }

            if (ownership == IntegrationOwnership.UNOWNED) {
                state.storeIrBase(current.palette)
            }
            val writeError = resolved.writePaletteOrError(target, enumValue)
            if (writeError != null) {
                handleApplyRollback(state, resolved, current, ownership, writeError)
                return failedOutcome(SYNC_FAILED, writeError)
            }

            state.storeIrApplied(target)
            state.irOwnership = IntegrationOwnership.OWNED.name
            log.info("Indent Rainbow colors synced $logContext")
            return IntegrationOutcome.Applied
        } catch (exception: InvocationTargetException) {
            val cause = exception.cause
            return failedOutcome(SYNC_FAILED, cause ?: exception)
        } catch (exception: ReflectiveOperationException) {
            return failedOutcome(SYNC_FAILED, exception)
        } catch (exception: RuntimeException) {
            return failedOutcome(SYNC_FAILED, exception)
        }
    }

    /**
     * Restores the exact pre-Ayu palette only while all current values still
     * match the most recent successful Ayu write.
     */
    internal fun restoreOwnedState(): IntegrationOutcome {
        val state = AyuIslandsSettings.getInstance().state
        val ownership = IntegrationOwnership.fromName(state.irOwnership)
        if (ownership != IntegrationOwnership.OWNED) {
            return if (ownership == IntegrationOwnership.RECOVERY_PENDING) {
                restorePendingPalette(state)
            } else {
                IntegrationOutcome.Skipped
            }
        }
        val base = state.irBasePalette()
        val applied = state.irAppliedPalette()
        if (base == null || applied == null) {
            suspendOwnership(state)
            return IntegrationOutcome.Skipped
        }

        val resolved = resolveOrReturn() ?: return unresolvedOutcome(RESTORE_FAILED)
        val baseEnum =
            paletteEnumValues?.get(base.type)
                ?: return schemaFailure(state, RESTORE_FAILED, "palette enum ${base.type} is unavailable")

        try {
            val current = resolved.readPalette()
            if (current.palette != applied) {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
            writeRestoredPalette(state, resolved, current, base, baseEnum)?.let { return it }
            clearOwnership(state)
            log.info("Indent Rainbow palette restored to its pre-Ayu values")
            return IntegrationOutcome.Restored
        } catch (exception: InvocationTargetException) {
            return failedOutcome(RESTORE_FAILED, exception.cause ?: exception)
        } catch (exception: ReflectiveOperationException) {
            return failedOutcome(RESTORE_FAILED, exception)
        } catch (exception: RuntimeException) {
            return failedOutcome(RESTORE_FAILED, exception)
        }
    }

    internal fun revert(): IntegrationOutcome = restoreOwnedState()

    internal fun prepareExplicitEnable() {
        val state = AyuIslandsSettings.getInstance().state
        state.isIrOwnershipMigrated = true
        if (IntegrationOwnership.fromName(state.irOwnership) == IntegrationOwnership.SUSPENDED) {
            clearOwnership(state)
        }
    }

    private fun resolveOrReturn(): ResolvedIrState? {
        resolveReflection()
        defaultEnumValue ?: return null
        return ResolvedIrState(
            config = irConfig ?: return null,
            paletteTypeField = paletteTypeField ?: return null,
            customPaletteField = customPaletteField ?: return null,
            colorCountField = customPaletteNumberColorsField ?: return null,
            updateMethod = cachedDataUpdateMethod ?: return null,
            companion = cachedDataCompanion ?: return null,
            refreshMethod = refreshMethod ?: return null,
            colorsInstance = irColorsInstance ?: return null,
        )
    }

    private fun unresolvedOutcome(operation: String): IntegrationOutcome {
        val failure =
            resolutionFailure
                ?: if (irConfig != null) {
                    IllegalStateException("Indent Rainbow reflection schema is incomplete")
                } else {
                    return IntegrationOutcome.Skipped
                }
        irConfig = null
        paletteTypeField = null
        customPaletteField = null
        customPaletteNumberColorsField = null
        customEnumValue = null
        defaultEnumValue = null
        paletteEnumValues = null
        cachedDataUpdateMethod = null
        cachedDataCompanion = null
        refreshMethod = null
        irColorsInstance = null
        methodsResolved = false
        resolutionFailure = null
        return failedOutcome(operation, failure)
    }

    private fun schemaFailure(
        state: AyuIslandsState,
        operation: String,
        message: String,
    ): IntegrationOutcome.Failed {
        suspendOwnership(state)
        return failedOutcome(operation, IllegalStateException(message))
    }

    private fun failedOutcome(
        operation: String,
        error: Throwable,
    ): IntegrationOutcome.Failed {
        log.warn(
            "Indent Rainbow $operation: ${error.javaClass.simpleName}: ${error.message}",
            error,
        )
        return IntegrationOutcome.Failed(operation, error)
    }

    private fun handleApplyRollback(
        state: AyuIslandsState,
        resolved: ResolvedIrState,
        current: CurrentIrPalette,
        ownership: IntegrationOwnership,
        error: Throwable,
    ) {
        if (!rollbackPalette(resolved, current, error)) {
            captureRecoveryPalette(state, resolved, error)
        } else if (ownership == IntegrationOwnership.UNOWNED) {
            clearOwnership(state)
        }
    }

    private fun recoverPendingPalette(
        state: AyuIslandsState,
        resolved: ResolvedIrState,
    ): IntegrationOutcome? {
        if (IntegrationOwnership.fromName(state.irOwnership) != IntegrationOwnership.RECOVERY_PENDING) {
            return null
        }
        val base =
            state.irBasePalette() ?: run {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
        val pending =
            state.irPendingPalette() ?: run {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
        val baseEnum =
            paletteEnumValues?.get(base.type) ?: run {
                return failedOutcome(
                    RESTORE_FAILED,
                    IllegalStateException("palette enum ${base.type} is unavailable"),
                )
            }
        return try {
            if (resolved.readPalette().palette != pending) {
                suspendOwnership(state)
                return IntegrationOutcome.Skipped
            }
            resolved.writePalette(base, baseEnum)
            clearOwnership(state)
            null
        } catch (exception: InvocationTargetException) {
            failedOutcome(RESTORE_FAILED, exception.cause ?: exception)
        } catch (exception: ReflectiveOperationException) {
            failedOutcome(RESTORE_FAILED, exception)
        } catch (exception: RuntimeException) {
            failedOutcome(RESTORE_FAILED, exception)
        }
    }

    private fun restorePendingPalette(state: AyuIslandsState): IntegrationOutcome {
        val resolved = resolveOrReturn() ?: return unresolvedOutcome(RESTORE_FAILED)
        return recoverPendingPalette(state, resolved) ?: IntegrationOutcome.Restored
    }

    private fun rollbackPalette(
        resolved: ResolvedIrState,
        current: CurrentIrPalette,
        originalError: Throwable,
    ): Boolean =
        try {
            resolved.writePalette(current.palette, current.typeValue)
            true
        } catch (rollbackError: ReflectiveOperationException) {
            originalError.addSuppressed(rollbackError)
            false
        } catch (rollbackError: RuntimeException) {
            originalError.addSuppressed(rollbackError)
            false
        }

    private fun writeRestoredPalette(
        state: AyuIslandsState,
        resolved: ResolvedIrState,
        current: CurrentIrPalette,
        base: IrPalette,
        baseEnum: Any,
    ): IntegrationOutcome.Failed? {
        val error = resolved.writePaletteOrError(base, baseEnum) ?: return null
        handleApplyRollback(
            state = state,
            resolved = resolved,
            current = current,
            ownership = IntegrationOwnership.OWNED,
            error = error,
        )
        return failedOutcome(RESTORE_FAILED, error)
    }

    private fun resolveReflection() {
        if (methodsResolved) return
        methodsResolved = true

        try {
            val pluginId = PluginId.getId(IR_PLUGIN_ID)
            val irPlugin = AyuPlugin.findLoadedPlugin(pluginId) ?: return
            val classLoader = irPlugin.pluginClassLoader ?: return

            val configClass =
                Class.forName(
                    "indent.rainbow.settings.IrConfig",
                    true,
                    classLoader,
                )

            val config = resolveApplicationService(configClass) ?: return

            irConfig = config
            paletteTypeField =
                configClass.getDeclaredField("paletteType").apply {
                    isAccessible = true
                }
            customPaletteField =
                configClass.getDeclaredField("customPalette").apply {
                    isAccessible = true
                }
            customPaletteNumberColorsField =
                configClass
                    .getDeclaredField(
                        "customPaletteNumberColors",
                    ).apply {
                        isAccessible = true
                    }

            // Load enum values from IrColorsPaletteType
            val paletteTypeEnumClass =
                Class.forName(
                    "indent.rainbow.settings.IrColorsPaletteType",
                    true,
                    classLoader,
                )
            val enumConstants = paletteTypeEnumClass.enumConstants
            customEnumValue = enumConstants.first { (it as Enum<*>).name == "CUSTOM" }
            defaultEnumValue = enumConstants.first { (it as Enum<*>).name == "DEFAULT" }
            paletteEnumValues = enumConstants.associateBy { (it as Enum<*>).name }

            // Resolve IrCachedData.Companion.update(config) for cache refresh
            val cachedDataClass =
                Class.forName(
                    "indent.rainbow.settings.IrCachedData",
                    true,
                    classLoader,
                )
            val companion = cachedDataClass.getDeclaredField("Companion")[null]
            cachedDataCompanion = companion
            cachedDataUpdateMethod =
                companion.javaClass.getMethod(
                    "update",
                    configClass,
                )

            // Resolve IrColors.INSTANCE.refreshEditorIndentColors()
            val irColorsClass =
                Class.forName(
                    "indent.rainbow.IrColors",
                    true,
                    classLoader,
                )
            val instance = irColorsClass.getDeclaredField("INSTANCE")[null]
            irColorsInstance = instance
            refreshMethod = instance.javaClass.getMethod("refreshEditorIndentColors")
        } catch (exception: ReflectiveOperationException) {
            resolutionFailure = exception
            logResolutionWarning(exception)
        } catch (exception: RuntimeException) {
            resolutionFailure = exception
            logResolutionWarning(exception)
        }
    }

    private fun logResolutionWarning(exception: Throwable) {
        log.warn(
            "Indent Rainbow $RESOLUTION_FAILED: " +
                "${exception.javaClass.simpleName}: ${exception.message}",
        )
    }
}

private fun resolveApplicationService(serviceClass: Class<*>): Any? {
    // IR's service class is resolved from IR's plugin classloader, so DevKit
    // cannot prove registration at compile time. Reflecting the platform
    // lookup keeps this cross-plugin integration dynamic without adding IR as
    // a compile-time dependency.
    val application = ApplicationManager.getApplication()
    val getService = application.javaClass.getMethod("getService", Class::class.java)
    return getService.invoke(application, serviceClass)
}

private fun suspendOwnership(state: AyuIslandsState) {
    state.irOwnership = IntegrationOwnership.SUSPENDED.name
}

private fun clearOwnership(state: AyuIslandsState) {
    state.irOwnership = IntegrationOwnership.UNOWNED.name
    state.irBaseType = null
    state.irBasePalette = null
    state.irBaseColorCount = 0
    state.irAppliedType = null
    state.irAppliedPalette = null
    state.irAppliedColorCount = 0
}

private fun captureRecoveryPalette(
    state: AyuIslandsState,
    resolved: ResolvedIrState,
    error: Throwable,
) {
    try {
        state.storeIrApplied(resolved.readPalette().palette)
        state.irOwnership = IntegrationOwnership.RECOVERY_PENDING.name
    } catch (captureError: ReflectiveOperationException) {
        error.addSuppressed(captureError)
        suspendOwnership(state)
    } catch (captureError: RuntimeException) {
        error.addSuppressed(captureError)
        suspendOwnership(state)
    }
}

private fun ResolvedIrState.writePaletteOrError(
    palette: IrPalette,
    typeValue: Any,
): Throwable? =
    try {
        writePalette(palette, typeValue)
        null
    } catch (exception: InvocationTargetException) {
        exception.cause ?: exception
    } catch (exception: ReflectiveOperationException) {
        exception
    } catch (exception: RuntimeException) {
        exception
    }

private fun AyuIslandsState.migrateIrOwnership(): Boolean {
    if (isIrOwnershipMigrated) return false
    isIrOwnershipMigrated = true
    if (!irIntegrationEnabled ||
        IntegrationOwnership.fromName(irOwnership) != IntegrationOwnership.UNOWNED
    ) {
        return false
    }
    irOwnership = IntegrationOwnership.SUSPENDED.name
    return true
}
