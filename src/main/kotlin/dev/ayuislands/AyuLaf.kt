package dev.ayuislands

import com.intellij.ide.ui.LafManager
import javax.swing.SwingUtilities

object AyuLaf {
    @Suppress("UnstableApiUsage")
    fun currentThemeName(lafManager: LafManager = LafManager.getInstance()): String {
        val currentTheme = lafManager.currentUIThemeLookAndFeel
        return currentTheme?.name.orEmpty()
    }

    @Suppress("UnstableApiUsage") // No stable public API resolves installed theme display names.
    fun switchToThemeByName(
        themeName: String,
        shouldLockEditorScheme: Boolean,
        shouldApplyLater: Boolean,
    ): Boolean {
        val lafManager = LafManager.getInstance()
        val target = lafManager.installedThemes.firstOrNull { it.name == themeName } ?: return false
        val apply = {
            lafManager.setCurrentLookAndFeel(target, shouldLockEditorScheme)
            lafManager.updateUI()
        }
        if (shouldApplyLater) {
            SwingUtilities.invokeLater(apply)
        } else {
            apply()
        }
        return true
    }
}
