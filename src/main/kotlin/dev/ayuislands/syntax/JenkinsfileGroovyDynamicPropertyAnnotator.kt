package dev.ayuislands.syntax

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.groovy.highlighter.GroovySyntaxHighlighter
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression

internal class JenkinsfileGroovyDynamicPropertyAnnotator : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        val reference = element as? GrReferenceExpression ?: return
        if (reference.containingFile.name != JENKINSFILE_NAME) return
        if (reference.resolve() != null) return
        if (reference.hasAt() || reference.hasMemberPointer()) return

        val attributeKey = reference.semanticFallbackKey() ?: return
        val referenceName = reference.referenceNameElement ?: return
        holder
            .newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(referenceName)
            .textAttributes(attributeKey)
            .create()
    }

    private fun GrReferenceExpression.semanticFallbackKey(): TextAttributesKey? =
        when {
            isJenkinsDynamicBindingProperty() -> GroovySyntaxHighlighter.INSTANCE_PROPERTY_REFERENCE
            isQualifiedMethodCall() -> GroovySyntaxHighlighter.METHOD_CALL
            else -> null
        }

    private fun GrReferenceExpression.isJenkinsDynamicBindingProperty(): Boolean {
        val qualifier = qualifierExpression as? GrReferenceExpression ?: return false
        return qualifier.referenceName in JENKINS_DYNAMIC_BINDINGS
    }

    private fun GrReferenceExpression.isQualifiedMethodCall(): Boolean {
        if (qualifierExpression == null) return false
        val methodCall = parent as? GrMethodCall ?: return false
        return methodCall.invokedExpression == this
    }

    private companion object {
        private const val JENKINSFILE_NAME = "Jenkinsfile"
    }
}

private val JENKINS_DYNAMIC_BINDINGS =
    setOf(
        "currentBuild",
        "env",
        "params",
    )
