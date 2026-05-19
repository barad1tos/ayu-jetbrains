package dev.ayuislands.accent

import com.intellij.ide.plugins.PluginManager
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
import dev.ayuislands.accent.conflict.ConflictRegistry
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
import kotlin.test.assertTrue

/**
 * Invariant lock: [AccentApplicator.apply] publishes [AccentChangedTopic.TOPIC]
 * exactly once per open usable project, on the EDT, AFTER `state.lastApplyOk = true`.
 *
 * The publish call has to live inside the same EDT Runnable that flips `lastApplyOk`
 * so subscribers (toolbar stripe + chip) only fire on a fully-painted apply.
 * Per-project try/catch (Pattern B) prevents a single throwing subscriber from
 * tearing down the apply pipeline mid-EDT and leaving the IDE in a half-applied state.
 *
 * Mirrors `AccentApplicatorTest.setUp` for the platform-static mock harness so the
 * test runs headless without booting the IntelliJ application.
 */
class AccentChangedPublishTest {
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

        // Editor colour scheme publish is fired during apply — keep it relaxed
        // (it is not the publisher under test).
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns mockk(relaxed = true)

        // The publisher under test.
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

        mockkStatic(PluginManager::class)
        every { PluginManager.getPlugin(any()) } returns null

        // ProjectManager.openProjects drives the per-project publish loop.
        project =
            mockk {
                every { isDefault } returns false
                every { isDisposed } returns false
                every { name } returns "publish-project"
                every { basePath } returns "/tmp/publish-project"
            }
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns arrayOf(project)

        // notifyOnly + AccentResolver.source are downstream of the publish loop.
        mockkObject(ComponentTreeRefresher)
        every { ComponentTreeRefresher.notifyOnly(any()) } returns Unit

        mockkObject(AccentResolver)
        every { AccentResolver.source(project) } returns AccentResolver.Source.GLOBAL
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
    fun `apply publishes AccentChangedTopic exactly once per usable open project`() {
        // One publish per usable project, with the post-resolution source (so
        // subscribers can render the "Project override" / "Global" label).
        // Payload is the AccentHex value class, not raw String — assertion
        // matches by wrapping the literal via the same factory the publisher uses.
        val accentHex = AccentHex.of("#FFCC66")!!
        AccentApplicator.apply(accentHex)

        verify(exactly = 1) {
            listener.accentChanged(project, accentHex, AccentResolver.Source.GLOBAL)
        }
    }

    @Test
    fun `apply does not publish for default or disposed projects`() {
        // The publish loop reuses the existing `project.isUsable()` filter — a
        // mid-dispose race must NOT fan out a stale project to subscribers.
        val disposed =
            mockk<Project> {
                every { isDefault } returns false
                every { isDisposed } returns true
                every { name } returns "disposed"
            }
        val default =
            mockk<Project> {
                every { isDefault } returns true
                every { isDisposed } returns false
                every { name } returns "default"
            }
        every { mockProjectManager.openProjects } returns arrayOf(disposed, default, project)
        every { AccentResolver.source(project) } returns AccentResolver.Source.PROJECT_OVERRIDE

        val accentHex = AccentHex.of("#5CCFE6")!!
        AccentApplicator.apply(accentHex)

        verify(exactly = 0) { listener.accentChanged(disposed, any(), any()) }
        verify(exactly = 0) { listener.accentChanged(default, any(), any()) }
        verify(exactly = 1) {
            listener.accentChanged(project, accentHex, AccentResolver.Source.PROJECT_OVERRIDE)
        }
    }

    @Test
    fun `apply still returns true and runs on EDT when subscriber throws`() {
        // Pattern B regression lock: a throwing subscriber must NOT propagate into
        // the apply pipeline. `lastApplyOk` must stay `true`, the call must return
        // `true`, and the WARN line must be captured so triage has a thread to pull.
        val onEdtCapture = mutableListOf<Boolean>()
        every {
            listener.accentChanged(project, any(), any())
        } answers {
            onEdtCapture += SwingUtilities.isEventDispatchThread()
            error("boom subscriber")
        }

        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains("AccentChangedTopic publish failed")) return true
                    capturedWarns += message
                    return false
                }
            }

        var applyResult = false
        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            applyResult = AccentApplicator.apply(AccentHex.of("#DFBFFF")!!)
        }

        assertTrue(applyResult, "AccentApplicator.apply must return true even when a subscriber throws")
        assertTrue(
            state.lastApplyOk,
            "state.lastApplyOk must remain true — the throw is contained by the per-project try/catch",
        )
        assertTrue(
            onEdtCapture.any { it },
            "AccentChanged publish must run on EDT (Pattern C) — captured EDT flags: $onEdtCapture",
        )
        assertTrue(
            capturedWarns.any { it.contains("AccentChangedTopic publish failed") },
            "Pattern B: subscriber exception must surface as WARN with the topic-failed marker; got: $capturedWarns",
        )
    }
}
