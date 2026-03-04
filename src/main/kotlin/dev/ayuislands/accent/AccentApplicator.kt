package dev.ayuislands.accent

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import java.awt.Window
import java.lang.reflect.Method
import javax.swing.SwingUtilities
import javax.swing.UIManager

object AccentApplicator {
    private val EP_NAME =
        ExtensionPointName<AccentElement>(
            "com.ayuislands.theme.accentElement",
        )

    private val log = logger<AccentApplicator>()
    private const val DARK_FOREGROUND_HEX = 0x1F2430
    private val DARK_FOREGROUND = Color(DARK_FOREGROUND_HEX)

    // Cached CodeGlance Pro reflection objects (resolved once per session)
    private var cgpService: Any? = null
    private var cgpGetState: Method? = null
    private var cgpSetViewportColor: Method? = null
    private var cgpSetViewportBorderColor: Method? = null
    private var cgpSetViewportBorderThickness: Method? = null
    private var cgpMethodsResolved = false

    // Always-on UIManager keys (not per-element toggleable)
    private val ALWAYS_ON_UI_KEYS =
        listOf(
            // GotItTooltip
            "GotItTooltip.background",
            "GotItTooltip.borderColor",
            // Default button
            "Button.default.startBackground",
            "Button.default.endBackground",
            // Focus border
            "Component.focusedBorderColor",
            "Component.focusColor",
            // Drag and drop
            "DragAndDrop.borderColor",
            // Trial widget
            "TrialWidget.Alert.borderColor",
            "TrialWidget.Alert.foreground",
            // Split editor border
            "OnePixelDivider.background",
            // Tab underlines (always accent, not toggleable)
            "ToolWindow.HeaderTab.underlineColor",
            "TabbedPane.underlineColor",
        )

    // Always-on editor ColorKeys (not per-element toggleable)
    private val ALWAYS_ON_EDITOR_COLOR_KEYS =
        listOf(
            ColorKey.find("BUTTON_BACKGROUND"),
            ColorKey.find("WARNING_FOREGROUND"),
        )

    private data class AttrOverride(
        val key: String,
        val foreground: Boolean = false,
        val effectColor: Boolean = false,
        val errorStripe: Boolean = false,
    )

    // Always-on editor TextAttributesKey overrides
    private val ALWAYS_ON_EDITOR_ATTR_OVERRIDES =
        listOf(
            AttrOverride("BOOKMARKS_ATTRIBUTES", errorStripe = true),
            AttrOverride("DEBUGGER_INLINED_VALUES_MODIFIED", foreground = true),
            AttrOverride("LIVE_TEMPLATE_ATTRIBUTES", effectColor = true),
            AttrOverride("LOG_INFO_OUTPUT", foreground = true),
            AttrOverride("RUNTIME_ERROR", effectColor = true),
            AttrOverride("SMART_COMPLETION_STATISTICAL_MATCHED_ITEM", foreground = true),
            AttrOverride("TEXT_STYLE_WARNING", effectColor = true),
            AttrOverride("TODO_DEFAULT_ATTRIBUTES", foreground = true),
            AttrOverride("WARNING_ATTRIBUTES", effectColor = true, errorStripe = true),
        )

    fun apply(accentHex: String) {
        val accent = Color.decode(accentHex)
        val state = AyuIslandsSettings.getInstance().state
        val variant = AyuVariant.detect()

        // All work batched into a single EDT dispatch (UIManager.put is not
        // thread-safe, and elements previously posted their own invokeLater)
        val work =
            Runnable {
                applyAlwaysOnUiKeys(accent)

                for (element in EP_NAME.extensionList) {
                    val enabled = state.isToggleEnabled(element.id)
                    if (!enabled) {
                        neutralizeOrRevert(element, variant)
                        continue
                    }
                    val conflict = ConflictRegistry.getConflictFor(element.id)
                    val forceOverride = element.id.name in state.forceOverrides
                    if (conflict != null && !forceOverride) {
                        neutralizeOrRevert(element, variant)
                        continue
                    }
                    if (conflict != null) {
                        log.warn(
                            "Force-overriding ${conflict.pluginDisplayName} conflict for ${element.displayName}",
                        )
                    }
                    try {
                        element.apply(accent)
                    } catch (exception: RuntimeException) {
                        log.warn(
                            "Failed to apply accent to ${element.displayName}",
                            exception,
                        )
                    }
                }

                syncCodeGlanceProViewport(accentHex)
                applyAlwaysOnEditorKeys(accent)
                val windows = Window.getWindows()
                repaintAllWindows(windows)
            }

        if (SwingUtilities.isEventDispatchThread()) {
            work.run()
        } else {
            SwingUtilities.invokeLater(work)
        }
    }

    fun revertAll() {
        // All revert work batched into a single EDT dispatch
        val work =
            Runnable {
                for (key in ALWAYS_ON_UI_KEYS) {
                    UIManager.put(key, null)
                }
                UIManager.put("GotItTooltip.foreground", null)
                UIManager.put("GotItTooltip.Button.foreground", null)
                UIManager.put("GotItTooltip.Header.foreground", null)
                UIManager.put("Button.default.focusedBorderColor", null)
                UIManager.put("Button.default.startBorderColor", null)
                UIManager.put("Button.default.endBorderColor", null)

                for (element in EP_NAME.extensionList) {
                    try {
                        element.revert()
                    } catch (exception: RuntimeException) {
                        log.warn(
                            "Failed to revert ${element.displayName}",
                            exception,
                        )
                    }
                }

                revertAlwaysOnEditorKeys()
                val windows = Window.getWindows()
                repaintAllWindows(windows)
            }

        if (SwingUtilities.isEventDispatchThread()) {
            work.run()
        } else {
            SwingUtilities.invokeLater(work)
        }
    }

    private fun neutralizeOrRevert(
        element: AccentElement,
        variant: AyuVariant?,
    ) {
        try {
            if (variant != null) {
                element.applyNeutral(variant)
            } else {
                element.revert()
            }
        } catch (exception: RuntimeException) {
            log.warn("Failed to neutralize ${element.displayName}", exception)
        }
    }

    private fun applyAlwaysOnUiKeys(accent: Color) {
        for (key in ALWAYS_ON_UI_KEYS) {
            UIManager.put(key, accent)
        }

        // Contrast foreground for accent-background elements (GotItTooltip, buttons)
        val contrastForeground = if (ColorUtil.isDark(accent)) Color.WHITE else DARK_FOREGROUND
        UIManager.put("GotItTooltip.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Button.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Header.foreground", contrastForeground)

        // Darkened accent for default button borders (~15% darker)
        val darkenedAccent = ColorUtil.darker(accent, 1)
        UIManager.put("Button.default.focusedBorderColor", darkenedAccent)
        UIManager.put("Button.default.startBorderColor", darkenedAccent)
        UIManager.put("Button.default.endBorderColor", darkenedAccent)
    }

    private fun applyAlwaysOnEditorKeys(accent: Color) {
        val scheme = EditorColorsManager.getInstance().globalScheme

        // ColorKey entries
        for (colorKey in ALWAYS_ON_EDITOR_COLOR_KEYS) {
            scheme.setColor(colorKey, accent)
        }

        // TextAttributesKey entries -- clone existing, override only accent properties
        for (override in ALWAYS_ON_EDITOR_ATTR_OVERRIDES) {
            val attrKey = TextAttributesKey.find(override.key)
            val existing = scheme.getAttributes(attrKey)
            val updated = existing?.clone() ?: TextAttributes()
            if (override.foreground) updated.foregroundColor = accent
            if (override.effectColor) updated.effectColor = accent
            if (override.errorStripe) updated.errorStripeColor = accent
            scheme.setAttributes(attrKey, updated)
        }

        // Notify editors to repaint with an updated scheme
        // Wrapped in ReadAction because Jupyter's NotebookEditorColorsListener
        // accesses PSI from globalSchemeChange, which requires read access.
        ReadAction.run<RuntimeException> {
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }

    private fun revertAlwaysOnEditorKeys() {
        val scheme = EditorColorsManager.getInstance().globalScheme

        for (colorKey in ALWAYS_ON_EDITOR_COLOR_KEYS) {
            scheme.setColor(colorKey, null)
        }

        for (override in ALWAYS_ON_EDITOR_ATTR_OVERRIDES) {
            val attrKey = TextAttributesKey.find(override.key)
            scheme.setAttributes(attrKey, null)
        }

        ReadAction.run<RuntimeException> {
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }

    private fun repaintAllWindows(windows: Array<Window>) {
        for (window in windows) {
            window.repaint()
        }
    }

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
            log.warn("CodeGlance Pro method resolution failed: ${exception.javaClass.simpleName}: ${exception.message}")
        } catch (exception: RuntimeException) {
            log.warn("CodeGlance Pro method resolution failed: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private fun syncCodeGlanceProViewport(accentHex: String) {
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
            val cause = exception.cause
            log.warn("CodeGlance Pro sync failed: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        } catch (exception: ReflectiveOperationException) {
            log.warn("CodeGlance Pro sync failed: ${exception.javaClass.simpleName}: ${exception.message}")
        } catch (exception: RuntimeException) {
            log.warn("CodeGlance Pro sync failed: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}
