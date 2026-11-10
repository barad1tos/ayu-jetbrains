package dev.ayuislands.studio.session

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.ui.ComponentTreeRefresher
import java.awt.Window

/** Native discovery and refresh; candidate resolution and retained writes belong to the participant. */
internal interface StudioAccentHost {
    val schemes: StudioSchemeHost

    fun elements(): List<AccentElement>

    fun systemAccent(): String?

    fun refresh(
        projects: List<Project>,
        repaintWindows: Boolean,
    )
}

/** Shares the existing extension census, scheme host and native refresh workers with the legacy owner. */
internal class IdeAccentHost(
    override val schemes: StudioSchemeHost = IdeFontHost(),
) : StudioAccentHost {
    override fun elements(): List<AccentElement> = AccentApplicator.registeredElements

    override fun systemAccent(): String? = SystemAccentProvider.resolve()

    override fun refresh(
        projects: List<Project>,
        repaintWindows: Boolean,
    ) {
        projects.filter { !it.isDefault && !it.isDisposed }.forEach(ComponentTreeRefresher::notifyOnly)
        if (repaintWindows) AccentApplicator.repaintAllWindows(Window.getWindows())
    }
}
