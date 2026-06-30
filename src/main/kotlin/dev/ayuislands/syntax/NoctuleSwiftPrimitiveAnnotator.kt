package dev.ayuislands.syntax

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import dev.ayuislands.accent.AyuVariant

internal class NoctuleSwiftPrimitiveAnnotator(
    private val isAyuActive: () -> Boolean = AyuVariant::isAyuActive,
) : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        if (element.firstChild != null) return
        val languageId = element.language.id
        if (languageId != NOCTULE_SWIFT_LANGUAGE_ID) return

        val attributeKey =
            primitiveAttributeKey(
                languageId = languageId,
                tokenText = element.text,
                hasChildren = false,
                isAyuActive = isAyuActive(),
            ) ?: return

        holder
            .newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(attributeKey)
            .create()
    }

    companion object {
        private const val NOCTULE_SWIFT_LANGUAGE_ID = "NoctuleSwift"
        private const val NIL_LITERAL = "nil"
        private const val SWIFT_KEYWORD_KEY = "SWIFT.KEYWORD"
        private val swiftKeywordAttributes = TextAttributesKey.createTextAttributesKey(SWIFT_KEYWORD_KEY)

        internal fun primitiveAttributeKey(
            languageId: String,
            tokenText: String,
            hasChildren: Boolean,
            isAyuActive: Boolean,
        ): TextAttributesKey? {
            if (!isAyuActive) return null
            if (languageId != NOCTULE_SWIFT_LANGUAGE_ID) return null
            if (hasChildren) return null
            return if (tokenText == NIL_LITERAL) swiftKeywordAttributes else null
        }
    }
}
