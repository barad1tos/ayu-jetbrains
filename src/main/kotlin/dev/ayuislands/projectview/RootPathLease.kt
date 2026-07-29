package dev.ayuislands.projectview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.util.concurrency.annotations.RequiresEdt

@Service(Service.Level.APP)
internal class RootPathLease {
    private var ownership: RegistryOwnership? = null

    @RequiresEdt
    fun acquire(
        project: Project,
        registryValue: RegistryValue,
    ) {
        val current = ownership
        if (current == null) {
            val snapshot =
                RegistrySnapshot(
                    value = registryValue.asBoolean(),
                    wasChanged = registryValue.isChangedFromDefault(),
                )
            registryValue.setValue(false)
            ownership =
                RegistryOwnership(
                    snapshot = snapshot,
                    projects = mutableSetOf(project),
                )
            return
        }

        detectManualDrift(current, registryValue)
        current.projects += project
    }

    @RequiresEdt
    fun release(
        project: Project,
        registryValue: RegistryValue,
    ) {
        val current = ownership ?: return
        if (project !in current.projects) return

        detectManualDrift(current, registryValue)
        current.projects -= project
        if (current.projects.isNotEmpty()) return

        if (!current.isDrifted) {
            if (current.snapshot.wasChanged) {
                registryValue.setValue(current.snapshot.value)
            } else {
                registryValue.resetToDefault()
            }
        }
        ownership = null
    }

    private fun detectManualDrift(
        current: RegistryOwnership,
        registryValue: RegistryValue,
    ) {
        if (current.isDrifted || !registryValue.asBoolean()) return
        current.isDrifted = true
        LOG.warn("Project view Registry value changed outside Ayu; preserving the manual value")
    }

    companion object {
        fun getInstance(): RootPathLease = service()

        private val LOG = logger<RootPathLease>()
    }
}

private data class RegistrySnapshot(
    val value: Boolean,
    val wasChanged: Boolean,
)

private data class RegistryOwnership(
    val snapshot: RegistrySnapshot,
    val projects: MutableSet<Project>,
    var isDrifted: Boolean = false,
)
