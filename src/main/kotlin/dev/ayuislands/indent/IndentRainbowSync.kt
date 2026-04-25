package dev.ayuislands.indent

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object IndentRainbowSync {
    private val log = logger<IndentRainbowSync>()
    private const val IR_PLUGIN_ID = "indent-rainbow.indent-rainbow"
    private const val RESOLUTION_FAILED = "IR method resolution failed"
    private const val SYNC_FAILED = "IR sync failed"
    private const val MAX_ALPHA_VALUE = 255

    // Cached reflection objects (resolved once per session)
    @Volatile private var irConfig: Any? = null

    @Volatile private var paletteTypeField: Field? = null

    @Volatile private var customPaletteField: Field? = null

    @Volatile private var customPaletteNumberColorsField: Field? = null

    @Volatile private var customEnumValue: Any? = null

    @Volatile private var defaultEnumValue: Any? = null

    @Volatile private var cachedDataUpdateMethod: Method? = null

    @Volatile private var cachedDataCompanion: Any? = null

    @Volatile private var refreshMethod: Method? = null

    @Volatile private var irColorsInstance: Any? = null

    @Volatile private var methodsResolved = false

    /**
     * Syncs Indent Rainbow's custom palette to [accentHex] for the given [variant].
     *
     * Production callers (CA-I2, plan 40.1-02 review-loop):
     *   - [dev.ayuislands.accent.AccentApplicator.apply] — full theme apply path,
     *     fires once per accent change with the resolved hex.
     *   - [dev.ayuislands.settings.mappings.ProjectAccentSwapService.handleWindowActivated]
     *     — same-hex focus-swap fast path (D-07), pushes the per-project hex into IR's
     *     app-scoped IrConfig so the newly-focused project's indent palette matches
     *     the visible chrome without re-running the full apply.
     *
     * Both callers pass the resolved accent — the one that went through per-project /
     * per-language override resolution — so IR reflects the SAME color the rest of
     * the plugin just applied, not the global accent stored in settings (which
     * rotation mutates to a different value from what the focused project shows).
     */
    fun apply(
        variant: AyuVariant,
        accentHex: String,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.irIntegrationEnabled) {
            revert()
            return
        }

        val resolved = resolveOrReturn() ?: return
        val paletteField = customPaletteField ?: return
        val numberField = customPaletteNumberColorsField ?: return
        val enumValue = customEnumValue ?: return

        try {
            val palette = IndentPalette.forAccent(accentHex, variant)
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
            val customPaletteValue = colorStrings.joinToString(", ")

            resolved.paletteTypeField[resolved.config] = enumValue
            paletteField[resolved.config] = customPaletteValue
            numberField.setInt(resolved.config, colorStrings.size)

            resolved.flushCache()

            log.info("Indent Rainbow colors synced for ${variant.name}")
        } catch (exception: InvocationTargetException) {
            val cause = exception.cause
            logWarning(SYNC_FAILED, cause ?: exception)
            notifyFailure()
        } catch (exception: ReflectiveOperationException) {
            logWarning(SYNC_FAILED, exception)
            notifyFailure()
        } catch (exception: RuntimeException) {
            logWarning(SYNC_FAILED, exception)
            notifyFailure()
        }
    }

    /**
     * Revert IR's `paletteType` to its DEFAULT enum value, flush IR's reflection
     * cache. Idempotent. EDT-safe — `IrConfig` writes are unsynchronized but
     * IR itself reads paletteType only from EDT during paint.
     *
     * Reachable from:
     * - [apply] when `irIntegrationEnabled` becomes false (settings toggle)
     * - [dev.ayuislands.accent.AccentApplicator.revertAll] on theme-switch /
     *   license loss (Phase 40.1 D-04 wiring — cross-object caller)
     *
     * Does NOT clear `customPalette` — IR ignores it unless `paletteType == CUSTOM`.
     * If the user manually flips `paletteType` back to CUSTOM while a non-Ayu
     * theme is active, stale Ayu palette will render until the next [apply]
     * overwrites it. Accepted degradation per Phase 40.1 CONTEXT §specifics —
     * regression-locked by `IndentRainbowSyncTest.revert does not clear customPalette`.
     */
    fun revert() {
        val resolved = resolveOrReturn() ?: return
        val defaultEnum = defaultEnumValue ?: return

        try {
            resolved.paletteTypeField[resolved.config] = defaultEnum
            resolved.flushCache()

            log.info("Indent Rainbow palette type reset to DEFAULT")
        } catch (exception: ReflectiveOperationException) {
            logWarning("revert failed", exception)
        } catch (exception: RuntimeException) {
            logWarning("revert failed", exception)
        }
    }

    private fun resolveOrReturn(): ResolvedIrState? {
        resolveReflection()
        return ResolvedIrState(
            config = irConfig ?: return null,
            paletteTypeField = paletteTypeField ?: return null,
            updateMethod = cachedDataUpdateMethod ?: return null,
            companion = cachedDataCompanion ?: return null,
            refreshMethod = refreshMethod ?: return null,
            colorsInstance = irColorsInstance ?: return null,
        )
    }

    private data class ResolvedIrState(
        val config: Any,
        val paletteTypeField: Field,
        val updateMethod: Method,
        val companion: Any,
        val refreshMethod: Method,
        val colorsInstance: Any,
    ) {
        fun flushCache() {
            updateMethod.invoke(companion, config)
            refreshMethod.invoke(colorsInstance)
        }
    }

    private fun resolveReflection() {
        if (methodsResolved) return
        methodsResolved = true

        try {
            val pluginId = PluginId.getId(IR_PLUGIN_ID)
            val irPlugin = PluginManagerCore.getPlugin(pluginId) ?: return
            val classLoader = irPlugin.pluginClassLoader ?: return

            val configClass =
                Class.forName(
                    "indent.rainbow.settings.IrConfig",
                    true,
                    classLoader,
                )

            val config =
                ApplicationManager.getApplication().getService(configClass)
                    ?: return

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
            logWarning(RESOLUTION_FAILED, exception)
            notifyFailure()
        } catch (exception: RuntimeException) {
            logWarning(RESOLUTION_FAILED, exception)
            notifyFailure()
        }
    }

    private fun notifyFailure() {
        val state = AyuIslandsSettings.getInstance().state
        val currentVersion =
            PluginManagerCore
                .getPlugin(PluginId.getId(IR_PLUGIN_ID))
                ?.version

        if (state.irFailedVersion == currentVersion) return
        state.irFailedVersion = currentVersion

        try {
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("Ayu Islands")
                .createNotification(
                    "Indent Rainbow integration failed",
                    "Indent Rainbow may have updated its API. Colors will use IR defaults. " +
                        "Please report at github.com/barad1tos/ayu-jetbrains/issues",
                    NotificationType.WARNING,
                ).notify(null)
        } catch (exception: RuntimeException) {
            log.warn(
                "Failed to show IR failure notification: ${exception.message}",
            )
        }
    }

    private fun logWarning(
        action: String,
        exception: Throwable,
    ) {
        log.warn(
            "Indent Rainbow $action: " +
                "${exception.javaClass.simpleName}: ${exception.message}",
        )
    }
}
