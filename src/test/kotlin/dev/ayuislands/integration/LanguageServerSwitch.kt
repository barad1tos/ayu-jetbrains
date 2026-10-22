package dev.ayuislands.integration

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager

/** Disables lsp4ij servers for a project; only runtimes that ship lsp4ij may load this object. */
internal object LanguageServerSwitch {
    fun disable(
        project: Project,
        serverIds: Set<String>,
    ) {
        val options = LanguageServerManager.StopOptions().setWillDisable(true)
        for (serverId in serverIds) {
            LanguageServerManager.getInstance(project).stop(serverId, options)
        }
    }
}
