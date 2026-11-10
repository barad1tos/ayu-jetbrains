package dev.ayuislands.glow

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
    val component: JComponent,
    glowColor: Color,
    glowStyle: GlowStyle,
    baseIntensity: Int,
    private val callbacksAllowed: () -> Boolean,
    private val ownsComponent: () -> Boolean = { true },
    private val onDetached: (GlowFocusListener) -> Unit,
) : FocusListener,
    HierarchyListener {
    private var attachment = Attachment.NEW
    private val isAttached: Boolean
        get() = attachment == Attachment.ATTACHED
    private val canHandleFocus: Boolean
        get() = isAttached && ownsComponent() && callbacksAllowed()
    private var borderReceipt: BorderReceipt? = null
    private var appearance = GlowFocusAppearance(glowColor, glowStyle, baseIntensity, enabled = true)

    val currentAppearance: GlowFocusAppearance
        get() = appearance

    val borderOwnership: GlowFocusOwnership?
        get() = borderReceipt?.let { GlowFocusOwnership(it.installed, it.original) }

    fun captureCallbacks(isCurrent: () -> Boolean): CallbackHold = CallbackHold(isCurrent)

    fun prepareInitialBorder(): InitialBorder {
        checkEdt()
        check(attachment == Attachment.NEW && !callbacksAllowed()) {
            "Glow focus preparation requires a held new listener"
        }
        return InitialBorder()
    }

    inner class InitialBorder {
        private val before = component.border
        private val wasFocused = component.hasFocus()
        private val preparedReceipt =
            if (wasFocused && appearance.enabled) {
                BorderReceipt(before, GlowFocusBorder(before, appearance.color, appearance.style, appearance.intensity))
            } else {
                null
            }
        private var state = GlowFieldState.PREPARED
        val record: GlowInitialBorderRecord
            get() = GlowInitialBorderRecord(before, preparedReceipt?.installed ?: before, wasFocused, state)

        fun checkPrepared() {
            checkEdt()
            check(state == GlowFieldState.PREPARED && !callbacksAllowed()) {
                "Glow initial border is no longer prepared"
            }
            check(component.border === before) { "Glow focus border changed before attachment" }
            check(component.hasFocus() == wasFocused) { "Focus changed before attachment" }
        }

        fun apply() {
            checkPrepared()
            checkAttachment(Attachment.ATTACHED)
            if (preparedReceipt == null) {
                state = GlowFieldState.RESTORED
                return
            }
            state = GlowFieldState.WRITING
            borderReceipt = preparedReceipt
            component.border = preparedReceipt.installed
            component.repaint()
            checkAttachment(Attachment.ATTACHED)
            check(component.border === preparedReceipt.installed) { "Glow initial border write did not settle" }
            check(component.hasFocus() == wasFocused) { "Focus changed during attachment" }
        }

        fun settle() {
            checkEdt()
            if (state == GlowFieldState.RESTORED || state == GlowFieldState.FOREIGN) return
            state =
                when {
                    state == GlowFieldState.PREPARED || component.border === before -> GlowFieldState.RESTORED
                    component.border === preparedReceipt?.installed -> GlowFieldState.WRITING
                    else -> GlowFieldState.FOREIGN
                }
            if (state != GlowFieldState.WRITING && borderReceipt === preparedReceipt) borderReceipt = null
        }
    }

    inner class CallbackHold(
        private val isCurrent: () -> Boolean,
    ) {
        val listener: GlowFocusListener
            get() = this@GlowFocusListener

        val beforeBorder: Border? = component.border
        private val beforeReceipt = borderReceipt
        private val wasOwned = beforeReceipt == null || beforeBorder === beforeReceipt.installed

        fun reconcile(canWriteBorder: Boolean = true) {
            checkEdt()
            if (!canWriteBorder || !isAttached || !isCurrent()) return
            if (!component.hasFocus() || !appearance.enabled) {
                restoreBorder()
                return
            }
            val current = component.border
            val isOwned = borderReceipt?.let { current === it.installed } == true
            val isUnchanged =
                wasOwned &&
                    (current === beforeBorder || (beforeReceipt != null && current === beforeReceipt.original))
            if (isOwned || isUnchanged) applyBorder()
        }
    }

    fun capture(
        transform: (GlowFocusAppearance) -> GlowFocusAppearance,
        isCurrent: () -> Boolean,
    ): Checkpoint? {
        checkEdt()
        check(isAttached && isCurrent()) { "Glow focus listener is unavailable" }
        val planned = transform(appearance)
        if (GlowFocusFields.matches(appearance, planned)) return null
        return Checkpoint(planned, isCurrent)
    }

    fun captureShared(
        planned: GlowFocusAppearance,
        borderPlan: GlowFocusBorderPlan,
        isCurrent: () -> Boolean,
    ): Checkpoint? {
        checkEdt()
        check(isAttached && isCurrent()) { "Glow focus listener is unavailable" }
        check(component.border === borderPlan.observed) { "Shared Glow border changed before capture" }
        if (borderPlan.writer !== this && GlowFocusFields.matches(appearance, planned)) return null
        return Checkpoint(planned, isCurrent, borderPlan)
    }

    inner class Checkpoint(
        val planned: GlowFocusAppearance,
        private val isCurrent: () -> Boolean,
        private val borderPlan: GlowFocusBorderPlan? = null,
    ) {
        val listener: GlowFocusListener
            get() = this@GlowFocusListener

        init {
            checkTarget()
        }

        val before: GlowFocusAppearance = appearance
        private val beforeReceipt = borderReceipt
        private val beforeBorder = component.border
        private val wasFocused = component.hasFocus()
        private val ownsBorder =
            if (borderPlan == null) {
                beforeReceipt == null || beforeBorder === beforeReceipt.installed
            } else {
                borderPlan.writer === this@GlowFocusListener && borderPlan.canWrite
            }
        private val baseline =
            when {
                borderPlan != null -> borderPlan.baseline
                ownsBorder && beforeReceipt != null -> beforeReceipt.original
                else -> beforeBorder
            }
        private val plannedReceipt =
            if (ownsBorder && wasFocused && planned.enabled) {
                BorderReceipt(baseline, GlowFocusBorder(baseline, planned.color, planned.style, planned.intensity))
            } else {
                null
            }
        private val plannedBorder = if (ownsBorder) plannedReceipt?.installed ?: baseline else beforeBorder
        private var phase = GlowCheckpointPhase.PREPARED
        private val fields =
            mutableMapOf(GlowFocusField.APPEARANCE to GlowFieldState.PREPARED).apply {
                if (plannedBorder !== beforeBorder) put(GlowFocusField.BORDER, GlowFieldState.PREPARED)
            }
        private var restoration: GlowBorderRestore? = null
        private var lastBorderAttempt: GlowBorderRestore? = null

        val record: GlowFocusRecord
            get() =
                GlowFocusRecord(
                    before,
                    planned,
                    beforeBorder,
                    plannedBorder,
                    baseline,
                    phase,
                    java.util.Map.copyOf(fields),
                    restoration,
                )

        val hasPending: Boolean
            get() = phase != GlowCheckpointPhase.ACCEPTED && GlowFieldState.WRITING in fields.values

        fun apply() {
            checkTarget()
            check(phase == GlowCheckpointPhase.PREPARED) { "Glow focus change was already started" }
            check(appearance === before && borderReceipt === beforeReceipt) {
                "Glow focus target changed before apply"
            }
            if (ownsBorder) {
                check(component.border === beforeBorder) { "Glow focus border changed before apply" }
                check(component.hasFocus() == wasFocused) { "Focus changed before apply" }
            }
            phase = GlowCheckpointPhase.APPLYING
            fields[GlowFocusField.APPEARANCE] = GlowFieldState.WRITING
            appearance = planned
            if (plannedBorder !== beforeBorder) {
                fields[GlowFocusField.BORDER] = GlowFieldState.WRITING
                borderReceipt = plannedReceipt
                component.border = plannedBorder
                component.repaint()
            }
            checkTarget()
            check(appearance === planned && (!ownsBorder || component.border === plannedBorder)) {
                "Glow focus write did not settle"
            }
            phase = GlowCheckpointPhase.APPLIED
        }

        fun prepareRestore() {
            checkEdt()
            check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow focus changes cannot be restored" }
            if (fields[GlowFocusField.BORDER] != GlowFieldState.WRITING) return
            checkTarget()
            restoration = prepareFocusRestore(record, component.border, component.hasFocus(), lastBorderAttempt)
        }

        fun restore(blockedBy: Set<GlowFocusField> = emptySet()) {
            checkEdt()
            check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow focus changes cannot be restored" }
            if (phase == GlowCheckpointPhase.RESTORED) return
            phase = GlowCheckpointPhase.RESTORING
            val failures = mutableListOf<RuntimeException>()
            for (field in GlowFocusField.entries.asReversed()) {
                try {
                    restoreField(field, blockedBy)
                } catch (exception: RuntimeException) {
                    failures.add(exception)
                }
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                throw first
            }
            phase = GlowCheckpointPhase.RESTORED
        }

        private fun restoreField(
            field: GlowFocusField,
            blockedBy: Set<GlowFocusField>,
        ) {
            when (fields[field]) {
                null, GlowFieldState.RESTORED, GlowFieldState.FOREIGN -> return
                GlowFieldState.PREPARED -> {
                    fields[field] = GlowFieldState.RESTORED
                    return
                }
                GlowFieldState.WRITING -> Unit
            }
            check(field !in blockedBy) { "A newer Glow focus receipt owns $field" }
            checkTarget()
            when (field) {
                GlowFocusField.BORDER -> restoreCheckpointBorder()
                GlowFocusField.APPEARANCE -> {
                    if (appearance === planned) appearance = before
                    fields[field] = if (appearance === before) GlowFieldState.RESTORED else GlowFieldState.FOREIGN
                }
            }
        }

        fun accept() {
            checkEdt()
            if (phase == GlowCheckpointPhase.ACCEPTED) return
            check(phase == GlowCheckpointPhase.APPLIED) { "Glow focus apply has not completed" }
            if (!planned.enabled && appearance === planned) detach(shouldRestoreBorder = borderPlan == null)
            phase = GlowCheckpointPhase.ACCEPTED
        }

        private fun restoreCheckpointBorder() {
            val prepared = checkNotNull(restoration) { "Glow focus restoration must be prepared before writing" }
            val current = component.border
            val completedAttempt = lastBorderAttempt === prepared && current === prepared.border
            val isForeign =
                prepared.outcome == GlowFieldState.FOREIGN || (current !== prepared.observed && !completedAttempt)
            if (!isForeign) {
                check(component.hasFocus() == prepared.isFocused) { "Focus changed after restoration preparation" }
                if (prepared.outcome == GlowFieldState.WRITING && !completedAttempt) {
                    lastBorderAttempt = prepared
                    component.border = prepared.border
                    component.repaint()
                    checkTarget()
                    check(component.border !== current) { "Glow focus restoration did not settle" }
                    check(component.hasFocus() == prepared.isFocused) { "Focus changed during restoration" }
                }
            }
            if (borderReceipt === plannedReceipt) {
                borderReceipt =
                    beforeReceipt?.takeIf {
                        component.border === it.installed ||
                            (borderPlan != null && !isForeign && component.border === beforeBorder)
                    }
            }
            fields[GlowFocusField.BORDER] =
                if (!isForeign && (component.border === prepared.border || component.border === beforeBorder)) {
                    GlowFieldState.RESTORED
                } else {
                    GlowFieldState.FOREIGN
                }
        }

        private fun checkTarget() {
            checkEdt()
            check(isAttached && isCurrent()) { "Glow focus listener is unavailable" }
        }
    }

    fun attach() {
        if (isAttached) {
            checkAttachment(Attachment.ATTACHED)
            return
        }
        checkAttachment(Attachment.NEW)
        attachment = Attachment.ATTACHING
        component.addFocusListener(this)
        checkAttachment(Attachment.ATTACHING)
        component.addHierarchyListener(this)
        checkAttachment(Attachment.ATTACHING)
        check(component.focusListeners.any { it === this } && component.hierarchyListeners.any { it === this }) {
            "Glow focus registration did not settle"
        }
        attachment = Attachment.ATTACHED
        if (callbacksAllowed() && component.hasFocus()) applyBorder()
        checkAttachment(Attachment.ATTACHED)
    }

    fun detach(shouldRestoreBorder: Boolean = true) {
        if (attachment == Attachment.DETACHED && borderReceipt == null) return
        attachment = Attachment.DETACHING
        if (component.focusListeners.any { it === this }) component.removeFocusListener(this)
        if (component.hierarchyListeners.any { it === this }) component.removeHierarchyListener(this)
        check(component.focusListeners.none { it === this } && component.hierarchyListeners.none { it === this }) {
            "Glow focus cleanup did not settle"
        }
        if (shouldRestoreBorder) restoreBorder() else borderReceipt = null
        onDetached(this)
        attachment = Attachment.DETACHED
    }

    override fun focusGained(event: FocusEvent) {
        if (canHandleFocus && event.component === component) applyBorder()
    }

    override fun focusLost(event: FocusEvent) {
        if (canHandleFocus && event.component === component) restoreBorder()
    }

    override fun hierarchyChanged(event: HierarchyEvent) {
        val displayabilityChanged =
            (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
        if (displayabilityChanged && !component.isDisplayable) detach()
    }

    private fun applyBorder() {
        if (!isAttached || !ownsComponent() || !appearance.enabled) return
        val current = component.border
        val receipt = borderReceipt
        if (receipt != null && current === receipt.installed) return
        val border = GlowFocusBorder(current, appearance.color, appearance.style, appearance.intensity)
        borderReceipt = BorderReceipt(current, border)
        component.border = border
        component.repaint()
    }

    private fun restoreBorder() {
        val receipt = borderReceipt ?: return
        if (component.border === receipt.installed) {
            component.border = receipt.original
            component.repaint()
        }
        if (borderReceipt === receipt) borderReceipt = null
    }

    private class BorderReceipt(
        val original: Border?,
        val installed: GlowFocusBorder,
    )

    private enum class Attachment { NEW, ATTACHING, ATTACHED, DETACHING, DETACHED }

    private fun checkAttachment(expected: Attachment) {
        check(attachment == expected && ownsComponent()) { "Glow focus listener is unavailable" }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow focus checkpoints must run on EDT" }
    }
}
