package dev.ayuislands.accent

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.annotations.TestOnly
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Shared native observations and refresh primitives; Studio owns all accent transactions. */
object AccentApplicator {
    private val EP_NAME = ExtensionPointName<AccentElement>("com.ayuislands.theme.accentElement")

    internal val registeredElements: List<AccentElement>
        get() = EP_NAME.extensionList

    private val log = logger<AccentApplicator>()
    internal val osActiveFrameFailureLogged = AtomicBoolean(false)
    internal val windowManagerUnavailableLogged = AtomicBoolean(false)

    /** Resolves the active project before the bookmarked frame and open-project fallbacks. */
    @RequiresEdt
    internal fun resolveFocusedProject(): com.intellij.openapi.project.Project? {
        ProjectUtil.getActiveProject()?.takeIf { it.isUsable() }?.let { return it }
        osActiveProjectFrame()?.let { return it }

        IdeFocusManager
            .getGlobalInstance()
            .lastFocusedFrame
            ?.project
            ?.takeIf { it.isUsable() }
            ?.let { return it }
        return ProjectManager
            .getInstance()
            .openProjects
            .firstOrNull { it.isUsable() }
    }

    /**
     * Scans open project frames for the one whose ancestor window is OS-active. Each frame
     * access is wrapped in try/catch because frames can be mid-dispose during a shutdown
     * race; one bad frame must not break the scan for a healthy one. Error paths — a null
     * `WindowManager` service and per-frame failures — log at WARN on first occurrence then
     * degrade to DEBUG so user-submitted idea.log captures the pathology while interactive
     * sessions do not drown in noise across repeated Apply / LAF-change / rotation-tick
     * calls.
     */
    private fun osActiveProjectFrame(): com.intellij.openapi.project.Project? {
        val windowManager = WindowManager.getInstance()
        if (windowManager == null) {
            val context = "thread=${Thread.currentThread().name}"
            if (windowManagerUnavailableLogged.compareAndSet(false, true)) {
                log.warn(
                    "WindowManager unavailable during OS-active project resolution ($context); " +
                        "falling back to IdeFocusManager cascade (further occurrences at DEBUG)",
                )
            } else {
                log.debug("WindowManager unavailable during OS-active project resolution ($context)")
            }
            return null
        }
        for ((index, frame) in windowManager.allProjectFrames.withIndex()) {
            var probedName: String? = null
            try {
                val project = frame.project ?: continue
                // Platform contract: `Project.getName()` is a safe bookmarked-name
                // accessor that does not throw even on a mid-dispose instance. A
                // prior `runCatching { project.name }.getOrNull()` here swallowed
                // the entire [Throwable] surface (Pattern B) for no defensive
                // benefit — drop the wrapper and read the property directly.
                probedName = project.name
                if (!project.isUsable()) continue
                val window = SwingUtilities.getWindowAncestor(frame.component) ?: continue
                if (window.isActive) return project
            } catch (exception: RuntimeException) {
                val context =
                    "index=$index, project=${probedName ?: "<unresolved>"}, " +
                        "thread=${Thread.currentThread().name}"
                if (osActiveFrameFailureLogged.compareAndSet(false, true)) {
                    log.warn(
                        "Skipping frame during OS-active resolution ($context) " +
                            "(further failures logged at DEBUG)",
                        exception,
                    )
                } else {
                    log.debug("Skipping frame during OS-active resolution ($context)", exception)
                }
            }
        }
        return null
    }

    /** Repaints every live peer, including windows not yet showing. */
    internal fun repaintAllWindows(windows: Array<Window>) {
        // Predicate intentionally differs from
        // LiveChromeRefresher.forEachShowingWindow.isShowing (read-side).
        // Write-side tolerates not-yet-showing windows so queued paint
        // lands when the peer flips to showing — see KDoc above.
        for (window in windows) {
            if (!window.isDisplayable) continue
            window.repaint()
        }
    }
}

internal object ExternalChromeOwnership {
    private val memberships = java.util.EnumMap<AccentElementId, Membership>(AccentElementId::class.java)
    private val pending = mutableListOf<OwnershipCheckpoint>()

    /** A stale target has no effective membership; observing it does not reset native ownership. */
    fun isOwned(id: AccentElementId): Boolean = memberships[id]?.let { it.owned && it.target.isCurrent() } == true

    /** Captures one transition without running recovery or publishing membership before acknowledgement. */
    fun prepare(
        id: AccentElementId,
        owned: Boolean,
        segment: AccentApplyScope.Segment? = null,
    ): OwnershipCheckpoint? {
        check(SwingUtilities.isEventDispatchThread()) { "External Chrome preparation must run on EDT" }
        checkWritable()
        checkNoPending(id)
        val before = memberships[id]
        if (before == null && !owned) return null
        if (before != null && before.target.isCurrent() && before.owned == owned) return null
        return OwnershipCheckpoint(id, before, Membership(UiDefaultsTarget(), owned), segment)
    }

    @TestOnly
    fun resetForTests() {
        uiDefaultsOwner.restore()
        memberships.clear()
        pending.clear()
    }

    private fun checkWritable() {
        val scope = AccentApplyScope.active?.takeUnless { it.isCommitted }
        scope?.checkWritable()
    }

    private fun checkNoPending(id: AccentElementId) {
        check(pending.none { it.id == id && it.hasPending }) {
            "External Chrome membership has unresolved recovery: $id"
        }
    }

    /** Native target and value are facts; reference identity distinguishes newer equal-valued membership. */
    data class Membership(
        val target: UiDefaultsTarget,
        val owned: Boolean,
    )

    class OwnershipCheckpoint internal constructor(
        val id: AccentElementId,
        val before: Membership?,
        val planned: Membership,
        private val segment: AccentApplyScope.Segment?,
    ) : AccentWriteCheckpoint {
        private val dependencies = mutableListOf<AccentCheckpoint>()
        override var state = AccentWriteState.PREPARED
            private set

        override val hasPending: Boolean
            get() = state == AccentWriteState.OWNED

        override fun apply(): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "External Chrome membership writes must run on EDT" }
            if (state != AccentWriteState.PREPARED) return false
            checkWritable()
            checkNoPending(id)
            if (!planned.target.isCurrent() || memberships[id] !== before) {
                state = AccentWriteState.RELINQUISHED
                return false
            }
            state = AccentWriteState.OWNED
            memberships[id] = planned
            return true
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            check(SwingUtilities.isEventDispatchThread()) { "External Chrome restoration must run on EDT" }
            if (state == AccentWriteState.PREPARED) commit()
            if (!hasPending) return
            if (!planned.target.isCurrent()) {
                state = AccentWriteState.RELINQUISHED
                dependencies.clear()
                return
            }
            val related =
                blockedBy.filter { (segment != null && it in segment) || (it is OwnershipCheckpoint && it.id == id) }
            for (checkpoint in related.asReversed()) {
                if (dependencies.none { it === checkpoint }) dependencies += checkpoint
            }
            check(related.isEmpty()) { "External chrome ownership is waiting for surface recovery: $id" }
            restoreAccentCheckpoints(dependencies)
            if (memberships[id] === planned) {
                if (before == null) memberships.remove(id) else memberships[id] = before
                state = AccentWriteState.RESTORED
            } else {
                state = AccentWriteState.RELINQUISHED
            }
            dependencies.clear()
        }

        override fun commit() {
            if (state != AccentWriteState.RELINQUISHED) state = AccentWriteState.RESTORED
            dependencies.clear()
        }

        override fun retain() {
            if (hasPending && this !in pending) pending += this
        }
    }
}

internal fun isElementEnabled(
    isSelected: Boolean,
    isPremiumAllowed: Boolean,
): Boolean = !isPremiumAllowed || isSelected

internal fun canForceOverride(
    overrides: Collection<String>,
    elementId: AccentElementId,
    isPremiumAllowed: Boolean,
): Boolean = isPremiumAllowed && elementId.name in overrides

private fun com.intellij.openapi.project.Project.isUsable(): Boolean = !isDefault && !isDisposed
