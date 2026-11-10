package dev.ayuislands.projectview

import com.intellij.openapi.project.Project

/** Exact native observation; raw text also distinguishes equal boolean values with different defaults. */
internal data class RootPathValue(
    val raw: String,
    val wasChanged: Boolean,
)

internal data class RootPathBaseline(
    val value: Boolean,
    val wasChanged: Boolean,
)

internal data class RootPathOwner(
    val baseline: RootPathBaseline,
    val projects: Set<Project>,
    val isDrifted: Boolean = false,
)

/** The dormant fields remain independent of hasLease so rollback can preserve their original values. */
internal data class RootPathMetadata(
    val hasLease: Boolean,
    val wasShown: Boolean,
    val wasChanged: Boolean,
) {
    val baseline: RootPathBaseline?
        get() = if (hasLease) RootPathBaseline(wasShown, wasChanged) else null
}

internal data class RootPathState(
    val native: RootPathValue,
    val owner: RootPathOwner?,
    val metadata: RootPathMetadata,
)

/** A public Registry operation; its exact default status is observed only after execution. */
internal sealed interface RootPathWrite {
    data class Set(
        val raw: String,
    ) : RootPathWrite

    data object Reset : RootPathWrite
}

internal data class RootPathPlan(
    val after: RootPathState,
    val write: RootPathWrite? = null,
    val foundDrift: Boolean = false,
)

internal enum class RootPathRestore { WRITE, RESTORED, FOREIGN }

/** The legacy and retained paths use the same decisions over explicit observations. */
internal object RootPathPolicy {
    fun matches(
        write: RootPathWrite,
        observed: RootPathValue,
    ): Boolean =
        when (write) {
            is RootPathWrite.Set -> observed.raw == write.raw
            RootPathWrite.Reset -> !observed.wasChanged
        }

    fun resolveRecovery(
        before: RootPathValue,
        lastWritten: RootPathValue,
        current: RootPathValue,
    ): RootPathRestore =
        when (current) {
            before -> RootPathRestore.RESTORED
            lastWritten -> RootPathRestore.WRITE
            else -> RootPathRestore.FOREIGN
        }

    fun plan(
        before: RootPathState,
        projects: Set<Project>,
        hidePath: Boolean,
    ): RootPathPlan =
        when {
            projects.isEmpty() -> RootPathPlan(before)
            hidePath -> acquire(before, projects)
            else -> release(before, projects)
        }

    private fun acquire(
        before: RootPathState,
        projects: Set<Project>,
    ): RootPathPlan {
        val current = before.owner
        if (current != null) {
            val drifted = current.isDrifted || before.native.raw.toBoolean()
            return RootPathPlan(
                before.copy(owner = current.copy(projects = current.projects + projects, isDrifted = drifted)),
                foundDrift = drifted && !current.isDrifted,
            )
        }
        val saved = before.metadata.baseline
        val baseline = saved ?: RootPathBaseline(before.native.raw.toBoolean(), before.native.wasChanged)
        val drifted = saved != null && before.native.raw.toBoolean()
        val owner = RootPathOwner(baseline, projects, drifted)
        if (saved != null) return RootPathPlan(before.copy(owner = owner), foundDrift = drifted)
        return RootPathPlan(
            before.copy(
                owner = owner,
                metadata =
                    RootPathMetadata(
                        hasLease = true,
                        wasShown = baseline.value,
                        wasChanged = baseline.wasChanged,
                    ),
            ),
            RootPathWrite.Set("false"),
        )
    }

    private fun release(
        before: RootPathState,
        projects: Set<Project>,
    ): RootPathPlan {
        val current = before.owner
        if (current == null) {
            val saved = before.metadata.baseline ?: return RootPathPlan(before)
            return releaseLast(before, saved, before.native.raw.toBoolean())
        }
        if (current.projects.none { it in projects }) return RootPathPlan(before)
        val drifted = current.isDrifted || before.native.raw.toBoolean()
        val remaining = current.projects - projects
        if (remaining.isNotEmpty()) {
            return RootPathPlan(
                before.copy(owner = current.copy(projects = remaining, isDrifted = drifted)),
                foundDrift = drifted && !current.isDrifted,
            )
        }
        return releaseLast(before, current.baseline, drifted)
            .copy(foundDrift = drifted && !current.isDrifted)
    }

    private fun releaseLast(
        before: RootPathState,
        baseline: RootPathBaseline,
        drifted: Boolean,
    ): RootPathPlan {
        val released =
            before.copy(
                owner = null,
                metadata = RootPathMetadata(hasLease = false, wasShown = false, wasChanged = false),
            )
        if (drifted) return RootPathPlan(released, foundDrift = true)
        val write = if (baseline.wasChanged) RootPathWrite.Set(baseline.value.toString()) else RootPathWrite.Reset
        return RootPathPlan(released, write)
    }
}
