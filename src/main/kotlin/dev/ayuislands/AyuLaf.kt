package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import javax.swing.LookAndFeel
import javax.swing.SwingUtilities
import javax.swing.UIManager

object AyuLaf {
    private var ownedChange: OwnedThemeChange? = null

    /** A transaction owns reapplication for this synchronous native callback, including rollback. */
    internal fun switchOwnedTheme(
        target: UIThemeLookAndFeelInfo,
        lafManager: LafManager = LafManager.getInstance(),
        restoredLaf: LookAndFeel? = null,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "Owned theme transitions require EDT" }
        val previous = ownedChange
        ownedChange = OwnedThemeChange(lafManager, target)
        try {
            lafManager.setCurrentLookAndFeel(target, true)
            if (restoredLaf != null) UIManager.setLookAndFeel(restoredLaf)
            lafManager.updateUI()
        } finally {
            ownedChange = previous
        }
    }

    /** Foreign/manual callbacks retain their usual behavior even during an owned native transition. */
    internal fun ownsThemeChange(source: LafManager): Boolean {
        val change = ownedChange ?: return false
        return source === change.manager && source.currentUIThemeLookAndFeel === change.target
    }

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
            switchToTheme(target, shouldLockEditorScheme, lafManager)
        }
        if (shouldApplyLater) {
            SwingUtilities.invokeLater(apply)
        } else {
            apply()
        }
        return true
    }

    /** Apply an already observed platform theme synchronously, retaining the caller's scheme-lock policy. */
    fun switchToTheme(
        target: UIThemeLookAndFeelInfo,
        shouldLockEditorScheme: Boolean,
        lafManager: LafManager = LafManager.getInstance(),
    ) {
        lafManager.setCurrentLookAndFeel(target, shouldLockEditorScheme)
        lafManager.updateUI()
    }

    private data class OwnedThemeChange(
        val manager: LafManager,
        val target: UIThemeLookAndFeelInfo,
    )
}
