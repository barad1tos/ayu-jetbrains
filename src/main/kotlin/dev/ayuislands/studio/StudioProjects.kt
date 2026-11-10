package dev.ayuislands.studio

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.runtime.FocusSnapshot
import dev.ayuislands.studio.session.StudioNativeFocus
import java.util.IdentityHashMap
import javax.swing.SwingUtilities

/** Native project incarnations and focus epochs; no requested or accepted preferences live here. */
internal class StudioProjects(
    private val readProjects: () -> List<Project> = { ProjectManager.getInstance().openProjects.toList() },
    private val readFocus: () -> Project? = AccentApplicator::resolveFocusedProject,
    private val readKey: (Project) -> String? = AccentResolver::projectKey,
) {
    private val entries = IdentityHashMap<Project, ProjectEntry>()
    private var nextGeneration = GLOBAL_GENERATION
    private var focusGeneration = 0L
    private var focusIncarnation = 0L
    private var focused: Project? = null
    private var opened = emptyList<Project>()

    /** A missing or ambiguous project key cannot silently become the Global target. */
    fun capture(project: Project?): TargetObservation? {
        refresh()
        if (project == null) return observation(StudioTarget.Global)
        val entry = entries[project] ?: return null
        return observation(entry.target).takeUnless { it.presence == TargetPresence.UNAVAILABLE }
    }

    fun observe(target: StudioTarget): TargetObservation {
        refresh()
        return observation(target)
    }

    fun focus(): StudioNativeFocus? {
        refresh()
        val target =
            if (opened.isEmpty()) {
                StudioTarget.Global
            } else {
                val entry = entries[focused] ?: return null
                if (observation(entry.target).presence == TargetPresence.UNAVAILABLE) return null
                entry.target
            }
        return StudioNativeFocus(FocusSnapshot(target, focusGeneration), focused)
    }

    fun openProjects(): List<Project> {
        refresh()
        return opened
    }

    private fun refresh() {
        check(SwingUtilities.isEventDispatchThread()) { "Studio project observations require EDT" }
        opened = readProjects().filter { !it.isDefault && !it.isDisposed }
        entries.keys.removeIf { retained -> opened.none { it === retained } }
        for (project in opened) {
            val key = readKey(project)?.takeIf(String::isNotBlank)
            val previous = entries[project]
            if (key == null) {
                entries.remove(project)
            } else if (previous?.target?.key?.value != key) {
                entries[project] = ProjectEntry(StudioTarget.Project(StudioProjectKey(key)), ++nextGeneration)
            }
        }
        focused = readFocus()?.takeIf { selected -> opened.any { it === selected } }
        val incarnation = if (opened.isEmpty()) GLOBAL_GENERATION else entries[focused]?.generation ?: 0L
        if (incarnation != focusIncarnation) {
            focusIncarnation = incarnation
            focusGeneration++
        }
    }

    private fun observation(target: StudioTarget): TargetObservation {
        if (target == StudioTarget.Global) {
            val presence = if (opened.isEmpty()) TargetPresence.ACTIVE else TargetPresence.INACTIVE
            return TargetObservation(target, GLOBAL_GENERATION, presence)
        }
        val matching =
            entries.entries.singleOrNull { it.value.target == target }
                ?: return TargetObservation(target, 0L, TargetPresence.UNAVAILABLE)
        val presence = if (matching.key === focused) TargetPresence.ACTIVE else TargetPresence.INACTIVE
        return TargetObservation(target, matching.value.generation, presence)
    }

    private data class ProjectEntry(
        val target: StudioTarget.Project,
        val generation: Long,
    )

    private companion object {
        const val GLOBAL_GENERATION = 1L
    }
}
