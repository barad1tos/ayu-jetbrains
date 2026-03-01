package dev.ayuislands.accent

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import java.awt.Window
import javax.swing.SwingUtilities
import javax.swing.UIManager

object AccentApplicator {

    private val EP_NAME = ExtensionPointName<AccentElement>(
        "com.ayuislands.theme.accentElement"
    )

    private val log = logger<AccentApplicator>()

    // Always-on UIManager keys (not per-element toggleable)
    private val ALWAYS_ON_UI_KEYS = listOf(
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
    )

    // Always-on editor ColorKeys (not per-element toggleable)
    private val ALWAYS_ON_EDITOR_COLOR_KEYS = listOf(
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
    private val ALWAYS_ON_EDITOR_ATTR_OVERRIDES = listOf(
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

        // Always-on UIManager keys
        applyAlwaysOnUiKeys(accent)

        // EP-registered element keys with toggle + conflict awareness
        for (element in EP_NAME.extensionList) {
            val enabled = state.isToggleEnabled(element.id)
            if (!enabled) {
                try {
                    element.revert()
                } catch (exception: Exception) {
                    log.warn("Failed to revert ${element.displayName}", exception)
                }
                continue
            }
            val hasConflict = ConflictRegistry.hasConflict(element.id)
            val forceOverride = element.id.name in state.forceOverrides
            if (hasConflict && !forceOverride) {
                try {
                    element.revert()
                } catch (exception: Exception) {
                    log.warn("Failed to revert ${element.displayName}", exception)
                }
                continue
            }
            if (hasConflict && forceOverride) {
                log.warn("Force-overriding conflict for ${element.displayName}")
            }
            try {
                element.apply(accent)
            } catch (exception: Exception) {
                log.warn("Failed to apply accent to ${element.displayName}", exception)
            }
        }

        // CodeGlance Pro viewport color sync (skips silently if CGP not installed)
        syncCodeGlanceProViewport(accentHex)

        // EDT-only work: always-on editor keys + global scheme notification + repaint
        val edtWork = Runnable {
            applyAlwaysOnEditorKeys(accent)
            repaintAllWindows()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    fun revertAll() {
        // Revert always-on UIManager keys
        for (key in ALWAYS_ON_UI_KEYS) {
            UIManager.put(key, null)
        }
        // Contrast foreground keys
        UIManager.put("GotItTooltip.foreground", null)
        UIManager.put("GotItTooltip.Button.foreground", null)
        UIManager.put("GotItTooltip.Header.foreground", null)
        // Darkened button border keys
        UIManager.put("Button.default.focusedBorderColor", null)
        UIManager.put("Button.default.startBorderColor", null)
        UIManager.put("Button.default.endBorderColor", null)

        // Revert all EP-registered elements
        for (element in EP_NAME.extensionList) {
            try {
                element.revert()
            } catch (exception: Exception) {
                log.warn("Failed to revert ${element.displayName}", exception)
            }
        }

        // EDT-only: revert editor keys + repaint
        val edtWork = Runnable {
            revertAlwaysOnEditorKeys()
            repaintAllWindows()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    private fun applyAlwaysOnUiKeys(accent: Color) {
        for (key in ALWAYS_ON_UI_KEYS) {
            UIManager.put(key, accent)
        }

        // Contrast foreground for accent-background elements (GotItTooltip, buttons)
        val contrastForeground = if (ColorUtil.isDark(accent)) Color.WHITE else Color(0x1F, 0x24, 0x30)
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

        // Notify editors to repaint with updated scheme
        ApplicationManager.getApplication().messageBus
            .syncPublisher(EditorColorsManager.TOPIC)
            .globalSchemeChange(null)
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

        ApplicationManager.getApplication().messageBus
            .syncPublisher(EditorColorsManager.TOPIC)
            .globalSchemeChange(null)
    }

    private fun repaintAllWindows() {
        for (window in Window.getWindows()) {
            window.repaint()
        }
    }

    private fun syncCodeGlanceProViewport(accentHex: String) {
        try {
            val hexWithoutHash = accentHex.removePrefix("#")

            val pluginId = PluginId.getId("com.nasller.CodeGlancePro")
            val cgpPlugin = PluginManagerCore.getPlugin(pluginId)
            if (cgpPlugin == null) {
                log.info("CodeGlance Pro not found via PluginManagerCore (id=$pluginId)")
                return
            }
            val cgpClassLoader = cgpPlugin.pluginClassLoader
                ?: throw IllegalStateException("CodeGlance Pro classloader not available")

            val serviceClass = Class.forName(
                "com.nasller.codeglance.config.CodeGlanceConfigService",
                true,
                cgpClassLoader,
            )

            val service = ApplicationManager.getApplication().getService(serviceClass)
                ?: throw IllegalStateException("CodeGlanceConfigService not registered")

            val config = service.javaClass.getMethod("getState").invoke(service)

            config.javaClass.getMethod("setViewportColor", String::class.java)
                .invoke(config, hexWithoutHash)
            config.javaClass.getMethod("setViewportBorderColor", String::class.java)
                .invoke(config, hexWithoutHash)
            config.javaClass.getMethod("setViewportBorderThickness", Int::class.java)
                .invoke(config, 1)

            // Repaint GlancePanel components without dispose+recreate
            SwingUtilities.invokeLater {
                for (window in Window.getWindows()) {
                    repaintCodeGlancePanels(window)
                }
            }

            log.info("CodeGlance Pro viewport color synced to $hexWithoutHash")
        } catch (exception: Exception) {
            val cause = if (exception is java.lang.reflect.InvocationTargetException) exception.cause else exception
            log.warn("CodeGlance Pro sync failed: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        }
    }

    private fun repaintCodeGlancePanels(container: java.awt.Container) {
        for (component in container.components) {
            if (component.javaClass.name.contains("GlancePanel")) {
                component.repaint()
            }
            if (component is java.awt.Container) {
                repaintCodeGlancePanels(component)
            }
        }
    }
}
