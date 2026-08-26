package dev.ayuislands.syntax

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiElement
import dev.ayuislands.accent.AyuVariant

private const val NOCTULE_LANGUAGE_ID = "NoctuleSwift"
private const val SWIFT_LANGUAGE = "Swift"
private const val NIL_LITERAL = "nil"
private const val SWIFT_KEYWORD_KEY = "SWIFT.KEYWORD"
private val swiftKeywordAttributes = TextAttributesKey.createTextAttributesKey(SWIFT_KEYWORD_KEY)

internal class NoctuleSwiftPrimitiveAnnotator(
    private val operatorKeys: (PsiElement) -> Array<TextAttributesKey> = ::swiftOperatorKeys,
    private val replacementFontType: () -> Int? = {
        SyntaxIntensityService
            .getInstance()
            .replacementFontType(SWIFT_LANGUAGE, PrimitiveCategory.OPERATOR)
    },
    private val tokenAttributes: (TextAttributesKey) -> TextAttributes? = { key ->
        EditorColorsManager.getInstance().globalScheme.getAttributes(key)
    },
    private val isAyuActive: () -> Boolean = AyuVariant::isAyuActive,
) : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        if (element.firstChild != null) return
        val languageId = element.language.id
        if (languageId != NOCTULE_LANGUAGE_ID) return
        if (!isAyuActive()) return

        val attributeKey =
            swiftPrimitiveKey(
                languageId = languageId,
                tokenText = element.text,
                hasChildren = false,
                isAyuActive = true,
            )

        if (attributeKey != null) {
            holder
                .newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(element)
                .textAttributes(attributeKey)
                .create()
            return
        }

        val tokenText = element.text
        if (!isOperatorToken(tokenText)) return
        val replacementFontType = replacementFontType() ?: return
        val operatorKey = operatorKeys(element).firstOrNull() ?: return
        val attributes = tokenAttributes(operatorKey)?.clone() ?: return
        attributes.fontType = replacementFontType

        holder
            .newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .enforcedTextAttributes(attributes)
            .create()
    }
}

internal fun swiftPrimitiveKey(
    languageId: String,
    tokenText: String,
    hasChildren: Boolean,
    isAyuActive: Boolean,
): TextAttributesKey? {
    if (!isAyuActive) return null
    if (languageId != NOCTULE_LANGUAGE_ID) return null
    if (hasChildren) return null
    return if (tokenText == NIL_LITERAL) swiftKeywordAttributes else null
}

private fun isOperatorToken(tokenText: String): Boolean =
    tokenText.isNotEmpty() &&
        tokenText.none { character ->
            character.isLetterOrDigit() ||
                character == '_' ||
                character.isWhitespace() ||
                character == '"' ||
                character == '\''
        }

private fun swiftOperatorKeys(element: PsiElement): Array<TextAttributesKey> {
    val node = element.node ?: return emptyArray()
    val file = element.containingFile ?: return emptyArray()
    val highlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(
            element.language,
            element.project,
            file.virtualFile,
        )
    return highlighter
        .getTokenHighlights(node.elementType)
        .filter { key ->
            SyntaxKeyRoleRegistry.classify(key.externalName).effectivePrimitive == PrimitiveCategory.OPERATOR
        }.toTypedArray()
}
