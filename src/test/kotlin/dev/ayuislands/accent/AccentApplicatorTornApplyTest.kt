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
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.ui.ComponentTreeRefresher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Window
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `applyFromHexString still reports the dispatch as accepted on a torn apply`() {
        // The Boolean contract is validation + scheduling, not paint completion —
        // torn-state signaling belongs to lastApplyOk / trustedCachedAccent.
        val accepted = AccentApplicator.applyFromHexString("#FFCC66")
        assertTrue(accepted)
        assertFalse(state.lastApplyOk)
    }
}
