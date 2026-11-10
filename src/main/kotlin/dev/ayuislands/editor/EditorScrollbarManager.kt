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
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.ui.ComponentTreeRefreshedListener
import dev.ayuislands.ui.ComponentTreeRefreshedTopic
import java.awt.Dimension
import java.util.WeakHashMap
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/** Hides editor scrollbars through preferred size while preserving wheel and keyboard scrolling. */
@Service(Service.Level.PROJECT)
class EditorScrollbarManager(
    private val project: Project,
) : Disposable {
    private val patchedScrollPanes = WeakHashMap<JScrollPane, PatchedState>()
    private var held: Checkpoint? = null
    private var isDisposed = false

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
        project.messageBus.connect(this).subscribe(
            ComponentTreeRefreshedTopic.TOPIC,
            ComponentTreeRefreshedListener {
                if (held == null && !isDisposed) {
                    resetOriginalSizeCache()
                    apply()
                }
            },
        )
    }

    @org.jetbrains.annotations.TestOnly
    internal fun resetOriginalSizeCacheForTest() = resetOriginalSizeCache()

    private fun applyNewEditor(editor: EditorEx) {
        if (!isAlive() || editor.isDisposed || editor.project != project) return
        held?.let {
            it.defer(editor)
            return
        }
        if (LicenseChecker.isLicensedOrGrace()) applyToEditor(editor)
    }

    private fun isAlive(): Boolean = !isDisposed && !project.isDisposed

    private fun resetOriginalSizeCache() {
        if (held != null) return
        for (pane in patchedScrollPanes.keys.toList()) {
            try {
                for (bar in listOfNotNull(pane.verticalScrollBar, pane.horizontalScrollBar)) {
                    // A notification without an actual Swing reset must not turn zero into the baseline.
                    if (bar.preferredSize != ZERO_SIZE) bar.putClientProperty(ORIGINAL_SIZE_KEY, null)
                }
            } catch (exception: RuntimeException) {
                LOG.warn("Failed to clear scrollbar original-size cache on refresh", exception)
            }
        }
    }

    fun apply() {
        if (held != null || isDisposed) return
        val licensed = LicenseChecker.isLicensedOrGrace()
        for (editor in editors()) {
            if (licensed) applyToEditor(editor) else restoreEditor(editor.scrollPane)
        }
    }

    private fun editors(): List<EditorEx> =
        EditorFactory.getInstance().allEditors.mapNotNull { editor ->
            (editor as? EditorEx)?.takeIf { it.project == project && !it.isDisposed }
        }

    private fun applyToEditor(editor: EditorEx) {
        val pane = editor.scrollPane
        val settings = AyuIslandsSettings.getInstance().state ?: return
        changeAxis(pane, true, settings.hideEditorVScrollbar)
        changeAxis(pane, false, settings.hideEditorHScrollbar)
        pane.revalidate()
    }

    private fun changeAxis(
        pane: JScrollPane,
        vertical: Boolean,
        hide: Boolean,
    ) {
        val bar = scrollbar(pane, vertical)
        val previous = patch(pane, vertical)
        if (hide) {
            val baseline = bar.getClientProperty(ORIGINAL_SIZE_KEY)
            val next =
                if (previous?.bar === bar && baseline === previous.baseline && baseline != null) {
                    previous
                } else {
                    Patch(bar, bar.isPreferredSizeSet, baseline as? Dimension ?: bar.preferredSize?.let(::Dimension))
                }
            setPatch(pane, vertical, next)
            if (baseline == null) bar.putClientProperty(ORIGINAL_SIZE_KEY, next.baseline)
            bar.preferredSize = Dimension(ZERO_SIZE)
            bar.revalidate()
        } else if (previous != null) {
            restoreBar(bar, previous)
            setPatch(pane, vertical, null)
        }
    }

    private fun restoreBar(
        bar: JScrollBar,
        previous: Patch,
    ) {
        if (bar !== previous.bar) return
        if (bar.preferredSize == ZERO_SIZE) {
            bar.preferredSize = if (previous.isExplicit) previous.baseline?.let(::Dimension) else null
        }
        if (bar.getClientProperty(ORIGINAL_SIZE_KEY) === previous.baseline) {
            bar.putClientProperty(ORIGINAL_SIZE_KEY, null)
        }
    }

    private fun restoreEditor(pane: JScrollPane) {
        changeAxis(pane, vertical = true, hide = false)
        changeAxis(pane, vertical = false, hide = false)
    }

    /** Captures selected candidate axes without writes or holds; null leaves an axis untouched. */
    internal fun capture(
        hideVertical: Boolean?,
        hideHorizontal: Boolean?,
    ): Checkpoint {
        checkEdt()
        check(isAlive() && held == null) { "Editor scrollbar manager is unavailable" }
        return Checkpoint(editors(), hideVertical, hideHorizontal)
    }

    internal inner class Checkpoint internal constructor(
        capturedEditors: List<EditorEx>,
        hideVertical: Boolean?,
        hideHorizontal: Boolean?,
    ) : AccentWriteCheckpoint {
        private val target = UiDefaultsTarget()
        private val changes =
            capturedEditors.distinctBy { it.scrollPane }.flatMap { editor ->
                listOfNotNull(
                    hideVertical?.let { AxisChange(editor, true, it, target) },
                    hideHorizontal?.let { AxisChange(editor, false, it, target) },
                )
            }
        private val deferred = linkedSetOf<EditorEx>()
        private var phase = Phase.PREPARED

        val records: List<EditorScrollbarRecord>
            get() = changes.map { it.record }

        override val state: AccentWriteState
            get() =
                when {
                    changes.any { it.state == AccentWriteState.RELINQUISHED } -> AccentWriteState.RELINQUISHED
                    phase == Phase.PREPARED -> AccentWriteState.PREPARED
                    phase == Phase.DONE -> AccentWriteState.RESTORED
                    else -> AccentWriteState.OWNED
                }

        override val hasPending: Boolean
            get() = phase != Phase.DONE

        override fun apply(): Boolean {
            checkEdt()
            check(phase == Phase.PREPARED) { "Editor scrollbar change was already started" }
            if (!canApply()) {
                changes.forEach { it.relinquish() }
                phase = Phase.DONE
                return false
            }
            phase = Phase.APPLYING
            held = this
            for (change in changes) change.apply()
            phase = Phase.APPLIED
            return true
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            checkEdt()
            check(phase != Phase.COMMITTED) { "Accepted editor scrollbars cannot be restored" }
            if (phase == Phase.DONE) return
            phase = Phase.RESTORING
            var failure: Throwable? = null
            for (change in changes.asReversed()) {
                try {
                    check(blockedBy.none { it is Checkpoint && it.hasPending && it.overlaps(change) }) {
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
            check(phase == Phase.APPLIED) { "Editor scrollbar application has not completed" }
            phase = Phase.COMMITTED
        }

        override fun releaseResources() {
            checkEdt()
            if (phase == Phase.DONE) return
            check(phase == Phase.COMMITTED || phase == Phase.RESTORED) { "Editor scrollbars are not settled" }
            for (editor in deferred.toList()) {
                if (isAlive() &&
                    !editor.isDisposed &&
                    changes.none { it.pane === editor.scrollPane }
                ) {
                    AccentApplyPlanRunner.post { applyNewEditor(editor) }
                }
                deferred.remove(editor)
            }
            if (held === this) held = null
            phase = Phase.DONE
        }

        override fun retain() {
            checkEdt()
            if (phase != Phase.PREPARED && phase != Phase.DONE) {
                check(held == null || held === this) { "Another editor scrollbar change owns callbacks" }
                held = this
            }
        }

        internal fun defer(editor: EditorEx) {
            deferred += editor
        }

        internal fun close() {
            deferred.clear()
            if (phase == Phase.COMMITTED) releaseResources() else restore()
        }

        private fun overlaps(change: AxisChange): Boolean =
            changes.any { it.bar === change.bar && it.state == AccentWriteState.OWNED }

        private fun canApply(): Boolean =
            held == null && isAlive() && target.isCurrent() && changes.all { it.matchesBefore() }
    }

    private inner class AxisChange(
        private val editor: EditorEx,
        private val vertical: Boolean,
        hide: Boolean,
        private val target: UiDefaultsTarget,
    ) {
        val pane: JScrollPane = editor.scrollPane
        val bar: JScrollBar = scrollbar(pane, vertical)
        private val beforePatch = patch(pane, vertical)
        private val before = observe(bar)
        private val nextPatch =
            if (hide) {
                beforePatch?.takeIf {
                    it.bar === bar && before.originalSize === it.baseline && before.preferredSize == ScrollbarSize(0, 0)
                }
                    ?: Patch(bar, bar.isPreferredSizeSet, bar.preferredSize?.let(::Dimension))
            } else {
                null
            }
        private val planned =
            when {
                hide -> {
                    val baseline = before.originalSize ?: nextPatch?.baseline
                    EditorScrollbarSnapshot(ScrollbarSize(0, 0), baseline, (baseline as? Dimension)?.let(::size))
                }

                beforePatch?.bar === bar -> {
                    EditorScrollbarSnapshot(
                        if (before.preferredSize == ScrollbarSize(0, 0)) {
                            if (beforePatch.isExplicit) beforePatch.baseline?.let(::size) else null
                        } else {
                            before.preferredSize
                        },
                        before.originalSize.takeUnless { it === beforePatch.baseline },
                        before.originalDimensions.takeUnless { before.originalSize === beforePatch.baseline },
                    )
                }

                else -> {
                    before
                }
            }
        private var expected = before
        private var sizePending = false
        private var baselinePending = false
        private var hasForeign = false
        var state = AccentWriteState.PREPARED
            private set
        val record: EditorScrollbarRecord
            get() = EditorScrollbarRecord(pane, bar, vertical, before, planned, expected, state)

        fun matchesBefore(): Boolean =
            isCurrent() && patch(pane, vertical) === beforePatch && matches(observe(bar), before)

        fun apply() {
            check(matchesBefore()) { "Editor scrollbar changed during apply" }
            state = AccentWriteState.OWNED
            setPatch(pane, vertical, nextPatch)
            if (!sameBaseline(before, planned)) {
                baselinePending = true
                writeBaseline(planned)
            }
            if (before.preferredSize != planned.preferredSize) {
                sizePending = true
                writeSize(planned.preferredSize)
            }
            bar.revalidate()
            check(isCurrent() && matches(observe(bar), planned)) { "Editor scrollbar write did not settle" }
        }

        fun restore() {
            if (state == AccentWriteState.PREPARED) {
                state = AccentWriteState.RESTORED
                return
            }
            if (state != AccentWriteState.OWNED) return
            if (!isCurrent()) {
                relinquish()
                return
            }
            val observed = observe(bar)
            if (observed.preferredSize != expected.preferredSize && observed.preferredSize != before.preferredSize) {
                hasForeign = true
            }
            if (!sameBaseline(observed, expected) && !sameBaseline(observed, before)) hasForeign = true
            var failure: Throwable? = null
            try {
                restoreSize()
            } catch (exception: RuntimeException) {
                failure = exception
            }
            try {
                restoreBaseline()
            } catch (exception: RuntimeException) {
                failure = combineUiFailures(failure, exception)
            }
            failure?.let { throw it }
            if (patch(pane, vertical) === nextPatch) {
                setPatch(pane, vertical, if (hasForeign) null else beforePatch)
            } else {
                hasForeign = true
            }
            state = if (hasForeign) AccentWriteState.RELINQUISHED else AccentWriteState.RESTORED
        }

        private fun restoreSize() {
            if (!sizePending) return
            val current = observe(bar).preferredSize
            when {
                current == before.preferredSize -> Unit
                current != expected.preferredSize -> hasForeign = true
                else -> writeSize(before.preferredSize)
            }
            sizePending = false
        }

        private fun restoreBaseline() {
            if (!baselinePending) return
            val current = observe(bar)
            // A released baseline must not be resurrected beneath a foreign preferred size.
            val restoration =
                if (hasForeign &&
                    beforePatch != null &&
                    before.originalSize === beforePatch.baseline
                ) {
                    planned
                } else {
                    before
                }
            when {
                sameBaseline(current, restoration) -> Unit
                !sameBaseline(current, expected) -> hasForeign = true
                else -> writeBaseline(restoration)
            }
            baselinePending = false
        }

        fun relinquish() {
            if (state == AccentWriteState.OWNED && patch(pane, vertical) === nextPatch) setPatch(pane, vertical, null)
            state = AccentWriteState.RELINQUISHED
        }

        private fun isCurrent(): Boolean =
            !isDisposed &&
                !project.isDisposed &&
                !editor.isDisposed &&
                target.isCurrent() &&
                editor.scrollPane === pane &&
                scrollbar(pane, vertical) === bar

        private fun writeSize(value: ScrollbarSize?) {
            check(isCurrent() && observe(bar).preferredSize == expected.preferredSize) {
                "Editor scrollbar size changed before write"
            }
            expected = expected.copy(preferredSize = value)
            bar.preferredSize = value?.let { Dimension(it.width, it.height) }
            check(observe(bar).preferredSize == value) { "Editor scrollbar size did not settle" }
        }

        private fun writeBaseline(snapshot: EditorScrollbarSnapshot) {
            check(isCurrent() && sameBaseline(observe(bar), expected)) {
                "Editor scrollbar baseline changed before write"
            }
            expected =
                expected.copy(originalSize = snapshot.originalSize, originalDimensions = snapshot.originalDimensions)
            bar.putClientProperty(ORIGINAL_SIZE_KEY, snapshot.originalSize)
            check(sameBaseline(observe(bar), snapshot)) { "Editor scrollbar baseline did not settle" }
        }
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
        val existing =
            patchedScrollPanes[pane] ?: if (value == null) {
                return
            } else {
                PatchedState().also {
                    patchedScrollPanes[pane] = it
                }
            }
        if (vertical) existing.vertical = value else existing.horizontal = value
        if (existing.vertical == null && existing.horizontal == null) patchedScrollPanes.remove(pane)
    }

    override fun dispose() {
        held?.close()
        for (pane in patchedScrollPanes.keys.toList()) restoreEditor(pane)
        isDisposed = true
    }

    private class PatchedState(
        var vertical: Patch? = null,
        var horizontal: Patch? = null,
    )

    private class Patch(
        val bar: JScrollBar,
        val isExplicit: Boolean,
        val baseline: Dimension?,
    )

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, COMMITTED, DONE }

    companion object {
        private val LOG = logger<EditorScrollbarManager>()
        private const val ORIGINAL_SIZE_KEY = "ayuIslands.originalPreferredSize"
        private val ZERO_SIZE = Dimension(0, 0)

        private fun checkEdt() =
            check(SwingUtilities.isEventDispatchThread()) { "Editor scrollbar checkpoints require EDT" }

        private fun scrollbar(
            pane: JScrollPane,
            vertical: Boolean,
        ): JScrollBar = if (vertical) pane.verticalScrollBar else pane.horizontalScrollBar

        private fun size(value: Dimension): ScrollbarSize = ScrollbarSize(value.width, value.height)

        private fun observe(bar: JScrollBar): EditorScrollbarSnapshot {
            val baseline = bar.getClientProperty(ORIGINAL_SIZE_KEY)
            return EditorScrollbarSnapshot(
                if (bar.isPreferredSizeSet) bar.preferredSize?.let(::size) else null,
                baseline,
                (baseline as? Dimension)?.let(::size),
            )
        }

        private fun sameBaseline(
            first: EditorScrollbarSnapshot,
            second: EditorScrollbarSnapshot,
        ): Boolean = first.originalSize === second.originalSize && first.originalDimensions == second.originalDimensions

        private fun matches(
            first: EditorScrollbarSnapshot,
            second: EditorScrollbarSnapshot,
        ): Boolean = first.preferredSize == second.preferredSize && sameBaseline(first, second)

        fun getInstance(project: Project): EditorScrollbarManager =
            project.getService(EditorScrollbarManager::class.java)
    }
}

/** A null preferred size means Swing inheritance, not an explicit copy of the computed dimensions. */
internal data class EditorScrollbarSnapshot(
    val preferredSize: ScrollbarSize?,
    val originalSize: Any?,
    val originalDimensions: ScrollbarSize?,
)

internal data class ScrollbarSize(
    val width: Int,
    val height: Int,
)

/** Retained native identities are projected into portable Workspace receipts by the outer adapter. */
internal data class EditorScrollbarRecord(
    val pane: JScrollPane,
    val bar: JScrollBar,
    val isVertical: Boolean,
    val before: EditorScrollbarSnapshot,
    val planned: EditorScrollbarSnapshot,
    val lastWritten: EditorScrollbarSnapshot,
    val state: AccentWriteState,
)
