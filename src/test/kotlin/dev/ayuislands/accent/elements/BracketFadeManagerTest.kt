package dev.ayuislands.accent.elements

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.codeInsight.highlighting.BraceMatchingUtil.BraceHighlightingAndNavigationContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorEventMulticaster
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [BracketFadeManager].
 *
 * Uses reflection to invoke private methods and access internal state,
 * following the same patterns as [dev.ayuislands.accent.AccentApplicatorTest].
 */
class BracketFadeManagerTest {
    private val mockApplication = mockk<com.intellij.openapi.application.Application>(relaxed = true)
    private val mockEditorFactory = mockk<EditorFactory>(relaxed = true)
    private val mockMulticaster = mockk<EditorEventMulticaster>(relaxed = true)
    private val mockEditor = mockk<Editor>(relaxed = true)
    private val mockDocument = mockk<Document>(relaxed = true)
    private val mockMarkupModel = mockk<MarkupModel>(relaxed = true)
    private val mockProject = mockk<Project>(relaxed = true)
    private val mockPsiDocumentManager = mockk<PsiDocumentManager>(relaxed = true)
    private val mockPsiFile = mockk<PsiFile>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val mockHighlighter = mockk<RangeHighlighter>(relaxed = true)
    private val state = AyuIslandsState()

    @BeforeTest
    fun setUp() {
        // ApplicationManager must be mocked BEFORE AyuIslandsSettings.Companion,
        // because getInstance() calls ApplicationManager.getApplication().getService()
        // during MockK recording.
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication

        mockkStatic(EditorFactory::class)
        every { EditorFactory.getInstance() } returns mockEditorFactory
        every { mockEditorFactory.eventMulticaster } returns mockMulticaster

        mockkStatic(Disposer::class)
        every { Disposer.newDisposable(any<String>()) } returns mockk<Disposable>(relaxed = true)
        every { Disposer.dispose(any()) } returns Unit

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state
        state.bracketScopeEnabled = true

        mockkStatic(PsiDocumentManager::class)
        every { PsiDocumentManager.getInstance(any()) } returns mockPsiDocumentManager
        every { mockPsiDocumentManager.getPsiFile(any()) } returns mockPsiFile

        mockkStatic(BraceMatchingUtil::class)

        every { mockEditor.project } returns mockProject
        every { mockEditor.document } returns mockDocument
        every { mockEditor.markupModel } returns mockMarkupModel
        every { mockDocument.textLength } returns 1000

        every {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        } returns mockHighlighter

        resetState()
    }

    @AfterTest
    fun tearDown() {
        resetState()
        unmockkAll()
        clearAllMocks()
    }

    private fun resetState() {
        setPrivateField("disposable", null)
        setPrivateField("currentColor", null)
        getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters").clear()
    }

    private fun invokePrivate(
        methodName: String,
        vararg args: Any?,
    ) {
        val method =
            BracketFadeManager::class.java.declaredMethods
                .first { it.name == methodName }
        method.isAccessible = true
        method.invoke(BracketFadeManager, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(fieldName: String): T {
        val field = BracketFadeManager::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(BracketFadeManager) as T
    }

    private fun setPrivateField(
        fieldName: String,
        value: Any?,
    ) {
        val field = BracketFadeManager::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(BracketFadeManager, value)
    }

    // activate

    @Test
    fun `activate sets currentColor and creates disposable`() {
        val color = Color(255, 204, 102)

        BracketFadeManager.activate(color)

        val storedColor = getPrivateField<Color?>("currentColor")
        val storedDisposable = getPrivateField<Disposable?>("disposable")
        assertEquals(color, storedColor)
        assertNotNull(storedDisposable)
    }

    @Test
    fun `activate twice is idempotent for disposable creation`() {
        val color1 = Color(255, 204, 102)
        val color2 = Color(100, 150, 200)

        BracketFadeManager.activate(color1)
        val firstDisposable = getPrivateField<Disposable?>("disposable")

        BracketFadeManager.activate(color2)
        val secondDisposable = getPrivateField<Disposable?>("disposable")

        // Disposable should be same reference (not re-created)
        assertTrue(firstDisposable === secondDisposable)
        // Color should be updated to the second value
        assertEquals(color2, getPrivateField<Color?>("currentColor"))
    }

    @Test
    fun `activate installs caret listener and editor factory listener`() {
        BracketFadeManager.activate(Color.RED)

        verify { mockMulticaster.addCaretListener(any(), any()) }
        verify { mockEditorFactory.addEditorFactoryListener(any(), any()) }
    }

    // deactivate

    @Test
    fun `deactivate clears disposable and currentColor`() {
        BracketFadeManager.activate(Color.RED)
        assertNotNull(getPrivateField<Disposable?>("disposable"))
        assertNotNull(getPrivateField<Color?>("currentColor"))

        BracketFadeManager.deactivate()

        assertNull(getPrivateField<Disposable?>("disposable"))
        assertNull(getPrivateField<Color?>("currentColor"))
    }

    @Test
    fun `deactivate disposes the disposable`() {
        BracketFadeManager.activate(Color.RED)

        BracketFadeManager.deactivate()

        verify { Disposer.dispose(any()) }
    }

    @Test
    fun `deactivate calls removeAllHighlighters and clears map`() {
        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        every { mockHighlighter.isValid } returns true
        highlighters[mockEditor] = listOf(mockHighlighter)

        BracketFadeManager.deactivate()

        verify { mockMarkupModel.removeHighlighter(mockHighlighter) }
        assertTrue(highlighters.isEmpty())
    }

    @Test
    fun `deactivate when no disposable does not throw`() {
        // disposable is already null from resetState
        BracketFadeManager.deactivate()

        assertNull(getPrivateField<Disposable?>("disposable"))
    }

    // handleCaretMove: early returns

    @Test
    fun `handleCaretMove with unlicensed returns early without creating highlighter`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        setPrivateField("currentColor", Color.RED)

        invokePrivate("handleCaretMove", mockEditor)

        verify(exactly = 0) {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        }
    }

    @Test
    fun `handleCaretMove with bracketScopeEnabled false returns early`() {
        state.bracketScopeEnabled = false
        setPrivateField("currentColor", Color.RED)

        invokePrivate("handleCaretMove", mockEditor)

        verify(exactly = 0) {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        }
    }

    @Test
    fun `handleCaretMove with null currentColor returns early`() {
        setPrivateField("currentColor", null)

        invokePrivate("handleCaretMove", mockEditor)

        verify(exactly = 0) {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        }
    }

    @Test
    fun `handleCaretMove with null project returns early`() {
        setPrivateField("currentColor", Color.RED)
        every { mockEditor.project } returns null

        invokePrivate("handleCaretMove", mockEditor)

        verify(exactly = 0) {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        }
    }

    @Test
    fun `handleCaretMove with null psiFile returns early`() {
        setPrivateField("currentColor", Color.RED)
        every { mockPsiDocumentManager.getPsiFile(any()) } returns null

        invokePrivate("handleCaretMove", mockEditor)

        verify(exactly = 0) {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        }
    }

    @Test
    fun `handleCaretMove with null context returns early`() {
        setPrivateField("currentColor", Color.RED)
        every {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(any(), any())
        } returns null

        invokePrivate("handleCaretMove", mockEditor)

        verify(exactly = 0) {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        }
    }

    @Test
    fun `handleCaretMove with same offsets returns early`() {
        setPrivateField("currentColor", Color.RED)
        val mockContext = mockk<BraceHighlightingAndNavigationContext>(relaxed = true)
        every {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(any(), any())
        } returns mockContext
        every { mockContext.currentBraceOffset() } returns 42
        every { mockContext.navigationOffset() } returns 42

        invokePrivate("handleCaretMove", mockEditor)

        verify(exactly = 0) {
            mockMarkupModel.addRangeHighlighter(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any(),
                any<HighlighterTargetArea>(),
            )
        }
    }

    // handleCaretMove: success path

    @Test
    fun `handleCaretMove success creates highlighter with BracketScopeRenderer`() {
        setPrivateField("currentColor", Color.CYAN)
        val mockContext = mockk<BraceHighlightingAndNavigationContext>(relaxed = true)
        every {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(any(), any())
        } returns mockContext
        every { mockContext.currentBraceOffset() } returns 10
        every { mockContext.navigationOffset() } returns 50
        every { mockDocument.getLineNumber(10) } returns 2
        every { mockDocument.getLineNumber(50) } returns 8

        invokePrivate("handleCaretMove", mockEditor)

        verify {
            mockMarkupModel.addRangeHighlighter(
                10,
                51,
                any<Int>(),
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        }
        verify { mockHighlighter.lineMarkerRenderer = any<BracketScopeRenderer>() }

        val highlighters = getPrivateField<Map<Editor, List<RangeHighlighter>>>("activeHighlighters")
        assertTrue(highlighters.containsKey(mockEditor))
        assertEquals(1, highlighters[mockEditor]?.size)
    }

    @Test
    fun `handleCaretMove coerces endOffset plus one to textLength`() {
        setPrivateField("currentColor", Color.RED)
        val mockContext = mockk<BraceHighlightingAndNavigationContext>(relaxed = true)
        every {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(any(), any())
        } returns mockContext
        every { mockContext.currentBraceOffset() } returns 10
        every { mockContext.navigationOffset() } returns 999
        every { mockDocument.textLength } returns 1000
        every { mockDocument.getLineNumber(any()) } returns 0

        invokePrivate("handleCaretMove", mockEditor)

        // endOffset + 1 = 1000, coerced to textLength = 1000
        verify {
            mockMarkupModel.addRangeHighlighter(
                10,
                1000,
                any<Int>(),
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        }
    }

    @Test
    fun `handleCaretMove with reversed offsets uses min and max correctly`() {
        setPrivateField("currentColor", Color.RED)
        val mockContext = mockk<BraceHighlightingAndNavigationContext>(relaxed = true)
        every {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(any(), any())
        } returns mockContext
        // currentBraceOffset > navigationOffset (reversed order)
        every { mockContext.currentBraceOffset() } returns 80
        every { mockContext.navigationOffset() } returns 20
        every { mockDocument.getLineNumber(20) } returns 3
        every { mockDocument.getLineNumber(80) } returns 12

        invokePrivate("handleCaretMove", mockEditor)

        verify {
            mockMarkupModel.addRangeHighlighter(
                20,
                81,
                any<Int>(),
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        }
    }

    @Test
    fun `handleCaretMove catches RuntimeException from BraceMatchingUtil`() {
        setPrivateField("currentColor", Color.RED)
        every {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(any(), any())
        } throws RuntimeException("edge case crash")

        // Should not throw
        invokePrivate("handleCaretMove", mockEditor)

        val highlighters = getPrivateField<Map<Editor, List<RangeHighlighter>>>("activeHighlighters")
        assertTrue(highlighters.isEmpty())
    }

    @Test
    fun `handleCaretMove removes existing highlighters before processing`() {
        val existingHighlighter = mockk<RangeHighlighter>(relaxed = true)
        every { existingHighlighter.isValid } returns true
        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        highlighters[mockEditor] = listOf(existingHighlighter)

        // Set up for early return (unlicensed) so we can verify removal without new creation
        every { LicenseChecker.isLicensedOrGrace() } returns false
        setPrivateField("currentColor", Color.RED)

        invokePrivate("handleCaretMove", mockEditor)

        verify { mockMarkupModel.removeHighlighter(existingHighlighter) }
    }

    @Test
    fun `handleCaretMove stores highlighter in activeHighlighters map`() {
        setPrivateField("currentColor", Color.MAGENTA)
        val mockContext = mockk<BraceHighlightingAndNavigationContext>(relaxed = true)
        every {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(any(), any())
        } returns mockContext
        every { mockContext.currentBraceOffset() } returns 5
        every { mockContext.navigationOffset() } returns 25
        every { mockDocument.getLineNumber(5) } returns 0
        every { mockDocument.getLineNumber(25) } returns 4

        invokePrivate("handleCaretMove", mockEditor)

        val highlighters = getPrivateField<Map<Editor, List<RangeHighlighter>>>("activeHighlighters")
        assertEquals(listOf(mockHighlighter), highlighters[mockEditor])
    }

    // removeHighlighters

    @Test
    fun `removeHighlighters removes valid highlighters from markup model`() {
        val validHighlighter = mockk<RangeHighlighter>(relaxed = true)
        every { validHighlighter.isValid } returns true
        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        highlighters[mockEditor] = listOf(validHighlighter)

        invokePrivate("removeHighlighters", mockEditor)

        verify { mockMarkupModel.removeHighlighter(validHighlighter) }
        assertTrue(!highlighters.containsKey(mockEditor))
    }

    @Test
    fun `removeHighlighters skips invalid highlighters`() {
        val invalidHighlighter = mockk<RangeHighlighter>(relaxed = true)
        every { invalidHighlighter.isValid } returns false
        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        highlighters[mockEditor] = listOf(invalidHighlighter)

        invokePrivate("removeHighlighters", mockEditor)

        verify(exactly = 0) { mockMarkupModel.removeHighlighter(any()) }
        assertTrue(!highlighters.containsKey(mockEditor))
    }

    @Test
    fun `removeHighlighters with no highlighters for editor does nothing`() {
        invokePrivate("removeHighlighters", mockEditor)

        verify(exactly = 0) { mockMarkupModel.removeHighlighter(any()) }
    }

    // removeAllHighlighters

    @Test
    fun `removeAllHighlighters removes all valid highlighters and clears map`() {
        val editor1 = mockk<Editor>(relaxed = true)
        val editor2 = mockk<Editor>(relaxed = true)
        val markup1 = mockk<MarkupModel>(relaxed = true)
        val markup2 = mockk<MarkupModel>(relaxed = true)
        every { editor1.markupModel } returns markup1
        every { editor2.markupModel } returns markup2
        val hl1 = mockk<RangeHighlighter>(relaxed = true)
        val hl2 = mockk<RangeHighlighter>(relaxed = true)
        every { hl1.isValid } returns true
        every { hl2.isValid } returns true

        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        highlighters[editor1] = listOf(hl1)
        highlighters[editor2] = listOf(hl2)

        invokePrivate("removeAllHighlighters")

        verify { markup1.removeHighlighter(hl1) }
        verify { markup2.removeHighlighter(hl2) }
        assertTrue(highlighters.isEmpty())
    }

    @Test
    fun `removeAllHighlighters skips invalid highlighters`() {
        val invalidHighlighter = mockk<RangeHighlighter>(relaxed = true)
        every { invalidHighlighter.isValid } returns false

        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        highlighters[mockEditor] = listOf(invalidHighlighter)

        invokePrivate("removeAllHighlighters")

        verify(exactly = 0) { mockMarkupModel.removeHighlighter(any()) }
        assertTrue(highlighters.isEmpty())
    }

    @Test
    fun `removeAllHighlighters handles RuntimeException from individual removals`() {
        val throwingHighlighter = mockk<RangeHighlighter>(relaxed = true)
        every { throwingHighlighter.isValid } returns true
        every { mockMarkupModel.removeHighlighter(throwingHighlighter) } throws RuntimeException("already disposed")

        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        highlighters[mockEditor] = listOf(throwingHighlighter)

        // Should not throw
        invokePrivate("removeAllHighlighters")

        assertTrue(highlighters.isEmpty())
    }

    @Test
    fun `removeAllHighlighters clears map even when empty`() {
        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        assertTrue(highlighters.isEmpty())

        invokePrivate("removeAllHighlighters")

        assertTrue(highlighters.isEmpty())
    }

    @Test
    fun `removeAllHighlighters handles multiple highlighters per editor`() {
        val hl1 = mockk<RangeHighlighter>(relaxed = true)
        val hl2 = mockk<RangeHighlighter>(relaxed = true)
        val hl3 = mockk<RangeHighlighter>(relaxed = true)
        every { hl1.isValid } returns true
        every { hl2.isValid } returns false
        every { hl3.isValid } returns true

        val highlighters = getPrivateField<MutableMap<Editor, List<RangeHighlighter>>>("activeHighlighters")
        highlighters[mockEditor] = listOf(hl1, hl2, hl3)

        invokePrivate("removeAllHighlighters")

        verify { mockMarkupModel.removeHighlighter(hl1) }
        verify(exactly = 0) { mockMarkupModel.removeHighlighter(hl2) }
        verify { mockMarkupModel.removeHighlighter(hl3) }
        assertTrue(highlighters.isEmpty())
    }

    // installListeners (tested indirectly via activate)

    @Test
    fun `activate registers both caret and editor factory listeners`() {
        BracketFadeManager.activate(Color.GREEN)

        verify(exactly = 1) { mockMulticaster.addCaretListener(any(), any()) }
        verify(exactly = 1) { mockEditorFactory.addEditorFactoryListener(any(), any()) }
    }

    // Full lifecycle

    @Test
    fun `activate then deactivate then activate creates fresh disposable`() {
        BracketFadeManager.activate(Color.RED)
        val first = getPrivateField<Disposable?>("disposable")

        BracketFadeManager.deactivate()
        assertNull(getPrivateField<Disposable?>("disposable"))

        BracketFadeManager.activate(Color.BLUE)
        val second = getPrivateField<Disposable?>("disposable")

        assertNotNull(first)
        assertNotNull(second)
    }

    @Test
    fun `activate with existing disposable only updates color`() {
        val mockDisposable = mockk<Disposable>(relaxed = true)
        setPrivateField("disposable", mockDisposable)
        setPrivateField("currentColor", Color.RED)

        BracketFadeManager.activate(Color.BLUE)

        // Color updated
        assertEquals(Color.BLUE, getPrivateField<Color?>("currentColor"))
        // Disposable unchanged — no new listeners installed
        assertTrue(mockDisposable === getPrivateField<Disposable?>("disposable"))
        // installListeners not called (no new EditorFactory interaction)
        verify(exactly = 0) { mockEditorFactory.addEditorFactoryListener(any(), any()) }
    }
}
