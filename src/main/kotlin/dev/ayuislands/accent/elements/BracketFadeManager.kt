package dev.ayuislands.accent.elements

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import dev.ayuislands.licensing.LicenseChecker
import java.awt.Color

object BracketFadeManager {
    private var disposable: Disposable? = null
    private var currentColor: Color? = null
    private val activeHighlighters = mutableMapOf<Editor, List<RangeHighlighter>>()

    fun activate(color: Color) {
        currentColor = color
        if (disposable != null) return
        val parentDisposable = Disposer.newDisposable("BracketFadeManager")
        disposable = parentDisposable
        installListeners(parentDisposable)
    }

    fun deactivate() {
        removeAllHighlighters()
        disposable?.let { Disposer.dispose(it) }
        disposable = null
        currentColor = null
    }

    private fun installListeners(parentDisposable: Disposable) {
        val factory = EditorFactory.getInstance()

        factory.eventMulticaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    handleCaretMove(event.editor)
                }
            },
            parentDisposable,
        )

        factory.addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    activeHighlighters.remove(event.editor)
                }
            },
            parentDisposable,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleCaretMove(editor: Editor) {
        removeHighlighters(editor)
        if (!LicenseChecker.isLicensedOrGrace()) return
        val color = currentColor ?: return

        val project = editor.project ?: return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return

        try {
            val context =
                BraceMatchingUtil.computeHighlightingAndNavigationContext(editor, psiFile)
                    ?: return

            val currentOffset = context.currentBraceOffset()
            val matchOffset = context.navigationOffset()
            if (currentOffset == matchOffset) return

            val startOffset = minOf(currentOffset, matchOffset)
            val endOffset = maxOf(currentOffset, matchOffset)
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
        } catch (_: RuntimeException) {
            // BraceMatchingUtil can throw on edge cases
        }
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
