package dev.ayuislands.projectview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState

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
            startOwnership(project, registryValue)
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
        val current = ownership
        if (current == null) {
            releasePersistedOwnership(registryValue)
            return
        }
        if (project !in current.projects) return

        detectManualDrift(current, registryValue)
        if (current.projects.size > 1) {
            current.projects -= project
            return
        }

        if (!current.isDrifted) {
            restoreSnapshot(current.snapshot, registryValue)
        }
        AyuIslandsSettings.getInstance().state.clearRootPathSnapshot()
        current.projects -= project
        ownership = null
    }

    private fun releasePersistedOwnership(registryValue: RegistryValue) {
        val state = AyuIslandsSettings.getInstance().state
        val snapshot = state.rootPathSnapshot() ?: return
        if (registryValue.asBoolean()) {
            LOG.warn("Project view Registry value changed outside Ayu; preserving the manual value")
        } else {
            restoreSnapshot(snapshot, registryValue)
        }
        state.clearRootPathSnapshot()
    }

    private fun restoreSnapshot(
        snapshot: RegistrySnapshot,
        registryValue: RegistryValue,
    ) {
        if (snapshot.wasChanged) {
            registryValue.setValue(snapshot.value)
        } else {
            registryValue.resetToDefault()
        }
    }

    private fun startOwnership(
        project: Project,
        registryValue: RegistryValue,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        val savedSnapshot = state.rootPathSnapshot()
        val snapshot =
            savedSnapshot
                ?: RegistrySnapshot(
                    value = registryValue.asBoolean(),
                    wasChanged = registryValue.isChangedFromDefault(),
                )
        val current =
            RegistryOwnership(
                snapshot = snapshot,
                projects = mutableSetOf(project),
            )
        ownership = current

        if (savedSnapshot != null) {
            detectManualDrift(current, registryValue)
            return
        }

        state.storeRootPathSnapshot(snapshot)
        try {
            registryValue.setValue(false)
        } catch (exception: RuntimeException) {
            ownership = null
            state.clearRootPathSnapshot()
            throw exception
        }
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

private fun AyuIslandsState.rootPathSnapshot(): RegistrySnapshot? =
    if (hasRootPathLease) {
        RegistrySnapshot(
            value = wasRootPathShown,
            wasChanged = wasRootPathChanged,
        )
    } else {
        null
    }

private fun AyuIslandsState.storeRootPathSnapshot(snapshot: RegistrySnapshot) {
    wasRootPathShown = snapshot.value
    wasRootPathChanged = snapshot.wasChanged
    hasRootPathLease = true
}

private fun AyuIslandsState.clearRootPathSnapshot() {
    hasRootPathLease = false
    wasRootPathShown = false
    wasRootPathChanged = false
}
