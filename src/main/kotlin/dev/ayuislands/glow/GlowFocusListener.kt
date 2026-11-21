package dev.ayuislands.glow

import kotlinx.coroutines.CancellationException
import java.awt.Color
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.border.Border

/** Owns a component's glow border and listener registrations. All callbacks run on the EDT. */
internal class GlowFocusListener(
    private val component: JComponent,
    glowColor: Color,
    glowStyle: GlowStyle,
    baseIntensity: Int,
    private val callbacksAllowed: () -> Boolean,
    private val onDetached: () -> Unit,
) : FocusListener,
    HierarchyListener {
    private var isAttached = false
    private var appearance = GlowFocusAppearance(glowColor, glowStyle, baseIntensity)
    private var ownership: Ownership? = null
    private var returnedOwnedBaseline = false

    private class Ownership(
        val baseline: Border?,
        val installed: GlowFocusBorder,
    )

    private fun canChangeBorder(observed: Border?): Boolean =
        ownership?.let { observed === it.installed } ?: (observed !is GlowFocusBorder)

    fun captureCallbacks(): CallbackCheckpoint {
        returnedOwnedBaseline = false
        return CallbackCheckpoint()
    }

    inner class CallbackCheckpoint {
        private val borderAtCapture = component.border
        private val ownedAtCapture = ownership?.takeIf { it.installed === borderAtCapture }
        private val mayWrapBaseline = canChangeBorder(borderAtCapture)

        fun reconcile() {
            if (!isAttached) return
            if (!component.hasFocus() || !appearance.enabled) {
                restoreBorder()
                return
            }
            val current = component.border
            val isReleasedBaseline =
                returnedOwnedBaseline &&
                    ownedAtCapture != null &&
                    ownership == null &&
                    current === ownedAtCapture.baseline
            if ((mayWrapBaseline && current === borderAtCapture) || isReleasedBaseline) applyBorder()
        }
    }

    fun captureAppearance(
        isCurrent: () -> Boolean = { true },
        transform: (GlowFocusAppearance) -> GlowFocusAppearance,
    ): Checkpoint? {
        checkTarget(isCurrent)
        val planned = transform(appearance)
        return if (appearance.matches(planned)) null else Checkpoint(planned, isCurrent)
    }

    inner class Checkpoint(
        val planned: GlowFocusAppearance,
        private val isCurrent: () -> Boolean,
    ) {
        private val before = appearance
        private val beforeBorder = component.border
        private val beforeOwner = ownership
        private val wasFocused = component.hasFocus()
        private val canWriteBorder = canChangeBorder(beforeBorder)
        private val baseline = if (beforeOwner != null && canWriteBorder) beforeOwner.baseline else beforeBorder
        private val plannedOwner =
            if (canWriteBorder && wasFocused && planned.enabled) makeBorder(baseline, planned) else null
        private val plannedBorder = if (canWriteBorder) plannedOwner?.installed ?: baseline else beforeBorder
        private var phase = GlowCheckpointPhase.PREPARED
        private var appearanceState = GlowFieldState.PREPARED
        private var borderState = GlowFieldState.PREPARED.takeIf { plannedBorder !== beforeBorder }
        private var restoration: GlowFocusRestore? = null
        private var lastAttempt: GlowFocusRestore? = null

        val record: GlowFocusRecord
            get() =
                GlowFocusRecord(
                    before,
                    planned,
                    beforeBorder,
                    plannedBorder,
                    baseline,
                    phase,
                    buildMap {
                        put(GlowFocusField.APPEARANCE, appearanceState)
                        borderState?.let { put(GlowFocusField.BORDER, it) }
                    },
                    restoration,
                )

        fun apply() {
            checkTarget(isCurrent)
            check(phase == GlowCheckpointPhase.PREPARED) { "Glow focus change already started" }
            check(appearance === before && ownership === beforeOwner) { "Glow focus state changed before apply" }
            if (canWriteBorder) checkBorder(beforeBorder, wasFocused)
            phase = GlowCheckpointPhase.APPLYING
            appearanceState = GlowFieldState.WRITING
            appearance = planned
            if (borderState != null) {
                borderState = GlowFieldState.WRITING
                installBorder(plannedBorder, plannedOwner)
            }
            checkTarget(isCurrent)
            check(appearance === planned) { "Glow focus appearance did not settle" }
            if (canWriteBorder) checkBorder(plannedBorder, wasFocused)
            phase = GlowCheckpointPhase.APPLIED
        }

        fun prepareRestore() {
            checkRestorable()
            if (!isAttached || borderState != GlowFieldState.WRITING) return
            checkTarget(isCurrent)
            restoration = planFocusRestore(record, component.border, component.hasFocus(), lastAttempt)
        }

        fun restore(blockedBy: Set<GlowFocusField> = emptySet()) {
            checkRestorable()
            if (phase == GlowCheckpointPhase.RESTORED) return
            if (!isAttached) {
                appearanceState = appearanceState.afterDetach()
                borderState = borderState?.afterDetach()
                phase = GlowCheckpointPhase.RESTORED
                return
            }
            phase = GlowCheckpointPhase.RESTORING
            val failures = mutableListOf<RuntimeException>()
            for (field in GlowFocusField.entries.asReversed()) {
                try {
                    when (field) {
                        GlowFocusField.BORDER -> restoreBorderField(blockedBy)
                        GlowFocusField.APPEARANCE -> restoreAppearance(blockedBy)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RuntimeException) {
                    failures.add(failure)
                }
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                throw first
            }
            phase = GlowCheckpointPhase.RESTORED
        }

        private fun GlowFieldState.afterDetach(): GlowFieldState =
            when (this) {
                GlowFieldState.PREPARED -> GlowFieldState.RESTORED
                GlowFieldState.WRITING -> GlowFieldState.FOREIGN
                else -> this
            }

        fun accept() {
            checkEdt()
            check(phase == GlowCheckpointPhase.APPLIED || phase == GlowCheckpointPhase.ACCEPTED) {
                "Glow focus apply has not completed"
            }
            phase = GlowCheckpointPhase.ACCEPTED
        }

        private fun restoreAppearance(blockedBy: Set<GlowFocusField>) {
            if (appearanceState == GlowFieldState.WRITING) {
                check(GlowFocusField.APPEARANCE !in blockedBy) { "A newer receipt owns Glow focus appearance" }
                checkTarget(isCurrent)
                if (appearance === planned) appearance = before
                appearanceState = if (appearance === before) GlowFieldState.RESTORED else GlowFieldState.FOREIGN
            } else if (appearanceState == GlowFieldState.PREPARED) {
                appearanceState = GlowFieldState.RESTORED
            }
        }

        private fun restoreBorderField(blockedBy: Set<GlowFocusField>) {
            when (borderState) {
                GlowFieldState.PREPARED -> borderState = GlowFieldState.RESTORED
                GlowFieldState.WRITING -> {
                    check(GlowFocusField.BORDER !in blockedBy) { "A newer receipt owns the Glow focus border" }
                    checkTarget(isCurrent)
                    restorePreparedBorder(checkNotNull(restoration) { "Glow focus restoration has not been prepared" })
                }
                else -> Unit
            }
        }

        private fun restorePreparedBorder(prepared: GlowFocusRestore) {
            val current = component.border
            if (prepared.outcome == GlowFieldState.FOREIGN || current !== prepared.observed) {
                settleBorder(GlowFieldState.FOREIGN)
                return
            }
            check(component.hasFocus() == prepared.isFocused) { "Focus changed after preparing Glow restoration" }
            if (prepared.outcome == GlowFieldState.WRITING) {
                lastAttempt = prepared
                installBorder(prepared.desired, beforeOwner?.takeIf { it.installed === prepared.desired })
                checkTarget(isCurrent)
                check(component.border !== current) { "Glow focus restoration did not settle" }
                check(component.hasFocus() == prepared.isFocused) { "Focus changed during Glow restoration" }
            }
            val restored = component.border === prepared.desired
            settleBorder(if (restored) GlowFieldState.RESTORED else GlowFieldState.FOREIGN)
        }

        private fun settleBorder(outcome: GlowFieldState) {
            if (ownership === plannedOwner || ownership === beforeOwner) {
                if (outcome == GlowFieldState.FOREIGN) returnedOwnedBaseline = false
                ownership =
                    beforeOwner?.takeIf { outcome == GlowFieldState.RESTORED && component.border === it.installed }
            }
            borderState = outcome
        }

        private fun checkRestorable() {
            checkEdt()
            check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow focus changes cannot be restored" }
        }
    }

    fun attach() {
        if (isAttached) return
        isAttached = true
        component.addFocusListener(this)
        component.addHierarchyListener(this)
        if (component.hasFocus()) applyBorder()
    }

    fun detach() {
        if (!isAttached) return
        isAttached = false
        component.removeFocusListener(this)
        component.removeHierarchyListener(this)
        restoreBorder()
        onDetached()
    }

    override fun focusGained(event: FocusEvent) {
        if (canHandle(event)) applyBorder()
    }

    override fun focusLost(event: FocusEvent) {
        if (canHandle(event)) restoreBorder()
    }

    private fun canHandle(event: FocusEvent): Boolean =
        isAttached && event.component === component && callbacksAllowed()

    override fun hierarchyChanged(event: HierarchyEvent) {
        val displayabilityChanged =
            (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
        if (displayabilityChanged && !component.isDisplayable) detach()
    }

    private fun applyBorder() {
        if (!appearance.enabled || ownership?.installed?.let { it === component.border } == true) return
        val border = makeBorder(component.border, appearance)
        installBorder(border.installed, border)
    }

    private fun makeBorder(
        baseline: Border?,
        settings: GlowFocusAppearance,
    ): Ownership = Ownership(baseline, GlowFocusBorder(baseline, settings.color, settings.style, settings.intensity))

    private fun installBorder(
        border: Border?,
        owner: Ownership?,
    ) {
        val previous = ownership
        returnedOwnedBaseline =
            owner == null &&
            previous != null &&
            component.border === previous.installed &&
            border === previous.baseline
        ownership = owner
        component.border = border
        component.repaint()
    }

    private fun restoreBorder() {
        val owner = ownership ?: return
        ownership = null
        if (component.border === owner.installed) installBorder(owner.baseline, null)
    }

    private fun checkTarget(isCurrent: () -> Boolean) {
        checkEdt()
        check(isAttached && isCurrent()) { "Glow focus listener is unavailable" }
    }

    private fun checkBorder(
        expected: Border?,
        isFocused: Boolean,
    ) {
        check(component.border === expected) { "Glow focus border changed" }
        check(component.hasFocus() == isFocused) { "Focus changed during Glow apply" }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow focus checkpoints must run on EDT" }
    }
}
