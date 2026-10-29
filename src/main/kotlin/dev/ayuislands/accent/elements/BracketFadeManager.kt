package dev.ayuislands.accent.elements

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentApplyScope
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.restoreAccentCheckpoints
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.SwingUtilities

object BracketFadeManager {
    private val LOG = logger<BracketFadeManager>()

    @Volatile private var disposable: Disposable? = null

    @Volatile private var currentColor: Color? = null
    private val activeHighlighters = mutableMapOf<Editor, List<RangeHighlighter>>()
    private val pending = mutableListOf<FadeCheckpoint>()

    fun activate(color: Color) {
        if (checkpointChange(color)) return
        currentColor = color
        // Always reinstall listeners: early calls (appFrameCreated) may land on an
        // incompletely initialized EditorFactory; later calls provide valid listeners.
        disposable?.let {
            removeAllHighlighters()
            Disposer.dispose(it)
        }
        val parentDisposable = Disposer.newDisposable("BracketFadeManager")
        disposable = parentDisposable
        installListeners(parentDisposable)
        LOG.info("BracketFadeManager activated, color=$color")
    }

    fun deactivate() {
        if (checkpointChange(null)) return
        removeAllHighlighters()
        disposable?.let { Disposer.dispose(it) }
        disposable = null
        currentColor = null
    }

    private fun checkpointChange(color: Color?): Boolean {
        val scope =
            if (SwingUtilities.isEventDispatchThread()) AccentApplyScope.active?.takeUnless { it.isCommitted } else null
        if (scope == null) {
            restoreAccentCheckpoints(pending)
            return false
        }
        scope.checkWritable()
        scope.recover { restoreAccentCheckpoints(pending) }
        val checkpoint = FadeCheckpoint()
        scope.record(checkpoint)
        checkpoint.apply(color)
        return true
    }

    private enum class FadePhase { RECORDING, RESTORING, RETIRING, DONE }

    private class MarkerSnapshot(
        val highlighter: RangeHighlighter,
        val renderer: LineMarkerRenderer?,
    )

    private class MarkupSnapshot(
        val editor: Editor,
        val original: List<RangeHighlighter>,
    ) {
        val remaining = original.map { MarkerSnapshot(it, it.lineMarkerRenderer) }.toMutableList()
    }

    /** Keeps old native objects alive until rollback is no longer possible. */
    private class FadeCheckpoint : AccentCheckpoint {
        private val target = UiDefaultsTarget()
        private val previousParent = disposable
        private val previousColor = currentColor
        private val markup = activeHighlighters.map { (editor, markers) -> MarkupSnapshot(editor, markers) }
        private var expectedParent = previousParent
        private var expectedColor = previousColor
        private var createdParent: Disposable? = null
        private var phase = FadePhase.RECORDING
        private val retiringParents = mutableListOf<Disposable>()

        override val hasPending: Boolean
            get() = phase != FadePhase.DONE

        fun apply(color: Color?) {
            val next = if (color == null) null else Disposer.newDisposable("BracketFadeManager")
            createdParent = next
            expectedParent = next
            expectedColor = color
            disposable = next
            currentColor = color
            if (next != null) installListeners(next)
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            if (phase == FadePhase.DONE) return
            if (phase == FadePhase.RECORDING) {
                val sameTarget = target.isCurrent()
                if (disposable === expectedParent && currentColor === expectedColor) {
                    disposable = if (sameTarget) previousParent else null
                    currentColor = if (sameTarget) previousColor else null
                }
                createdParent?.let(retiringParents::add)
                if (!sameTarget) previousParent?.let(retiringParents::add)
                phase = if (sameTarget) FadePhase.RESTORING else FadePhase.RETIRING
            }
            releaseResources()
        }

        override fun commit() {
            check(phase == FadePhase.RECORDING)
            previousParent?.let(retiringParents::add)
            phase = FadePhase.RETIRING
        }

        override fun releaseResources() {
            var failure = if (phase == FadePhase.RETIRING) retireMarkup() else null
            for (parent in retiringParents.toList()) {
                try {
                    if (parent !== disposable) Disposer.dispose(parent)
                    retiringParents.remove(parent)
                } catch (exception: RuntimeException) {
                    failure = combineUiFailures(failure, exception)
                }
            }
            if (failure == null) phase = FadePhase.DONE
            failure?.let { throw it }
        }

        private fun retireMarkup(): Throwable? {
            var failure: Throwable? = null
            for (snapshot in markup) {
                for (marker in snapshot.remaining.toList()) {
                    try {
                        if (!snapshot.editor.isDisposed &&
                            marker.highlighter.isValid &&
                            marker.highlighter.lineMarkerRenderer === marker.renderer
                        ) {
                            snapshot.editor.markupModel.removeHighlighter(marker.highlighter)
                        }
                        snapshot.remaining.remove(marker)
                    } catch (exception: RuntimeException) {
                        failure = combineUiFailures(failure, exception)
                    }
                }
                if (snapshot.remaining.isEmpty() && activeHighlighters[snapshot.editor] === snapshot.original) {
                    activeHighlighters.remove(snapshot.editor)
                }
            }
            return failure
        }

        override fun retain() {
            if (hasPending && this !in pending) pending += this
        }
    }

    private fun installListeners(parentDisposable: Disposable) {
        val factory = EditorFactory.getInstance()

        factory.eventMulticaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    if (disposable === parentDisposable) handleCaretMove(event.editor)
                }
            },
            parentDisposable,
        )

        factory.addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    if (disposable === parentDisposable) activeHighlighters.remove(event.editor)
                }
            },
            parentDisposable,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleCaretMove(editor: Editor) {
        val scope = AccentApplyScope.active
        if (scope != null) {
            scope.onClose {
                AccentApplyPlanRunner.post { if (!editor.isDisposed) handleCaretMove(editor) }
            }
            return
        }
        removeHighlighters(editor)
        if (!LicenseChecker.isLicensedOrGrace()) {
            LOG.debug("BracketFade: blocked by license check")
            return
        }
        if (!AyuIslandsSettings.getInstance().state.bracketScopeEnabled) {
            LOG.debug("BracketFade: bracketScopeEnabled=false")
            return
        }
        val color = currentColor ?: return

        val project = editor.project ?: return

        // PSI access requires a read action (EDT alone is not enough since 2025.1 threading model)
        val bracketRange =
            try {
                ApplicationManager.getApplication().runReadAction<IntArray?> {
                    val psiFile =
                        PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                            ?: return@runReadAction null
                    val context =
                        BraceMatchingUtil.computeHighlightingAndNavigationContext(editor, psiFile)
                            ?: return@runReadAction null
                    val currentOffset = context.currentBraceOffset()
                    val matchOffset = context.navigationOffset()
                    if (currentOffset == matchOffset) return@runReadAction null
                    intArrayOf(
                        minOf(currentOffset, matchOffset),
                        maxOf(currentOffset, matchOffset),
                    )
                }
            } catch (exception: RuntimeException) {
                LOG.warn("BracketFade: BraceMatchingUtil threw", exception)
                null
            } ?: return

        val startOffset = bracketRange[0]
        val endOffset = bracketRange[1]
        val startLine = editor.document.getLineNumber(startOffset)
        val endLine = editor.document.getLineNumber(endOffset)

        val highlighter =
            editor.markupModel.addRangeHighlighter(
                startOffset,
                (endOffset + 1).coerceAtMost(editor.document.textLength),
                HighlighterLayer.SELECTION - 1,
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        highlighter.lineMarkerRenderer = BracketScopeRenderer(color, startLine, endLine)
        activeHighlighters[editor] = listOf(highlighter)
        LOG.debug("BracketFade: scope rendered lines $startLine..$endLine")
    }

    private fun removeHighlighters(editor: Editor) {
        activeHighlighters.remove(editor)?.forEach { highlighter ->
            if (highlighter.isValid) {
                editor.markupModel.removeHighlighter(highlighter)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun removeAllHighlighters() {
        for ((editor, highlighters) in activeHighlighters) {
            for (highlighter in highlighters) {
                try {
                    if (highlighter.isValid) {
                        editor.markupModel.removeHighlighter(highlighter)
                    }
                } catch (_: RuntimeException) {
                    // Editor may already be disposed
                }
            }
        }
        activeHighlighters.clear()
    }
}
