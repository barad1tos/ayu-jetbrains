package dev.ayuislands.syntax

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.awt.Color
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoctuleSwiftPrimitiveAnnotatorTest {
    @Test
    fun `Noctule nil literal uses Swift keyword color when Ayu is active`() {
        val key =
            swiftPrimitiveKey(
                languageId = NOCTULE_SWIFT_LANGUAGE_ID,
                tokenText = "nil",
                hasChildren = false,
                isAyuActive = true,
            )

        assertEquals("SWIFT.KEYWORD", key?.externalName)
    }

    @Test
    fun `Noctule identifiers are left to upstream semantic highlighting`() {
        val key =
            swiftPrimitiveKey(
                languageId = NOCTULE_SWIFT_LANGUAGE_ID,
                tokenText = "track",
                hasChildren = false,
                isAyuActive = true,
            )

        assertNull(key)
    }

    @Test
    fun `nil fallback stays scoped to Noctule Swift leaves on Ayu themes`() {
        assertNull(
            swiftPrimitiveKey(
                languageId = "Swift",
                tokenText = "nil",
                hasChildren = false,
                isAyuActive = true,
            ),
        )
        assertNull(
            swiftPrimitiveKey(
                languageId = NOCTULE_SWIFT_LANGUAGE_ID,
                tokenText = "nil",
                hasChildren = true,
                isAyuActive = true,
            ),
        )
        assertNull(
            swiftPrimitiveKey(
                languageId = NOCTULE_SWIFT_LANGUAGE_ID,
                tokenText = "nil",
                hasChildren = false,
                isAyuActive = false,
            ),
        )
    }

    @Test
    fun `annotate ignores composite PSI before reading text or theme state`() {
        val element =
            mockk<PsiElement> {
                every { firstChild } returns mockk()
            }
        val holder = mockk<AnnotationHolder>(relaxed = true)
        val annotator =
            NoctuleSwiftPrimitiveAnnotator {
                error("Ayu theme state must not be read for composite PSI")
            }

        annotator.annotate(element, holder)

        verify(exactly = 0) { holder.newSilentAnnotation(any()) }
    }

    @Test
    fun `annotate ignores non Noctule Swift leaves before reading text or theme state`() {
        val language = mockk<Language> { every { id } returns "kotlin" }
        val element = mockk<PsiElement>()
        every { element.firstChild } returns null
        every { element.language } returns language
        val holder = mockk<AnnotationHolder>(relaxed = true)
        val annotator =
            NoctuleSwiftPrimitiveAnnotator {
                error("Ayu theme state must not be read for foreign languages")
            }

        annotator.annotate(element, holder)

        verify(exactly = 0) { holder.newSilentAnnotation(any()) }
    }

    @Test
    fun `annotate colors Noctule nil leaves with Swift keyword attributes`() {
        val element = mockSwiftLeaf()
        val holder = mockk<AnnotationHolder>()
        val builder = mockk<AnnotationBuilder>()
        every { holder.newSilentAnnotation(HighlightSeverity.INFORMATION) } returns builder
        every { builder.range(element) } returns builder
        every {
            builder.textAttributes(match { it.externalName == "SWIFT.KEYWORD" })
        } returns builder
        justRun { builder.create() }

        NoctuleSwiftPrimitiveAnnotator { true }.annotate(element, holder)

        verify(exactly = 1) { holder.newSilentAnnotation(HighlightSeverity.INFORMATION) }
        verify(exactly = 1) { builder.range(element) }
        verify(exactly = 1) {
            builder.textAttributes(match { it.externalName == "SWIFT.KEYWORD" })
        }
        verify(exactly = 1) { builder.create() }
    }

    @Test
    fun `explicit operator replacement enforces exact style over semantic highlighting`() {
        val element = mockSwiftLeaf(text = "[")
        val operatorKey = TextAttributesKey.createTextAttributesKey("SWIFT.BRACKETS.TEST")
        val sourceColor = Color(0xCC, 0xCA, 0xC2)
        val sourceAttributes =
            TextAttributes().apply {
                foregroundColor = sourceColor
                fontType = Font.ITALIC
            }
        val holder = mockk<AnnotationHolder>()
        val builder = mockk<AnnotationBuilder>()
        val enforced = slot<TextAttributes>()
        every { holder.newSilentAnnotation(HighlightSeverity.INFORMATION) } returns builder
        every { builder.range(element) } returns builder
        every { builder.enforcedTextAttributes(capture(enforced)) } returns builder
        justRun { builder.create() }

        NoctuleSwiftPrimitiveAnnotator(
            isAyuActive = { true },
            operatorKeys = { listOf(operatorKey) },
            replacementFontType = { Font.PLAIN },
            tokenAttributes = { sourceAttributes },
        ).annotate(element, holder)

        assertEquals(Font.PLAIN, enforced.captured.fontType)
        assertEquals(sourceColor, enforced.captured.foregroundColor)
        verify(exactly = 1) { builder.enforcedTextAttributes(any()) }
        verify(exactly = 0) { builder.textAttributes(any()) }
        verify(exactly = 1) { builder.create() }
    }

    @Test
    fun `operator leaves stay upstream owned without explicit replacement`() {
        val element = mockSwiftLeaf(text = "!")
        val holder = mockk<AnnotationHolder>(relaxed = true)

        NoctuleSwiftPrimitiveAnnotator(
            isAyuActive = { true },
            operatorKeys = {
                listOf(TextAttributesKey.createTextAttributesKey("SWIFT.OPERATOR.TEST"))
            },
            replacementFontType = { null },
            tokenAttributes = { error("Attributes must not resolve without replacement style") },
        ).annotate(element, holder)

        verify(exactly = 0) { holder.newSilentAnnotation(any()) }
    }

    @Test
    fun `operator replacement stays disabled outside Ayu themes`() {
        val element = mockSwiftLeaf(text = "[")
        val holder = mockk<AnnotationHolder>(relaxed = true)

        NoctuleSwiftPrimitiveAnnotator(
            operatorKeys = { error("Operator keys must not resolve outside Ayu themes") },
            replacementFontType = {
                error("Replacement style must not resolve outside Ayu themes")
            },
            tokenAttributes = { error("Token attributes must not resolve outside Ayu themes") },
            isAyuActive = { false },
        ).annotate(element, holder)

        verify(exactly = 0) { holder.newSilentAnnotation(any()) }
    }

    @Test
    fun `operator key cache resolves one immutable result per token profile`() {
        val language = Language.findLanguageByID("TEXT") ?: Language.ANY
        val elementType = IElementType("SWIFT_LEFT_BRACKET", language)
        val fileType = mockk<FileType> { every { name } returns "Swift" }
        val file = mockk<PsiFile> { every { this@mockk.fileType } returns fileType }
        val first = mockOperatorLeaf(language, elementType, file)
        val second = mockOperatorLeaf(language, elementType, file)
        val operatorKey = TextAttributesKey.createTextAttributesKey("SWIFT.BRACKETS.CACHED")
        var resolutions = 0
        val cache =
            SwiftOperatorKeyCache {
                resolutions += 1
                listOf(operatorKey)
            }

        val firstKeys = cache.keys(first)
        val secondKeys = cache.keys(second)

        assertEquals(listOf(operatorKey), firstKeys)
        assertEquals(firstKeys, secondKeys)
        assertEquals(1, resolutions)
    }

    private fun mockSwiftLeaf(text: String = "nil"): PsiElement {
        val language = mockk<Language> { every { id } returns NOCTULE_SWIFT_LANGUAGE_ID }
        val element = mockk<PsiElement>()
        every { element.firstChild } returns null
        every { element.language } returns language
        every { element.text } returns text
        return element
    }

    private fun mockOperatorLeaf(
        language: Language,
        elementType: IElementType,
        file: PsiFile,
    ): PsiElement {
        val node = mockk<ASTNode> { every { this@mockk.elementType } returns elementType }
        return mockk {
            every { this@mockk.language } returns language
            every { this@mockk.node } returns node
            every { containingFile } returns file
        }
    }

    private companion object {
        private const val NOCTULE_SWIFT_LANGUAGE_ID = "NoctuleSwift"
    }
}
