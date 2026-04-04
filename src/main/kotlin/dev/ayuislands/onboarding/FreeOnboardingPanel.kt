package dev.ayuislands.onboarding

import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.JPanel

/** Stub panel for the free onboarding wizard. Plan 02 replaces the body with full UI. */
internal class FreeOnboardingPanel(
    @Suppress("unused") private val project: Project,
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
    }
}
