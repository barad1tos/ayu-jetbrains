package dev.ayuislands.accent

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.messages.MessageBus
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.ui.ComponentTreeRefresher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import java.awt.Color
import java.awt.Window
import java.beans.PropertyChangeListener
import java.util.concurrent.CancellationException
import java.util.function.BiConsumer
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end lock on the torn-apply contract: a mid-step throw inside
 * [AccentApplicator.apply] is contained (never reaches the caller), aborts the
 * remaining plan (no component-tree notify, no accent publish, `lastApplyOk`
 * stays false), keeps the anti-flicker hex persisted, and leaves
 * [AyuIslandsState.trustedCachedAccent] refusing the torn cache. This is the
 * composed behavior the plan/runner unit tests cannot see — it pins the wiring
 * between [applyPlanFor], the worker map, and [AccentApplyPlanRunner].
 *
 * Mirrors `AccentChangedPublishTest`'s platform-static mock harness so the
 * test runs headless without booting the IntelliJ application.
 */
class AccentApplicatorTornApplyTest {
    private val mockScheme = mockk<EditorColorsScheme>(relaxed = true)
    private val mockColorsManager = mockk<EditorColorsManager>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)
    private lateinit var listener: AccentChangeListener
    private lateinit var project: Project
    private var originalEpName: ExtensionPointName<AccentElement>? = null

    @BeforeTest
    fun setUp() {
        saveOriginalEpName()
        mockEpExtensionList(emptyList())

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        mockkStatic(UIManager::class)

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager
        every { mockColorsManager.globalScheme } returns mockScheme
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns TextAttributes()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus
        every { mockApplication.getService(ProjectAccentSwapService::class.java) } returns ProjectAccentSwapService()
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns mockk(relaxed = true)
        listener = mockk(relaxed = true)
        every { mockMessageBus.syncPublisher(AccentChangedTopic.TOPIC) } returns listener

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(ConflictRegistry)
        every { ConflictRegistry.getConflictFor(any()) } returns null

        mockkStatic(Window::class)
        every { Window.getWindows() } returns emptyArray()

        mockkObject(AyuPlugin)
        every { AyuPlugin.findLoadedPlugin(any()) } returns null

        project =
            mockk {
                every { isDefault } returns false
                every { isDisposed } returns false
                every { name } returns "torn-project"
                every { basePath } returns "/tmp/torn-project"
            }
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns arrayOf(project)

        mockkObject(ComponentTreeRefresher)
        every { ComponentTreeRefresher.notifyOnly(any()) } returns Unit

        // The synthetic tear: SyncIndentRainbow is the third apply step, so
        // ApplyAlwaysOnUiKeys/ApplyElements ran, everything after must not.
        mockkObject(IndentRainbowSync)
        every {
            IndentRainbowSync.apply(any<AccentContext>(), any())
        } throws RuntimeException("synthetic tear")
    }

    @AfterTest
    fun tearDown() {
        restoreOriginalEpName()
        unmockkAll()
    }

    private fun saveOriginalEpName() {
        if (originalEpName != null) return
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        originalEpName = epField.get(null) as ExtensionPointName<AccentElement>
    }

    private fun restoreOriginalEpName() {
        val original = originalEpName ?: return
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        unsafeWriteStaticField(epField, original)
        originalEpName = null
    }

    private fun mockEpExtensionList(elements: List<AccentElement>) {
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        val mockEp = mockk<ExtensionPointName<AccentElement>>(relaxed = true)
        every { mockEp.extensionList } returns elements
        unsafeWriteStaticField(epField, mockEp)
    }

    @Suppress("DEPRECATION")
    private fun unsafeWriteStaticField(
        field: java.lang.reflect.Field,
        value: Any?,
    ) {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val offset = unsafe.staticFieldOffset(field)
        unsafe.putObject(field.declaringClass, offset, value)
    }

    @Test
    fun `mid-step throw is contained, aborts the tail, and leaves the torn markers`() {
        val accentHex = requireNotNull(AccentHex.of("#FFCC66"))
        val warns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    t: Throwable?,
                ): Boolean {
                    warns += message
                    return true
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            AccentApplicator.apply(accentHex) // MUST NOT throw — tear is contained
        }

        // Anti-flicker cache persisted BEFORE the plan ran, and survives the tear.
        assertEquals("#FFCC66", state.lastAppliedAccentHex)
        // MarkApplyClean was skipped, so the two-phase flag marks the tear...
        assertFalse(state.lastApplyOk)
        // ...and the trust boundary refuses the torn cache.
        assertNull(state.trustedCachedAccent())
        // Later steps aborted: no component-tree notify, no accent publish.
        verify(exactly = 0) { ComponentTreeRefresher.notifyOnly(any()) }
        verify(exactly = 0) { listener.accentChanged(any(), any(), any()) }
        // The WARN names the failing step for triage.
        assertTrue(
            warns.any { it.startsWith("Accent apply torn at SyncIndentRainbow") },
            "expected torn-apply WARN naming the failing step, got: $warns",
        )
    }

    @Test
    fun `failed accent restores the previous explicit UI color`() {
        withRealUiDefaults {
            val previous = Color(0x11, 0x22, 0x33)
            val saved = UIManager.put("Component.focusColor", previous)
            try {
                assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                assertEquals(expected = previous, actual = UIManager.getColor("Component.focusColor"))
            } finally {
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `failed accent preserves a later external UI color edit`() {
        withRealUiDefaults {
            val manual = Color(0x77, 0x88, 0x99)
            val saved = UIManager.put("Component.focusColor", Color(0x11, 0x22, 0x33))
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                UIManager.put("Component.focusColor", manual)
                error("integration failed after external color edit")
            }
            try {
                assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                assertEquals(expected = manual, actual = UIManager.getColor("Component.focusColor"))
            } finally {
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    private fun withRealUiDefaults(block: () -> Unit) {
        unmockkStatic(UIManager::class)
        unmockkStatic(SwingUtilities::class)
        SwingUtilities.invokeAndWait {
            val before = developerDefaults()
            try {
                block()
            } finally {
                val after = developerDefaults()
                for (key in before.keys + after.keys) {
                    if (before[key] !== after[key]) UIManager.put(key, before[key])
                }
            }
        }
    }

    private fun developerDefaults(): Map<Any, Any> =
        buildMap {
            // Read only raw developer entries, without evaluating lazy providers.
            val capture = BiConsumer<Any, Any> { key, value -> put(key, value) }
            UIManager.getDefaults().forEach(capture)
        }

    @Test
    fun `cancelled accent restores owned UI color before propagating cancellation`() {
        withRealUiDefaults {
            val previous = Color(0x11, 0x22, 0x33)
            val saved = UIManager.put("Component.focusColor", previous)
            val cancellation = CancellationException("accent cancelled during integration")
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } throws cancellation
            try {
                val thrown = assertFailsWith<CancellationException> { AccentApplicator.applyFromHexString("#445566") }
                assertSame(cancellation, thrown)
                assertEquals(expected = previous, actual = UIManager.getColor("Component.focusColor"))
                assertFalse(state.lastApplyOk)
            } finally {
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `recovery linkage error is reported with the original apply failure`() {
        withRealUiDefaults {
            val saved = UIManager.put("Component.focusColor", Color.BLUE)
            val recoveryFailure = NoClassDefFoundError("unloaded UI listener")
            val defaults = UIManager.getDefaults()
            val recoveryListener =
                PropertyChangeListener { event ->
                    if (event.propertyName == "Component.focusColor" &&
                        event.newValue === Color.BLUE
                    ) {
                        throw recoveryFailure
                    }
                }
            defaults.addPropertyChangeListener(recoveryListener)
            try {
                val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                assertEquals(2, outcome.failures.size)
                assertEquals(
                    "synthetic tear",
                    outcome.failures
                        .single { it.step == AccentApplyStep.SyncIndentRainbow }
                        .error.message,
                )
                assertSame(
                    recoveryFailure,
                    outcome.failures.single { it.step == AccentApplyStep.ApplyAlwaysOnUiKeys }.error,
                )
                assertFalse(state.lastApplyOk)
            } finally {
                defaults.removePropertyChangeListener(recoveryListener)
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `successful accent retains the newly applied UI color`() {
        withSuccessfulVisualApply {
            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#445566"))
            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = UIManager.getColor("Component.focusColor"))
            assertTrue(state.lastApplyOk)
        }
    }

    @Test
    fun `publication failure retains successfully applied UI color`() {
        withSuccessfulVisualApply {
            every { listener.accentChanged(any(), any(), any()) } throws IllegalStateException("subscriber failed")
            val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
            assertTrue(outcome.visualsApplied)
            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = UIManager.getColor("Component.focusColor"))
            assertTrue(state.lastApplyOk)
        }
    }

    @Test
    fun `publication cancellation retains successfully applied UI color`() {
        withSuccessfulVisualApply {
            val cancellation = CancellationException("subscriber cancelled")
            every { listener.accentChanged(any(), any(), any()) } throws cancellation
            val thrown = assertFailsWith<CancellationException> { AccentApplicator.applyFromHexString("#445566") }
            assertSame(cancellation, thrown)
            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = UIManager.getColor("Component.focusColor"))
            assertTrue(state.lastApplyOk)
        }
    }

    private fun withSuccessfulVisualApply(block: () -> Unit) {
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        mockkObject(AccentResolver)
        every { AccentResolver.source(any()) } returns AccentResolver.Source.GLOBAL
        withRealUiDefaults {
            val saved = UIManager.put("Component.focusColor", Color.BLUE)
            try {
                block()
            } finally {
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `applyFromHexString returns the failed invocation`() {
        val outcome: Any = AccentApplicator.applyFromHexString("#FFCC66")
        val torn = assertIs<AccentApplyOutcome.Torn>(outcome)
        assertEquals(AccentApplyStep.SyncIndentRainbow, torn.failures.single().step)
        assertEquals(
            "synthetic tear",
            torn.failures
                .single()
                .error.message,
        )
        assertFalse(state.lastApplyOk)
    }
}
