package dev.ayuislands.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.ui.ComponentTreeRefreshedListener
import dev.ayuislands.ui.ComponentTreeRefreshedTopic
import java.awt.Dimension
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Per-project service that manages editor scrollbar visibility.
 *
 * Instead of setting the scroll policy to NEVER (which disables scrolling entirely),
 * we override the scrollbar's [JScrollBar.getPreferredSize] to return zero dimensions.
 * This hides the scrollbar visually while preserving mouse wheel, trackpad, and
 * keyboard scrolling.
 */
@Service(Service.Level.PROJECT)
class EditorScrollbarManager(
    private val project: Project,
) : Disposable {
    /** Scroll panes whose scrollbars have been patched, so we can restore them. */
    private val patchedScrollPanes = WeakHashMap<JScrollPane, PatchedState>()
    private var held: Checkpoint? = null
    private var disposed = false
    private val isAlive: Boolean get() = !disposed && !project.isDisposed

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor as? EditorEx ?: return
                    applyNewEditor(editor)
                }
            },
            this,
        )

        // Self-heal after IJSwingUtilities.updateComponentTreeUI (startup, focus swap,
        // LAF change) — that walk calls updateUI() on every JScrollBar descendant and
        // resets the preferredSize=0 trick this manager uses to hide scrollbars.
        //
        project.messageBus
            .connect(this)
            .subscribe(
                ComponentTreeRefreshedTopic.TOPIC,
                ComponentTreeRefreshedListener { refresh() },
            )
    }

    private fun refresh() {
        if (!isAlive) return
        val pending = held
        if (pending != null) {
            pending.deferRefresh()
            return
        }
        apply()
    }

    private fun applyNewEditor(editor: EditorEx) {
        if (!isAlive || editor.isDisposed || editor.project != project) return
        val pending = held
        if (pending != null) {
            pending.defer(editor)
        } else if (LicenseChecker.isLicensedOrGrace()) {
            applyToEditor(editor)
        }
    }

    fun apply() {
        if (held != null || !isAlive) return
        val licensed = LicenseChecker.isLicensedOrGrace()
        for (editor in editors()) {
            if (licensed) {
                applyToEditor(editor)
            } else {
                restoreEditor(editor.scrollPane)
            }
        }
    }

    private fun editors(): List<EditorEx> =
        EditorFactory.getInstance().allEditors.filterIsInstance<EditorEx>().filter {
            it.project == project && !it.isDisposed
        }

    private fun applyToEditor(editor: EditorEx) {
        val scrollPane = editor.scrollPane
        val state = AyuIslandsSettings.getInstance().state
        val hideVertical = state.hideEditorVScrollbar
        val hideHorizontal = state.hideEditorHScrollbar

        if (!hideVertical && !hideHorizontal) {
            restoreEditor(scrollPane)
            return
        }

        val patched = patchedScrollPanes.getOrPut(scrollPane) { PatchedState() }
        patched.vertical = applyAxis(scrollPane.verticalScrollBar, patched.vertical, hideVertical)
        patched.horizontal = applyAxis(scrollPane.horizontalScrollBar, patched.horizontal, hideHorizontal)
        if (patched.isEmpty()) patchedScrollPanes.remove(scrollPane)
        scrollPane.revalidate()
    }

    private fun applyAxis(
        bar: JScrollBar,
        previous: Patch?,
        hide: Boolean,
    ): Patch? {
        if (!hide) return restoreScrollBar(previous)
        if (previous?.target() !== bar) {
            restoreScrollBar(previous)?.let { return it }
        }
        return hideScrollBar(bar, previous)
    }

    private fun hideScrollBar(
        bar: JScrollBar,
        previous: Patch?,
    ): Patch? {
        var attempted = previous
        try {
            val owned = previous?.owns(bar) == true
            if (!owned && bar.getClientProperty(ORIGINAL_PREFERRED_SIZE_KEY) != null) return null
            val explicit = bar.isPreferredSizeSet
            val current = bar.preferredSize
            if (owned && explicit && current == ZERO_SIZE) return previous

            val patch = Patch(WeakReference(bar), Dimension(current), explicit)
            // Swing can notify listeners after storing a value, even when the setter throws.
            attempted = patch
            tryScrollbarWrite("baseline") { bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, patch.baseline) }
            if (patch.owns(bar) && (!explicit || current != ZERO_SIZE)) {
                bar.preferredSize = Dimension(ZERO_SIZE)
                bar.revalidate()
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to hide editor scrollbar", exception)
        }
        return attempted?.takeIf { it.owns(bar) } ?: previous?.takeIf { it.owns(bar) }
    }

    private fun restoreScrollBar(patch: Patch?): Patch? {
        val bar = patch?.target() ?: return null
        if (!patch.owns(bar)) return null
        try {
            if (hasHiddenSize(bar)) {
                val restored = if (patch.isExplicit) Dimension(patch.baseline) else null
                val wrote = tryScrollbarWrite("preferred size") { bar.preferredSize = restored }
                if (!wrote && hasHiddenSize(bar)) return patch.takeIf { it.owns(bar) }
            }
            if (patch.owns(bar)) {
                tryScrollbarWrite("baseline") { bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, null) }
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to restore editor scrollbar", exception)
        }
        return patch.takeIf { it.owns(bar) }
    }

    private fun hasHiddenSize(bar: JScrollBar): Boolean = bar.isPreferredSizeSet && bar.preferredSize == ZERO_SIZE

    private inline fun tryScrollbarWrite(
        field: String,
        write: () -> Unit,
    ): Boolean =
        try {
            write()
            true
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to update editor scrollbar $field", exception)
            false
        }

    private fun restoreEditor(scrollPane: JScrollPane) {
        val patched = patchedScrollPanes[scrollPane] ?: return
        patched.vertical = restoreScrollBar(patched.vertical)
        patched.horizontal = restoreScrollBar(patched.horizontal)
        if (patched.isEmpty()) patchedScrollPanes.remove(scrollPane)
    }

    /** Observes the selected axes without taking ownership or performing writes. */
    internal fun capture(
        hideVertical: Boolean?,
        hideHorizontal: Boolean?,
    ): Checkpoint {
        checkEdt()
        check(isAlive && held == null)
        return Checkpoint(editors().distinctBy { it.scrollPane }, hideVertical, hideHorizontal)
    }

    internal inner class Checkpoint internal constructor(
        captured: List<EditorEx>,
        hideVertical: Boolean?,
        hideHorizontal: Boolean?,
    ) : AccentCheckpoint {
        private val target = UiDefaultsTarget()
        private val capturedPanes = captured.mapTo(hashSetOf()) { it.scrollPane }
        private val changes =
            captured.flatMap { editor ->
                listOfNotNull(
                    hideVertical?.let { AxisWrite(editor, true, it, target) },
                    hideHorizontal?.let { AxisWrite(editor, false, it, target) },
                ).filter(AxisWrite::hasEffect)
            }
        private val deferred = linkedSetOf<EditorEx>()
        private var phase = Phase.PREPARED
        private var refreshPending = false

        val records: List<EditorScrollbarRecord>
            get() = changes.map(AxisWrite::record)

        override val hasPending: Boolean
            get() =
                when (phase) {
                    Phase.APPLYING, Phase.APPLIED, Phase.RESTORING -> true
                    Phase.PREPARED, Phase.RESTORED, Phase.COMMITTED, Phase.DONE -> false
                }

        fun apply(): Boolean {
            checkEdt()
            check(phase == Phase.PREPARED)
            if (!canApply()) {
                changes.forEach(AxisWrite::relinquish)
                phase = Phase.DONE
                return false
            }
            held = this
            phase = Phase.APPLYING
            changes.forEach(AxisWrite::apply)
            phase = Phase.APPLIED
            return true
        }

        private fun canApply(): Boolean =
            canStart() &&
                editors().none { it.scrollPane !in capturedPanes } &&
                changes.all(AxisWrite::matchesBefore)

        private fun canStart(): Boolean = isAlive && held == null && target.isCurrent()

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            checkEdt()
            check(phase != Phase.COMMITTED)
            if (phase == Phase.DONE || phase == Phase.RESTORED) return
            phase = Phase.RESTORING
            var failure: Throwable? = null
            for (change in changes.asReversed()) {
                try {
                    check(blockedBy.none { it is Checkpoint && it.overlaps(change.bar) }) {
                        "Editor scrollbar restoration is blocked by a newer write"
                    }
                    change.restore()
                } catch (exception: RuntimeException) {
                    failure = combineUiFailures(failure, exception)
                }
            }
            failure?.let { throw it }
            phase = Phase.RESTORED
            releaseResources()
        }

        override fun commit() {
            checkEdt()
            if (phase == Phase.DONE || phase == Phase.COMMITTED) return
            check(phase == Phase.APPLIED)
            phase = Phase.COMMITTED
        }

        override fun releaseResources() {
            checkEdt()
            if (phase == Phase.DONE) return
            check(phase == Phase.COMMITTED || phase == Phase.RESTORED)
            val shouldRefresh = refreshPending
            val editors = deferred.filter { editor -> changes.none { it.pane === editor.scrollPane } }
            deferred.clear()
            refreshPending = false
            if (held === this) held = null
            phase = Phase.DONE
            if (shouldRefresh) {
                AccentApplyPlanRunner.post {
                    refresh()
                    editors.forEach(::applyNewEditor)
                }
            } else {
                editors.forEach { editor -> AccentApplyPlanRunner.post { applyNewEditor(editor) } }
            }
        }

        override fun retain() {
            checkEdt()
            if (!hasPending) return
            check(held == null || held === this)
            held = this
        }

        internal fun defer(editor: EditorEx) {
            deferred += editor
        }

        internal fun deferRefresh() {
            refreshPending = true
        }

        internal fun close() {
            deferred.clear()
            refreshPending = false
            if (phase == Phase.COMMITTED) releaseResources() else restore()
        }

        private fun overlaps(bar: JScrollBar): Boolean = changes.any { it.bar === bar && it.hasPending }
    }

    private inner class AxisWrite(
        private val editor: EditorEx,
        private val vertical: Boolean,
        hide: Boolean,
        private val target: UiDefaultsTarget,
    ) {
        val pane: JScrollPane = editor.scrollPane
        val bar: JScrollBar = scrollbar(pane, vertical)
        private val before = EditorScrollbarSnapshot.read(bar, ORIGINAL_PREFERRED_SIZE_KEY)
        private val previous = patch(pane, vertical)
        private val owned = previous?.takeIf { it.owns(bar) }
        private val next =
            if (hide) {
                owned?.takeIf { before.preferredSize == HIDDEN_SIZE }
                    ?: Patch(WeakReference(bar), Dimension(bar.preferredSize), bar.isPreferredSizeSet)
            } else {
                null
            }
        private val planned = plan(next)
        private var expected = before
        private var phase = EditorScrollbarWrite.PREPARED
        private var sizePending = false
        private var baselinePending = false
        private var foreign = false

        val hasEffect: Boolean get() = !before.matches(planned)
        val hasPending: Boolean get() = phase == EditorScrollbarWrite.OWNED
        val record: EditorScrollbarRecord get() = EditorScrollbarRecord(bar, before, planned, expected, phase)

        private fun plan(proposal: Patch?): EditorScrollbarSnapshot =
            when {
                proposal != null && (before.baseline == null || owned != null) ->
                    EditorScrollbarSnapshot(HIDDEN_SIZE, proposal.baseline)
                proposal == null && owned != null ->
                    EditorScrollbarSnapshot(
                        if (before.preferredSize == HIDDEN_SIZE) {
                            if (owned.isExplicit) ScrollbarSize.of(owned.baseline) else null
                        } else {
                            before.preferredSize
                        },
                        null,
                    )
                else -> before
            }

        fun matchesBefore(): Boolean = isCurrent() && patch(pane, vertical) === previous && before.matches(observe())

        fun apply() {
            check(matchesBefore()) { "Editor scrollbar changed before application" }
            phase = EditorScrollbarWrite.OWNED
            setPatch(pane, vertical, next)
            if (!before.matchesBaseline(planned)) {
                baselinePending = true
                writeBaseline(planned)
            }
            if (before.preferredSize != planned.preferredSize) {
                sizePending = true
                writeSize(planned.preferredSize)
            }
            check(isCurrent() && planned.matches(observe())) { "Editor scrollbar write did not settle" }
        }

        fun restore() {
            if (phase == EditorScrollbarWrite.PREPARED) {
                phase = EditorScrollbarWrite.RESTORED
                return
            }
            if (!hasPending) return
            if (!isAttached()) {
                relinquish()
                return
            }
            val started = observe()
            if (started.preferredSize != expected.preferredSize && started.preferredSize != before.preferredSize) {
                foreign = true
            }
            if (!started.matchesBaseline(expected) && !started.matchesBaseline(before)) foreign = true
            if (sizePending) restoreSize()
            if (baselinePending) restoreBaseline()
            if (patch(pane, vertical) === next) {
                setPatch(pane, vertical, if (foreign) null else previous)
            } else {
                foreign = true
            }
            phase = if (foreign) EditorScrollbarWrite.RELINQUISHED else EditorScrollbarWrite.RESTORED
        }

        private fun restoreSize() {
            val current = observe().preferredSize
            when {
                current == before.preferredSize -> Unit
                current != expected.preferredSize -> foreign = true
                else -> writeSize(before.preferredSize, restoring = true)
            }
            sizePending = false
        }

        private fun restoreBaseline() {
            val current = observe()
            val destination =
                if (foreign && owned != null) before.copy(baseline = null, baselineSize = null) else before
            when {
                current.matchesBaseline(destination) -> Unit
                !current.matchesBaseline(expected) -> foreign = true
                else -> writeBaseline(destination, restoring = true)
            }
            baselinePending = false
        }

        fun relinquish() {
            if (hasPending && patch(pane, vertical) === next) setPatch(pane, vertical, null)
            phase = EditorScrollbarWrite.RELINQUISHED
        }

        private fun writeSize(
            value: ScrollbarSize?,
            restoring: Boolean = false,
        ) {
            check(canWrite(restoring) && observe().preferredSize == expected.preferredSize) {
                "Editor scrollbar size changed before write"
            }
            expected = expected.copy(preferredSize = value)
            bar.preferredSize = value?.toDimension()
            check(observe().preferredSize == value) { "Editor scrollbar size write did not settle" }
        }

        private fun writeBaseline(
            value: EditorScrollbarSnapshot,
            restoring: Boolean = false,
        ) {
            check(canWrite(restoring) && observe().matchesBaseline(expected)) {
                "Editor scrollbar baseline changed before write"
            }
            expected = expected.copy(baseline = value.baseline, baselineSize = value.baselineSize)
            bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, value.baseline)
            check(observe().matchesBaseline(value)) { "Editor scrollbar baseline write did not settle" }
        }

        private fun observe(): EditorScrollbarSnapshot = EditorScrollbarSnapshot.read(bar, ORIGINAL_PREFERRED_SIZE_KEY)

        private fun isCurrent(): Boolean = canWrite(restoring = false)

        private fun canWrite(restoring: Boolean): Boolean = isAttached() && (restoring || target.isCurrent())

        private fun isAttached(): Boolean =
            isAlive &&
                !editor.isDisposed &&
                editor.scrollPane === pane &&
                scrollbar(pane, vertical) === bar
    }

    private fun patch(
        pane: JScrollPane,
        vertical: Boolean,
    ): Patch? = patchedScrollPanes[pane]?.let { if (vertical) it.vertical else it.horizontal }

    private fun setPatch(
        pane: JScrollPane,
        vertical: Boolean,
        value: Patch?,
    ) {
        val tracked = patchedScrollPanes[pane] ?: if (value == null) return else PatchedState()
        if (vertical) tracked.vertical = value else tracked.horizontal = value
        if (tracked.isEmpty()) {
            patchedScrollPanes.remove(pane)
        } else {
            patchedScrollPanes[pane] = tracked
        }
    }

    override fun dispose() {
        held?.close()
        disposed = true
        patchedScrollPanes.keys.toList().forEach(::restoreEditor)
    }

    private class PatchedState(
        var vertical: Patch? = null,
        var horizontal: Patch? = null,
    ) {
        fun isEmpty(): Boolean = vertical == null && horizontal == null
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, COMMITTED, DONE }

    private class Patch(
        private val scrollbar: WeakReference<JScrollBar>,
        val baseline: Dimension,
        val isExplicit: Boolean,
    ) {
        // A strong reference to the bar would keep the weak map's parent pane alive.
        fun target(): JScrollBar? = scrollbar.get()

        fun owns(bar: JScrollBar): Boolean =
            target() === bar && bar.getClientProperty(ORIGINAL_PREFERRED_SIZE_KEY) === baseline
    }

    companion object {
        private val LOG = logger<EditorScrollbarManager>()
        private const val ORIGINAL_PREFERRED_SIZE_KEY = "ayuIslands.originalPreferredSize"
        private val ZERO_SIZE = Dimension(0, 0)
        private val HIDDEN_SIZE = ScrollbarSize.of(ZERO_SIZE)

        private fun checkEdt() {
            check(SwingUtilities.isEventDispatchThread()) { "Editor scrollbar checkpoints require EDT" }
        }

        private fun scrollbar(
            pane: JScrollPane,
            vertical: Boolean,
        ): JScrollBar = if (vertical) pane.verticalScrollBar else pane.horizontalScrollBar

        fun getInstance(project: Project): EditorScrollbarManager =
            project.getService(
                EditorScrollbarManager::class.java,
            )
    }
}
