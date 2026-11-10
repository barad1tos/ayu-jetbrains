package dev.ayuislands.glow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import dev.ayuislands.settings.GlowSettings
import javax.swing.JComponent

/** One discovered native host; discovery never installs listeners or overlays. */
internal data class GlowHost(
    val id: String,
    val host: JComponent,
    val isEditor: Boolean,
)

/** Shared native discovery for legacy attachment and explicit candidate preparation. */
internal object GlowHosts {
    fun findToolWindow(
        toolWindow: ToolWindow,
        settings: GlowSettings,
    ): JComponent? {
        if (!settings.isIslandEnabled(toolWindow.id)) return null
        val component = toolWindow.component
        if (!component.isDisplayable) return null
        return ComponentHierarchyUtils.findGlowHost(component)
    }

    fun findEditor(
        project: Project,
        settings: GlowSettings,
    ): JComponent? {
        if (!settings.isIslandEnabled(GlowOverlayManager.EDITOR_ID)) return null
        val editorRoot =
            FileEditorManager.getInstance(project).selectedEditor?.component
                ?: FileEditorManagerEx.getInstanceEx(project).component
                ?: return null
        return ComponentHierarchyUtils.findEditorHost(editorRoot)?.takeIf { it.isDisplayable }
    }

    fun findMissing(
        project: Project,
        settings: GlowSettings,
        existingIds: Set<String>,
    ): List<GlowHost> {
        if (!settings.enabled) return emptyList()
        val selected = existingIds.toMutableSet()
        val hosts = mutableListOf<GlowHost>()
        val manager = ToolWindowManager.getInstance(project)
        for (id in manager.toolWindowIdSet) {
            val toolWindow = manager.getToolWindow(id) ?: continue
            if (!toolWindow.isVisible || toolWindow.id in selected) continue
            val host = findToolWindow(toolWindow, settings) ?: continue
            hosts.add(GlowHost(toolWindow.id, host, isEditor = false))
            selected.add(toolWindow.id)
        }
        if (GlowOverlayManager.EDITOR_ID !in selected) {
            findEditor(project, settings)?.let { host ->
                hosts.add(GlowHost(GlowOverlayManager.EDITOR_ID, host, isEditor = true))
            }
        }
        return java.util.List.copyOf(hosts)
    }
}
