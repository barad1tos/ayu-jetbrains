package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioThemeChange

/** External LAF choices enter shared admission; the owning transaction already handles its own LAF events. */
class AyuIslandsLafListener : LafManagerListener {
    override fun lookAndFeelChanged(source: LafManager) {
        if (AyuLaf.ownsThemeChange(source)) return
        val themeName = AyuLaf.currentThemeName(source)
        val change =
            if (AyuVariant.fromThemeName(themeName) == null) {
                StudioNativeChange.Appearance
            } else {
                StudioNativeChange.Theme(StudioThemeChange.Explicit(themeName))
            }
        StudioService.getInstance().nativeChanged(change)
    }
}
