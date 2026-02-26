package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class AyuIslandsStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val themeName = LafManager.getInstance().currentUIThemeLookAndFeel.name
        LOG.info("Ayu Islands loaded — active theme: $themeName, project: ${project.name}")
    }

    companion object {
        private val LOG = logger<AyuIslandsStartupActivity>()
    }
}
