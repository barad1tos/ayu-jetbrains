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
import com.intellij.util.messages.MessageBus
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.ui.ComponentTreeRefresher
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Integration coverage for the Gap 4 D-15 mirror hook in [AccentApplicator.apply].
 *
 * Locks in the architectural symmetry promise: `apply` must republish
 * [ComponentTreeRefresher.notifyOnly] for every usable open project so existing
 * subscribers (EditorScrollbarManager, ProjectViewScrollbarManager) re-read
 * UIManager state the same way they already do on `revertAll`. Without the apply-path
 * hook, scrollbars keep stale cached JBColor references after an accent apply until
 * the next LAF cycle, while revert works correctly — asymmetric and user-visible.
 *
 * Three scenarios pin down the behavior:
 *  1. `notifyOnly` fires once per usable open project after `apply(hex)`.
 *  2. Disposed / default projects are skipped by the same `isUsable()` guard as
 *     `revertAll` — shutting-down windows must not crash the apply path.
 *  3. `notifyOnly` runs AFTER `applyElements` so subscribers see the freshly-written
 *     UIManager state when they decide to repaint.
 *
 * Per 40-12 research §A (verdict=UNSAFE), this hook MUST NOT publish
 * `LafManagerListener.TOPIC` — that broadcast would re-enter the LAF cycle and
 * recurse. Apply-path symmetry uses notifyOnly only. Banned-API regression guards
 * live in [AccentApplicatorBannedApiGuardTest].
 */
class AccentApplicatorChromeApplyRefreshTest {
    private val mockScheme = mockk<EditorColorsScheme>(relaxed = true)
    private val mockColorsManager = mockk<EditorColorsManager>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)

    private var originalEpName: ExtensionPointName<AccentElement>? = null

    @BeforeTest
    fun setUp() {
        saveOriginalEpName()

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

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        // AyuVariant.detect() calls LafManager.getInstance() which can't be
        // instantiated in a unit-test classloader. Stub the companion to return
        // MIRAGE so apply() can run through its EDT path without the platform.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(ComponentTreeRefresher)
        every { ComponentTreeRefresher.notifyOnly(any()) } returns Unit

        // Default to an empty EP list so apply runs through its full path without
        // element callbacks interfering. Tests that need specific elements re-call
        // mockEpExtensionList() after setUp.
        mockEpExtensionList(emptyList())
    }

    @AfterTest
    fun tearDown() {
        restoreOriginalEpName()
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `apply notifies ComponentTreeRefresher once per usable open project`() {
        val project1 = mockProject(usable = true)
        val project2 = mockProject(usable = true)
        every { mockProjectManager.openProjects } returns arrayOf(project1, project2)

        AccentApplicator.applyFromHexString("#FFCC66")

        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(project1) }
        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(project2) }
    }

    @Test
    fun `apply skips disposed projects when notifying`() {
        val usable = mockProject(usable = true)
        val disposed = mockProject(usable = false)
        every { mockProjectManager.openProjects } returns arrayOf(usable, disposed)

        AccentApplicator.applyFromHexString("#FFCC66")

        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(usable) }
        verify(exactly = 0) { ComponentTreeRefresher.notifyOnly(disposed) }
    }

    @Test
    fun `apply notifies ComponentTreeRefresher AFTER the EP apply loop`() {
        val chromeElement = mockk<AccentElement>(relaxed = true)
        every { chromeElement.id } returns AccentElementId.STATUS_BAR
        every { chromeElement.displayName } returns "ChromeTestElement"
        mockEpExtensionList(listOf(chromeElement))

        // STATUS_BAR chrome toggle defaults to false, which would route apply()
        // through the `neutralizeOrRevert` branch and never call `element.apply()`.
        // Enable it so the EP apply loop exercises the apply() path and we can
        // verify the ordering against notifyOnly.
        state.chromeStatusBar = true

        val project = mockProject(usable = true)
        every { mockProjectManager.openProjects } returns arrayOf(project)

        AccentApplicator.applyFromHexString("#FFCC66")

        // EP apply must happen before the refresh notification so subscribers see
        // freshly-written UIManager state when they repaint.
        verifyOrder {
            chromeElement.apply(any())
            ComponentTreeRefresher.notifyOnly(project)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mockProject(usable: Boolean): Project {
        val project = mockk<Project>(relaxed = true)
        every { project.isDefault } returns !usable
        every { project.isDisposed } returns !usable
        return project
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
}
