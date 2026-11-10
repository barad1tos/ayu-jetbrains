package dev.ayuislands

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.StudioFixtures
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AyuIslandsStartupActivityTest {
    private val activity = AyuIslandsStartupActivity()

    @Test
    fun `focused editor context initializes before any other project work`() {
        val failure = IllegalStateException("initialization stopped")
        val startup = AyuIslandsStartupActivity(initializeFocusedEditorContext = { throw failure })
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { runBlocking { startup.execute(mockk<Project>()) } },
        )
    }

    @Test
    fun `cancellation is control flow and cannot be swallowed by a startup step`() {
        val cancellation = CancellationException("closed")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                activity.runStepForTest("cancelled") { throw cancellation }
            },
        )
    }

    @Test
    fun `VM failures are logged and rethrown while optional linkage failures allow remaining work`() {
        val captured = mutableListOf<Pair<String, Throwable?>>()
        val processor = capturingProcessor(captured)
        assertFailsWith<InternalError> {
            LoggedErrorProcessor.executeWith<Throwable>(processor) {
                activity.runStepForTest("vm") { throw InternalError("fatal") }
            }
        }
        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            assertFalse(activity.runStepForTest("linkage") { throw NoClassDefFoundError("optional") })
            assertTrue(activity.runStepForTest("next") { })
        }
        assertEquals(
            listOf(
                "Ayu startup step 'vm' failed with VM error",
                "Ayu startup step 'linkage' failed with Error",
            ),
            captured.map {
                it.first
            },
        )
    }

    @Test
    fun `runtime failure identifies its step and leaves later work available`() {
        val captured = mutableListOf<Pair<String, Throwable?>>()
        LoggedErrorProcessor.executeWith<Throwable>(capturingProcessor(captured)) {
            assertFalse(activity.runStepForTest("optional") { throw IllegalStateException("unavailable") })
            assertTrue(activity.runStepForTest("remaining") {})
        }
        assertEquals("Ayu startup step 'optional' failed", captured.single().first)
    }

    @Test
    fun `only language pins and fallback request language warmup`() {
        val mappings = AccentMappingsState()

        fun needsScan() =
            activity.shouldWarmLanguageDetector(
                StudioFixtures.legacyView(AyuIslandsState(), mappings = mappings).mappings,
            )
        assertFalse(needsScan())
        mappings.languageAccents["kotlin"] = "#73D0FF"
        assertTrue(needsScan())
        mappings.languageAccents.clear()
        mappings.languageFallbackAccent = "#73D0FF"
        assertTrue(needsScan())
    }

    private fun capturingProcessor(captured: MutableList<Pair<String, Throwable?>>) =
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                throwable: Throwable?,
            ): Set<Action> {
                captured += message to throwable
                return EnumSet.noneOf(Action::class.java)
            }
        }
}
