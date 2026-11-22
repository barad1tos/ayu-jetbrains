package dev.ayuislands.glow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Observes eligible hosts without installing anything or reading user preferences. */
internal fun availableGlowHosts(
    project: Project,
    enabled: Set<GlowIsland>,
    occupied: Set<String>,
): Map<String, JComponent> =
    buildMap {
        val editor = GlowIsland.EDITOR
        if (editor in enabled && editor.id !in occupied) {
            editorGlowHost(project)?.let { put(editor.id, it) }
        }
        if (enabled.any { it != editor }) putAll(toolWindowHosts(project, enabled, occupied))
    }.filterValues { host ->
        host.isShowing && host.width > 0 && host.height > 0 && SwingUtilities.getRootPane(host) != null
    }

private fun toolWindowHosts(
    project: Project,
    enabled: Set<GlowIsland>,
    occupied: Set<String>,
): Map<String, JComponent> =
    buildMap {
        val manager = ToolWindowManager.getInstance(project)
        for (id in manager.toolWindowIdSet) {
            if (id in occupied || id == GlowIsland.EDITOR.id || GlowIsland.fromId(id) !in enabled) continue
            val window = manager.getToolWindow(id) ?: continue
            if (window.isVisible) toolWindowGlowHost(window)?.let { put(id, it) }
        }
    }

internal fun toolWindowGlowHost(toolWindow: ToolWindow): JComponent? =
    toolWindow.component.takeIf { it.isDisplayable }?.let { ComponentHierarchyUtils.findGlowHost(it) }

internal fun editorGlowHost(project: Project): JComponent? {
    val root =
        FileEditorManager.getInstance(project).selectedEditor?.component
            ?: FileEditorManagerEx.getInstanceEx(project).component
    return root?.let(ComponentHierarchyUtils::findEditorHost)?.takeIf { it.isDisplayable }
}
