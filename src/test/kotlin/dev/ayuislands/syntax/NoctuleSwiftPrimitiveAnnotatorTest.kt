package dev.ayuislands.syntax

import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoctuleSwiftPrimitiveAnnotatorTest {
    @Test
    fun `Noctule nil literal uses Swift keyword color when Ayu is active`() {
        val key =
            NoctuleSwiftPrimitiveAnnotator.primitiveAttributeKey(
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
            NoctuleSwiftPrimitiveAnnotator.primitiveAttributeKey(
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
            NoctuleSwiftPrimitiveAnnotator.primitiveAttributeKey(
                languageId = "Swift",
                tokenText = "nil",
                hasChildren = false,
                isAyuActive = true,
            ),
        )
        assertNull(
            NoctuleSwiftPrimitiveAnnotator.primitiveAttributeKey(
                languageId = NOCTULE_SWIFT_LANGUAGE_ID,
                tokenText = "nil",
                hasChildren = true,
                isAyuActive = true,
            ),
        )
        assertNull(
            NoctuleSwiftPrimitiveAnnotator.primitiveAttributeKey(
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
        val element = mockLeaf(languageId = NOCTULE_SWIFT_LANGUAGE_ID, text = "nil")
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

    private fun mockLeaf(
        languageId: String,
        text: String = "nil",
    ): PsiElement {
        val language = mockk<Language> { every { id } returns languageId }
        val element = mockk<PsiElement>()
        every { element.firstChild } returns null
        every { element.language } returns language
        every { element.text } returns text
        return element
    }

    private companion object {
        private const val NOCTULE_SWIFT_LANGUAGE_ID = "NoctuleSwift"
    }
}
