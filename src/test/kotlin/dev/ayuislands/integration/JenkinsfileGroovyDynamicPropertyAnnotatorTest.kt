package dev.ayuislands.integration

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.jetbrains.plugins.groovy.GroovyFileType
import org.jetbrains.plugins.groovy.highlighter.GroovySyntaxHighlighter
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JenkinsfileGroovyDynamicPropertyAnnotatorTest : LightPlatformCodeInsightFixture4TestCase() {
    @Test
    fun `Jenkinsfile dynamic binding properties use native Groovy property key`() {
        val text =
            $$"""
            boolean isMainBranch() {
                return env.BRANCH_NAME == "main" || env.GIT_BRANCH == "origin/main"
            }

            node("${label}-qa-monitor-runtime") {
                currentBuild.description = "branch=${env.BRANCH_NAME ?: env.GIT_BRANCH}<br/>mode=${params.RUN_MODE}"
            }
            """.trimIndent()
        val highlightInfos = highlightGroovyFile(JENKINSFILE_NAME, text)

        assertTokenHasKey(text, highlightInfos, "BRANCH_NAME", 0, INSTANCE_PROPERTY_REFERENCE_KEY)
        assertTokenHasKey(text, highlightInfos, "GIT_BRANCH", 0, INSTANCE_PROPERTY_REFERENCE_KEY)
        assertTokenHasKey(text, highlightInfos, "description", 0, INSTANCE_PROPERTY_REFERENCE_KEY)
        assertTokenHasKey(text, highlightInfos, "BRANCH_NAME", 1, INSTANCE_PROPERTY_REFERENCE_KEY)
        assertTokenHasKey(text, highlightInfos, "GIT_BRANCH", 1, INSTANCE_PROPERTY_REFERENCE_KEY)
        assertTokenHasKey(text, highlightInfos, "RUN_MODE", 0, INSTANCE_PROPERTY_REFERENCE_KEY)
    }

    @Test
    fun `Jenkinsfile unresolved qualified method calls use native Groovy method key`() {
        val text =
            """
            String imageTag() {
                return sh(script: "git rev-parse --short=12 HEAD", returnStdout: true)
                    .trim()
                    .toUpperCase()
            }
            """.trimIndent()
        val highlightInfos = highlightGroovyFile(JENKINSFILE_NAME, text)

        assertTokenHasKey(text, highlightInfos, "trim", 0, METHOD_CALL_KEY)
        assertTokenHasKey(text, highlightInfos, "toUpperCase", 0, METHOD_CALL_KEY)
    }

    @Test
    fun `fallbacks stay scoped to Jenkinsfile dynamic references`() {
        val groovyText = "return env.BRANCH_NAME"
        val groovyHighlightInfos = highlightGroovyFile("PipelineLike.groovy", groovyText)
        assertTokenDoesNotHaveKey(
            groovyText,
            groovyHighlightInfos,
            "BRANCH_NAME",
            INSTANCE_PROPERTY_REFERENCE_KEY,
        )

        val jenkinsText = "return config.BRANCH_NAME"
        val jenkinsHighlightInfos = highlightGroovyFile(JENKINSFILE_NAME, jenkinsText)
        assertTokenDoesNotHaveKey(
            jenkinsText,
            jenkinsHighlightInfos,
            "BRANCH_NAME",
            INSTANCE_PROPERTY_REFERENCE_KEY,
        )
    }

    private fun highlightGroovyFile(
        fileName: String,
        text: String,
    ): List<HighlightInfo> {
        if (fileName != JENKINSFILE_NAME) {
            myFixture.configureByText(fileName, text)
            return myFixture.doHighlighting(HighlightSeverity.INFORMATION)
        }

        val matcher = FileTypeManager.parseFromString(JENKINSFILE_NAME)
        val fileTypeManager = FileTypeManager.getInstance()
        ApplicationManager.getApplication().runWriteAction {
            fileTypeManager.associate(GroovyFileType.GROOVY_FILE_TYPE, matcher)
        }
        return try {
            myFixture.configureByText(JENKINSFILE_NAME, text)
            myFixture.doHighlighting(HighlightSeverity.INFORMATION)
        } finally {
            ApplicationManager.getApplication().runWriteAction {
                fileTypeManager.removeAssociation(GroovyFileType.GROOVY_FILE_TYPE, matcher)
            }
        }
    }

    private fun assertTokenHasKey(
        text: String,
        highlightInfos: List<HighlightInfo>,
        needle: String,
        occurrence: Int,
        expectedKey: String,
    ) {
        val startOffset = nthIndexOf(text, needle, occurrence)
        val endOffset = startOffset + needle.length
        val matchingKeys =
            highlightInfos
                .filter { info -> info.startOffset == startOffset && info.endOffset == endOffset }
                .mapNotNull { info -> info.forcedTextAttributesKey?.externalName }

        assertTrue(
            expectedKey in matchingKeys,
            "Expected $needle occurrence $occurrence to use $expectedKey, got $matchingKeys",
        )
    }

    private fun assertTokenDoesNotHaveKey(
        text: String,
        highlightInfos: List<HighlightInfo>,
        needle: String,
        rejectedKey: String,
    ) {
        val startOffset = nthIndexOf(text, needle, occurrence = 0)
        val endOffset = startOffset + needle.length
        val matchingKeys =
            highlightInfos
                .filter { info -> info.startOffset == startOffset && info.endOffset == endOffset }
                .mapNotNull { info -> info.forcedTextAttributesKey?.externalName }

        assertFalse(
            rejectedKey in matchingKeys,
            "Expected $needle to avoid $rejectedKey, got $matchingKeys",
        )
    }

    private fun nthIndexOf(
        text: String,
        needle: String,
        occurrence: Int,
    ): Int {
        var fromIndex = 0
        repeat(occurrence) {
            val next = text.indexOf(needle, fromIndex)
            check(next >= 0) { "Missing occurrence $it of $needle" }
            fromIndex = next + needle.length
        }
        val index = text.indexOf(needle, fromIndex)
        check(index >= 0) { "Missing occurrence $occurrence of $needle" }
        return index
    }

    private companion object {
        private const val JENKINSFILE_NAME = "Jenkinsfile"
        private val INSTANCE_PROPERTY_REFERENCE_KEY =
            GroovySyntaxHighlighter.INSTANCE_PROPERTY_REFERENCE.externalName
        private val METHOD_CALL_KEY = GroovySyntaxHighlighter.METHOD_CALL.externalName
    }
}
