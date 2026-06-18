package dev.ayuislands.accent.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidgetFactory
import dev.ayuislands.licensing.LicenseChecker

/**
 * Factory that creates the [AccentStatusBarWidget] for each project window.
 *
 * Registered in `plugin.xml` under `com.intellij.statusBarWidgetFactory`.
 * The widget is hidden when the license gate fails — [isAvailable] returns
 * `false` for unlicensed users, so the IDE removes it from the status bar
 * entirely rather than showing a disabled stub.
 */
internal class AccentStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AccentStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "Ayu Accent Source"

    override fun isAvailable(project: Project): Boolean = LicenseChecker.isLicensedOrGrace()

    override fun createWidget(project: Project): AccentStatusBarWidget = AccentStatusBarWidget(project)

    override fun isEnabledByDefault(): Boolean = true
}
