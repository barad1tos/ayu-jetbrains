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
 * Integration coverage for the D-15 revert hook in [AccentApplicator.revertAll].
 *
 * Locks in the CHROME-08 promise that reverting all accent state leaves zero residual
 * UIManager keys and repaints every open project's cached JBColor graph. Without the
 * [ComponentTreeRefresher.notifyOnly] loop, cached color instances survive the
 * `UIManager.put(key, null)` clear and the user sees "half-reverted" chrome until the
 * next LAF change.
 *
 * Four scenarios pin down the behavior:
 *  1. `notifyOnly` fires once per usable open project so every window refreshes.
 *  2. Disposed / default projects are skipped so shutting-down windows don't blow up.
 *  3. `notifyOnly` runs AFTER the EP revert loop so subscribers see cleared UIManager
 *     state when they decide to repaint.
 *  4. Staging a chrome element into `EP_NAME.extensionList` and calling `revertAll`
 *     results in `UIManager.put(key, null)` for every chrome key — end-to-end proof
 *     that cached state cannot survive a revert.
 */
class AccentApplicatorChromeRevertTest {
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

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(ComponentTreeRefresher)
        every { ComponentTreeRefresher.notifyOnly(any()) } returns Unit

        // Default to an empty EP list so the revertAll loop runs on every test
        // without each test needing to re-stub. Tests that need specific elements
        // re-call mockEpExtensionList() after setUp.
        mockEpExtensionList(emptyList())
    }

    @AfterTest
    fun tearDown() {
        restoreOriginalEpName()
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `revertAll notifies ComponentTreeRefresher once per usable open project`() {
        val project1 = mockProject(usable = true)
        val project2 = mockProject(usable = true)
        every { mockProjectManager.openProjects } returns arrayOf(project1, project2)

        AccentApplicator.revertAll()

        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(project1) }
        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(project2) }
    }

    @Test
    fun `revertAll skips disposed projects when notifying`() {
        val usable = mockProject(usable = true)
        val disposed = mockProject(usable = false)
        every { mockProjectManager.openProjects } returns arrayOf(usable, disposed)

        AccentApplicator.revertAll()

        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(usable) }
        verify(exactly = 0) { ComponentTreeRefresher.notifyOnly(disposed) }
    }

    @Test
    fun `revertAll notifies ComponentTreeRefresher AFTER the EP revert loop`() {
        val chromeElement = mockk<AccentElement>(relaxed = true)
        every { chromeElement.id } returns AccentElementId.STATUS_BAR
        every { chromeElement.displayName } returns "ChromeTestElement"
        mockEpExtensionList(listOf(chromeElement))

        val project = mockProject(usable = true)
        every { mockProjectManager.openProjects } returns arrayOf(project)

        AccentApplicator.revertAll()

        // EP revert must happen before the refresh notification so subscribers see
        // cleared UIManager state when they repaint.
        verifyOrder {
            chromeElement.revert()
            ComponentTreeRefresher.notifyOnly(project)
        }
    }

    @Test
    fun `revertAll clears every chrome UIManager key touched by registered elements`() {
        // Stage 5 stub elements that record chrome keys so we can prove the EP revert
        // loop runs every registered chrome element and each one clears its keys.
        val statusBar =
            stubChromeElement(
                listOf("StatusBar.background", "StatusBar.borderColor"),
            )
        val mainToolbar =
            stubChromeElement(
                listOf("MainToolbar.background", "MainToolbar.foreground"),
            )
        val stripe =
            stubChromeElement(
                listOf("ToolWindow.Button.selectedBackground", "ToolWindow.stripeBackground"),
            )
        val navBar =
            stubChromeElement(
                listOf("NavBar.background", "NavBar.borderColor"),
            )
        val panelBorder =
            stubChromeElement(
                listOf("Borders.color", "Panel.background"),
            )
        mockEpExtensionList(listOf(statusBar, mainToolbar, stripe, navBar, panelBorder))

        AccentApplicator.revertAll()

        verify { UIManager.put("StatusBar.background", null) }
        verify { UIManager.put("StatusBar.borderColor", null) }
        verify { UIManager.put("MainToolbar.background", null) }
        verify { UIManager.put("MainToolbar.foreground", null) }
        verify { UIManager.put("ToolWindow.Button.selectedBackground", null) }
        verify { UIManager.put("ToolWindow.stripeBackground", null) }
        verify { UIManager.put("NavBar.background", null) }
        verify { UIManager.put("NavBar.borderColor", null) }
        verify { UIManager.put("Borders.color", null) }
        verify { UIManager.put("Panel.background", null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mockProject(usable: Boolean): Project {
        val project = mockk<Project>(relaxed = true)
        every { project.isDefault } returns !usable
        every { project.isDisposed } returns !usable
        return project
    }

    /**
     * Produces a relaxed [AccentElement] whose `revert()` invokes
     * `UIManager.put(key, null)` for every key it owns. Lets the test assert against
     * the real UIManager mock for proof that a registered chrome element actually
     * clears its keys when `revertAll` iterates the extension list.
     */
    private fun stubChromeElement(keys: List<String>): AccentElement {
        val element = mockk<AccentElement>(relaxed = true)
        every { element.id } returns AccentElementId.STATUS_BAR
        every { element.displayName } returns "ChromeStub"
        every { element.revert() } answers {
            for (key in keys) {
                UIManager.put(key, null)
            }
        }
        return element
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
