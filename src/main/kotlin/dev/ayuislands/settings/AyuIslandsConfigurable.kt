package dev.ayuislands.settings

import com.intellij.openapi.options.Configurable
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.ui.StudioIdeSettings
import javax.swing.JComponent

/** The registered Settings entry point delegates to the application-owned Studio session. */
class AyuIslandsConfigurable internal constructor(
    private val settings: StudioIdeSettings,
) : Configurable {
    constructor() : this(StudioService.getInstance().settings())

    override fun getDisplayName(): String = "Ayu Islands"

    override fun createComponent(): JComponent = settings.createComponent()

    override fun getPreferredFocusedComponent(): JComponent? = settings.preferredFocusedComponent

    override fun isModified(): Boolean = settings.isModified()

    override fun apply() = settings.apply()

    override fun reset() = settings.reset()

    override fun cancel() = settings.cancel()

    override fun disposeUIResources() = settings.disposeUIResources()
}
