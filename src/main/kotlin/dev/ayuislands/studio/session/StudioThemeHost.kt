package dev.ayuislands.studio.session

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.AyuLaf
import javax.swing.LookAndFeel
import javax.swing.UIManager

/** Actual native identities; logical theme identifiers never substitute for these references. */
internal data class StudioThemeSnapshot(
    val theme: UIThemeLookAndFeelInfo?,
    val lookAndFeel: LookAndFeel,
    val scheme: EditorColorsScheme,
)

internal data class StudioThemeTransition(
    val before: StudioThemeSnapshot,
    val after: StudioThemeSnapshot,
)

/** Native theme observation and synchronous switching, owned by the caller's EDT context. */
internal interface StudioThemeHost {
    fun observe(): StudioThemeSnapshot

    fun installed(): List<UIThemeLookAndFeelInfo>

    fun switchTo(
        target: UIThemeLookAndFeelInfo,
        restoredLaf: LookAndFeel? = null,
    )

    fun schemes(): List<EditorColorsScheme>

    fun scheme(name: String): EditorColorsScheme?

    fun selectScheme(scheme: EditorColorsScheme)
}

internal class StudioIdeThemeHost : StudioThemeHost {
    override fun observe(): StudioThemeSnapshot =
        StudioThemeSnapshot(
            LafManager.getInstance().currentUIThemeLookAndFeel,
            requireNotNull(UIManager.getLookAndFeel()),
            EditorColorsManager.getInstance().globalScheme,
        )

    override fun installed(): List<UIThemeLookAndFeelInfo> = LafManager.getInstance().installedThemes.toList()

    override fun switchTo(
        target: UIThemeLookAndFeelInfo,
        restoredLaf: LookAndFeel?,
    ) {
        AyuLaf.switchOwnedTheme(target, restoredLaf = restoredLaf)
    }

    override fun schemes(): List<EditorColorsScheme> = EditorColorsManager.getInstance().allSchemes.toList()

    override fun scheme(name: String): EditorColorsScheme? = EditorColorsManager.getInstance().getScheme(name)

    override fun selectScheme(scheme: EditorColorsScheme) {
        EditorColorsManager.getInstance().setGlobalScheme(scheme)
    }
}
