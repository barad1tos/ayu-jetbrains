package dev.ayuislands.accent

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.messages.MessageBus
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.elements.BracketMatchElement
import dev.ayuislands.accent.elements.PanelBorderElement
import dev.ayuislands.accent.elements.ProgressBarElement
import dev.ayuislands.accent.elements.SearchResultsElement
import dev.ayuislands.accent.elements.StatusBarElement
import dev.ayuislands.accent.elements.ToolWindowStripeElement
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
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
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.function.BiConsumer
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
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
    fun `abort restores progress colors from the previous accent`() {
        state.progressBar = true
        mockEpExtensionList(listOf(ProgressBarElement()))
        withRealUiDefaults {
            val previous = Color(0x11, 0x22, 0x33)
            val keys = listOf("ProgressBar.foreground", "ProgressBar.progressCounterBackground")
            for (key in keys) UIManager.put(key, previous)
            var during = emptyList<Color?>()
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                during = keys.map(UIManager::getColor)
                error("integration failed after progress tint")
            }

            val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

            assertEquals(expected = AccentApplyStep.SyncIndentRainbow, actual = outcome.failures.single().step)
            assertEquals(expected = keys.map { Color(0x44, 0x55, 0x66) }, actual = during)
            for (key in keys) {
                assertSame(expected = previous, actual = UIManager.getColor(key), message = key)
            }
        }
    }

    @Test
    fun `integration failure restores a real progress elements previous editor accent and metadata`() {
        val key = ColorKey.find("PROGRESS_BAR_TRACK")
        val colors = mutableMapOf<ColorKey, Color?>(key to Color.BLUE)
        val metadata = Properties()
        every { mockScheme.name } returns "Ayu Islands Mirage"
        every { mockScheme.metaProperties } returns metadata
        every { mockColorsManager.allSchemes } returns arrayOf(mockScheme)
        every { mockScheme.getColor(any<ColorKey>()) } answers { colors[firstArg<ColorKey>()] }
        every { mockScheme.setColor(any(), any()) } answers {
            colors[firstArg<ColorKey>()] = secondArg<Color?>()
        }
        val editorListener = mockk<EditorColorsListener>(relaxed = true)
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns editorListener
        every { mockApplication.runWriteAction(any<ThrowableComputable<Any?, RuntimeException>>()) } answers {
            firstArg<ThrowableComputable<Any?, RuntimeException>>().compute()
        }
        val notifications = mutableListOf<Color?>()
        every { editorListener.globalSchemeChange(null) } answers { notifications += colors[key] }
        mockEpExtensionList(listOf(ProgressBarElement()))
        AyuEditorSchemeScope.resetClaims()
        try {
            withRealUiDefaults {
                AyuEditorSchemeScope.writeColor(
                    EditorSchemeOwner.Element(AccentElementId.PROGRESS_BAR),
                    key,
                    Color.ORANGE,
                )
                val savedMetadata = Properties().apply { putAll(metadata) }
                var during: Color? = null
                val failure = IllegalStateException("integration failed after progress editor tint")
                every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                    during = colors[key]
                    throw failure
                }

                val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

                assertEquals(expected = Color(0x44, 0x55, 0x66), actual = during)
                assertSame(expected = failure, actual = outcome.failures.single().error)
                assertSame(expected = Color.ORANGE, actual = colors[key])
                assertEquals(expected = savedMetadata, actual = metadata)
                assertEquals(expected = listOf(Color.ORANGE), actual = notifications)
                verify(exactly = 0) { listener.accentChanged(any(), any(), any()) }
            }
        } finally {
            AyuEditorSchemeScope.resetClaims()
        }
    }

    @Test
    fun `contained progress failure restores the previous editor accent instead of its original baseline`() {
        val key = ColorKey.find("PROGRESS_BAR_TRACK")
        val colors = mutableMapOf<ColorKey, Color?>(key to Color.BLUE)
        val metadata = Properties()
        every { mockScheme.name } returns "Ayu Islands Mirage"
        every { mockScheme.metaProperties } returns metadata
        every { mockColorsManager.allSchemes } returns arrayOf(mockScheme)
        every { mockScheme.getColor(any<ColorKey>()) } answers { colors[firstArg<ColorKey>()] }
        every { mockScheme.setColor(any(), any()) } answers { colors[firstArg<ColorKey>()] = secondArg<Color?>() }
        mockEpExtensionList(listOf(ProgressBarElement()))
        AyuEditorSchemeScope.resetClaims()
        try {
            withRealUiDefaults {
                val previous = Color.ORANGE
                AyuEditorSchemeScope.writeColor(EditorSchemeOwner.Element(AccentElementId.PROGRESS_BAR), key, previous)
                val savedMetadata = Properties().apply { putAll(metadata) }
                UIManager.put("ProgressBar.foreground", previous)
                val attempted = Color(0x44, 0x55, 0x66)
                val defaults = UIManager.getDefaults()
                val failure = IllegalStateException("contained progress listener failure")
                val failingListener =
                    PropertyChangeListener { event ->
                        if (event.propertyName == "ProgressBar.foreground" && event.newValue == attempted) throw failure
                    }
                defaults.addPropertyChangeListener(failingListener)
                var afterContainedFailure: Color? = null
                every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                    afterContainedFailure = colors[key]
                    error("later outer abort")
                }
                try {
                    val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                    assertEquals(expected = previous, actual = afterContainedFailure)
                    assertTrue(outcome.failures.any { it.step == AccentApplyStep.ApplyElements })
                    assertEquals(expected = previous, actual = colors[key])
                    assertEquals(expected = savedMetadata, actual = metadata)
                } finally {
                    defaults.removePropertyChangeListener(failingListener)
                }
            }
        } finally {
            AyuEditorSchemeScope.resetClaims()
        }
    }

    @Test
    fun `contained bracket failure restores the previous accent attributes without reverting their owner`() {
        val key = TextAttributesKey.find("MATCHED_BRACE_ATTRIBUTES")
        var attributes: TextAttributes? = TextAttributes().apply { foregroundColor = Color.BLUE }
        val metadata = Properties()
        every { mockScheme.name } returns "Ayu Islands Mirage"
        every { mockScheme.metaProperties } returns metadata
        every { mockColorsManager.allSchemes } returns arrayOf(mockScheme)
        every { mockScheme.getAttributes(key) } answers { attributes }
        every { mockScheme.setAttributes(key, any()) } answers { attributes = secondArg<TextAttributes?>() }
        mockEpExtensionList(listOf(BracketMatchElement()))
        AyuEditorSchemeScope.resetClaims()
        try {
            withSuccessfulVisualApply {
                val previous = TextAttributes().apply { foregroundColor = Color.ORANGE }
                AyuEditorSchemeScope.writeAttributes(
                    EditorSchemeOwner.Element(AccentElementId.BRACKET_MATCH),
                    key,
                    previous,
                )
                val savedMetadata = Properties().apply { putAll(metadata) }
                val attempted = Color(0x44, 0x55, 0x66)
                every { mockScheme.setAttributes(key, any()) } answers {
                    attributes = secondArg<TextAttributes?>()
                    if (attributes?.foregroundColor == attempted) error("bracket setter failed after write")
                }

                val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

                assertTrue(outcome.failures.any { it.step == AccentApplyStep.ApplyElements })
                assertEquals(previous, attributes)
                assertEquals(savedMetadata, metadata)
            }
        } finally {
            AyuEditorSchemeScope.resetClaims()
        }
    }

    @Test
    fun `later integration failure restores chrome colors overlays and the live peer`() {
        state.chromeStatusBar = true
        state.chromeToolWindowStripe = true
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockEpExtensionList(listOf(StatusBarElement(), ToolWindowStripeElement()))
        val peer = JPanel().apply { background = Color.BLUE }
        val statusBar = mockk<StatusBar> { every { component } returns peer }
        val windowManager = mockk<WindowManager> { every { getStatusBar(project) } returns statusBar }
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        val previous =
            mapOf(
                "StatusBar.background" to Color.BLUE,
                "StatusBar.borderColor" to Color.CYAN,
                "StatusBar.Breadcrumbs.foreground" to Color.MAGENTA,
                "StatusBar.Breadcrumbs.hoverBackground" to Color.PINK,
                "ToolWindow.Stripe.background" to Color.BLUE,
                "ToolWindow.Stripe.borderColor" to Color.CYAN,
                "ToolWindow.Button.selectedBackground" to Color.GREEN,
                "ToolWindow.Button.selectedForeground" to Color.MAGENTA,
                "ToolWindow.Stripe.foreground" to Color.MAGENTA,
            )
        withRealUiDefaults {
            previous.forEach { (key, value) ->
                ChromeBaseColors.forgetPluginTint(key)
                UIManager.put(key, value)
            }
            ChromeBaseColors.refresh()
            val observed = mutableMapOf<String, Color?>()
            val failure = IllegalStateException("integration failed after chrome tint")
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                for (key in previous.keys) observed[key] = UIManager.getColor(key)
                throw failure
            }

            val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

            assertSame(expected = failure, actual = outcome.failures.single().error)
            assertEquals(expected = previous.keys, actual = observed.keys)
            assertNotSame(illegal = Color.BLUE, actual = observed.getValue("StatusBar.background"))
            assertNotSame(illegal = Color.MAGENTA, actual = observed.getValue("ToolWindow.Stripe.foreground"))
            assertNull(observed.getValue("StatusBar.Breadcrumbs.hoverBackground"))
            previous.forEach { (key, value) ->
                assertSame(expected = value, actual = readRawUiDefault(UIManager.getDefaults(), key), message = key)
            }
            assertSame(expected = Color.BLUE, actual = peer.background)
        }
        ChromeBaseColors.refresh()
    }

    @Test
    fun `aborted external disable keeps ownership until the next real disable`() =
        withExternalStatusBar {
            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#445566"))
            val previousTint = readRawUiDefault(UIManager.getDefaults(), "StatusBar.background")
            state.externalThemeChromeTintEnabled = false
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } throws IllegalStateException("later abort")

            assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#AABBCC"))
            assertSame(
                expected = previousTint,
                actual = readRawUiDefault(UIManager.getDefaults(), "StatusBar.background"),
            )
            assertFalse(state.externalThemeChromeTintEnabled)
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped

            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#AABBCC"))

            assertNull(readRawUiDefault(UIManager.getDefaults(), "StatusBar.background"))
            assertSame(expected = Color.BLUE, actual = UIManager.getColor("StatusBar.background"))
        }

    @Test
    fun `aborted first external tint cannot claim a later native color edit`() =
        withExternalStatusBar {
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } throws IllegalStateException("later abort")
            assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
            assertNull(readRawUiDefault(UIManager.getDefaults(), "StatusBar.background"))
            assertTrue(state.externalThemeChromeTintEnabled)
            UIManager.put("StatusBar.background", Color.GREEN)
            state.externalThemeChromeTintEnabled = false
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped

            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#AABBCC"))

            assertSame(
                expected = Color.GREEN,
                actual = readRawUiDefault(UIManager.getDefaults(), "StatusBar.background"),
            )
        }

    @Test
    fun `external ownership waits for pending peer repaint before another disable`() {
        var failRepaint = false
        val peer =
            object : JPanel() {
                override fun repaint() {
                    check(!failRepaint) { "peer recovery repaint failed" }
                    super.repaint()
                }
            }.apply { background = Color.BLUE }
        withExternalStatusBar(peer) {
            try {
                every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                    failRepaint = true
                    error("later integration abort")
                }
                assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                assertSame(expected = Color.BLUE, actual = peer.background)
                failRepaint = false
                UIManager.put("StatusBar.background", Color.GREEN)
                state.externalThemeChromeTintEnabled = false
                every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped

                assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#AABBCC"))

                assertSame(
                    expected = Color.GREEN,
                    actual = readRawUiDefault(UIManager.getDefaults(), "StatusBar.background"),
                )
            } finally {
                failRepaint = false
            }
        }
    }

    @Test
    fun `pending status peer recovery cannot block independent panel borders`() = assertPanelRevert()

    @Test
    fun `cancelled explicit revert still releases independent panel ownership`() {
        val warnings = mutableListOf<Throwable>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    t: Throwable?,
                ): Boolean {
                    if (t != null) warnings += t
                    return false
                }
            }
        for (cancelled in listOf(ProcessCanceledException(), CancellationException("cancelled peer recovery"))) {
            LoggedErrorProcessor.executeWith<RuntimeException>(processor) { assertPanelRevert(cancelled) }
            assertFalse(warnings.any { it === cancelled }, "cancellation is control flow, not a logged failure")
        }
    }

    private fun assertPanelRevert(cancelled: RuntimeException? = null) {
        var repaintFailure: RuntimeException? = null
        val peer =
            object : JPanel() {
                override fun repaint() {
                    repaintFailure?.let { throw it }
                    super.repaint()
                }
            }.apply { background = Color.BLUE }
        withExternalStatusBar(peer) {
            state.chromePanelBorder = true
            val border = PanelBorderElement()
            val elements =
                if (cancelled == null) listOf(border, StatusBarElement()) else listOf(StatusBarElement(), border)
            mockEpExtensionList(elements)
            val defaults = UIManager.getLookAndFeelDefaults()
            val borderKey = "ToolWindow.borderColor"
            val previousBorder = readRawUiDefault(defaults, borderKey)
            defaults[borderKey] = Color.CYAN
            UIManager.put(borderKey, null)
            ChromeBaseColors.refresh()
            try {
                every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                    repaintFailure = IllegalStateException("status repaint still interrupted")
                    error("abort both chrome elements")
                }
                assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                assertNull(readRawUiDefault(UIManager.getDefaults(), borderKey))
                val failures = mutableListOf<AccentApplyStepFailure>()

                ExternalChromeOwnership.apply(listOf(border), state, Color.GREEN, true, failures::add)

                assertTrue(failures.isEmpty())
                assertTrue(readRawUiDefault(UIManager.getDefaults(), borderKey) is Color)
                assertSame(Color.BLUE, peer.background)

                if (cancelled == null) {
                    AccentApplicator.revertNow()
                } else {
                    repaintFailure = cancelled
                    assertSame(cancelled, assertFailsWith<RuntimeException> { AccentApplicator.revertNow() })
                    assertNull(readRawUiDefault(UIManager.getDefaults(), borderKey))
                }
                UIManager.put(borderKey, Color.MAGENTA)
                repaintFailure = null
                state.externalThemeChromeTintEnabled = false
                every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped

                assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#AABBCC"))
                assertSame(Color.MAGENTA, readRawUiDefault(UIManager.getDefaults(), borderKey))
            } finally {
                repaintFailure = null
                state.externalThemeChromeTintEnabled = false
                every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
                AccentApplicator.applyFromHexString("#AABBCC")
                defaults[borderKey] = previousBorder
                ChromeBaseColors.refresh()
            }
        }
    }

    private fun withExternalStatusBar(
        peer: JPanel? = null,
        block: () -> Unit,
    ) {
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromeStatusBar = true
        state.cgpIntegrationEnabled = false
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { AyuVariant.detect() } returns null
        mockEpExtensionList(listOf(StatusBarElement()))
        val statusBar =
            peer?.let { statusComponent ->
                mockk<StatusBar> { every { component } returns statusComponent }
            }
        val windowManager = mockk<WindowManager> { every { getStatusBar(project) } returns statusBar }
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        withSuccessfulVisualApply {
            ExternalChromeOwnership.resetForTests()
            val defaults = UIManager.getLookAndFeelDefaults()
            val native = mapOf("StatusBar.background" to Color.BLUE, "StatusBar.borderColor" to Color.CYAN)
            val saved = native.keys.associateWith { readRawUiDefault(defaults, it) }
            native.forEach { (key, value) ->
                UIManager.put(key, null)
                defaults[key] = value
                ChromeBaseColors.forgetPluginTint(key)
            }
            ChromeBaseColors.refresh()
            try {
                block()
            } finally {
                ExternalChromeOwnership.resetForTests()
                saved.forEach { (key, value) -> defaults[key] = value }
                native.keys.forEach(ChromeBaseColors::forgetPluginTint)
                ChromeBaseColors.refresh()
            }
        }
    }

    @Test
    fun `abort restores the previous selection after a disabled element removed it`() {
        state.searchResults = false
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockEpExtensionList(listOf(SearchResultsElement()))
        withRealUiDefaults {
            val previous = Color(0x11, 0x22, 0x33)
            val failure = IllegalStateException("disabled selection abort")
            UIManager.put("List.selectionBackground", previous)
            var during: Any? = previous
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                during = readRawUiDefault(UIManager.getDefaults(), "List.selectionBackground")
                throw failure
            }

            val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

            assertSame(expected = failure, actual = outcome.failures.single().error)
            assertNull(during)
            assertSame(
                expected = previous,
                actual = readRawUiDefault(UIManager.getDefaults(), "List.selectionBackground"),
            )
            assertFalse(state.searchResults)
        }
    }

    @Test
    fun `cancelled apply restores the previous progress accent`() {
        mockEpExtensionList(listOf(ProgressBarElement()))
        val cancellation = CancellationException("cancelled after progress tint")
        var during: Color? = null
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
            during = UIManager.getColor("ProgressBar.foreground")
            throw cancellation
        }
        withRealUiDefaults {
            val previous = Color(0x11, 0x22, 0x33)
            UIManager.put("ProgressBar.foreground", previous)

            val thrown = assertFailsWith<CancellationException> { AccentApplicator.applyFromHexString("#445566") }

            assertSame(expected = cancellation, actual = thrown)
            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = during)
            assertSame(expected = previous, actual = UIManager.getColor("ProgressBar.foreground"))
        }
    }

    @Test
    fun `abort preserves another plugin's progress color`() {
        mockEpExtensionList(listOf(ProgressBarElement()))
        withRealUiDefaults {
            val manual = Color(0x77, 0x88, 0x99)
            var during: Color? = null
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                during = UIManager.getColor("ProgressBar.foreground")
                UIManager.put("ProgressBar.foreground", manual)
                error("integration failed after manual progress edit")
            }

            assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = during)
            assertSame(expected = manual, actual = UIManager.getColor("ProgressBar.foreground"))
        }
    }

    @Test
    fun `abort preserves a lazy progress default without resolving it`() {
        mockEpExtensionList(listOf(ProgressBarElement()))
        withRealUiDefaults {
            var evaluations = 0
            val lazy =
                javax.swing.UIDefaults.LazyValue {
                    evaluations++
                    Color(0x11, 0x22, 0x33)
                }
            UIManager.put("ProgressBar.foreground", lazy)
            var during: Any? = null
            every { IndentRainbowSync.apply(any<AccentContext>(), any()) } answers {
                during = readRawUiDefault(UIManager.getDefaults(), "ProgressBar.foreground")
                error("integration failed after replacing a lazy progress default")
            }

            assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = during)
            assertSame(expected = lazy, actual = readRawUiDefault(UIManager.getDefaults(), "ProgressBar.foreground"))
            assertEquals(expected = 0, actual = evaluations)
        }
    }

    @Test
    fun `contained selection failure restores its previous color while other surfaces commit`() {
        mockEpExtensionList(listOf(SearchResultsElement()))
        withSuccessfulVisualApply {
            val previous = Color(0x11, 0x22, 0x33)
            UIManager.put("List.selectionBackground", previous)
            val defaults = UIManager.getDefaults()
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == "List.selectionBackground" && event.newValue !== previous) {
                        error("selection listener failed")
                    }
                }
            defaults.addPropertyChangeListener(listener)
            try {
                val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

                assertEquals(expected = AccentApplyStep.ApplyElements, actual = outcome.failures.single().step)
                assertSame(expected = previous, actual = readRawUiDefault(defaults, "List.selectionBackground"))
                assertEquals(expected = Color(0x44, 0x55, 0x66), actual = UIManager.getColor("Component.focusColor"))
            } finally {
                defaults.removePropertyChangeListener(listener)
            }
        }
    }

    @Test
    fun `selection child rollback cannot cancel a pending lifecycle interruption`() {
        mockEpExtensionList(listOf(SearchResultsElement()))
        val posted = ArrayDeque<Runnable>()
        every {
            mockApplication.invokeLater(any<Runnable>(), any<com.intellij.openapi.application.ModalityState>())
        } answers { posted.addLast(firstArg()) }
        withSuccessfulVisualApply {
            val previous = Color(0x11, 0x22, 0x33)
            UIManager.put("Component.focusColor", previous)
            UIManager.put("List.selectionBackground", previous)
            val defaults = UIManager.getDefaults()
            var observed: Color? = null
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == "List.selectionBackground" && event.newValue !== previous) {
                        AccentLifecycle.request { observed = UIManager.getColor("Component.focusColor") }
                    }
                }
            defaults.addPropertyChangeListener(listener)
            try {
                assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

                assertSame(expected = previous, actual = UIManager.getColor("Component.focusColor"))
            } finally {
                defaults.removePropertyChangeListener(listener)
                while (posted.isNotEmpty()) posted.removeFirst().run()
            }
            assertSame(expected = previous, actual = observed)
        }
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
                uiDefaultsOwner.restore()
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
                val recovery = outcome.failures.single { it.step == AccentApplyStep.ApplyAlwaysOnUiKeys }.error
                assertEquals("Accent restoration after torn apply failed", recovery.message)
                assertSame(recoveryFailure, recovery.cause)
                assertFalse(state.lastApplyOk)
            } finally {
                defaults.removePropertyChangeListener(recoveryListener)
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `recovery cancellation keeps the original apply failure`() {
        withRealUiDefaults {
            val saved = UIManager.put("Component.focusColor", Color.BLUE)
            val cancellation = CancellationException("recovery cancelled")
            val defaults = UIManager.getDefaults()
            val recoveryListener =
                PropertyChangeListener { event ->
                    if (event.propertyName == "Component.focusColor" &&
                        event.newValue === Color.BLUE
                    ) {
                        throw cancellation
                    }
                }
            defaults.addPropertyChangeListener(recoveryListener)
            try {
                val thrown = assertFailsWith<CancellationException> { AccentApplicator.applyFromHexString("#445566") }
                assertSame(cancellation, thrown)
                assertEquals(listOf("synthetic tear"), thrown.suppressed.map { it.message })
                assertFalse(state.lastApplyOk)
            } finally {
                defaults.removePropertyChangeListener(recoveryListener)
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `failed accent repaints windows after restoring UI colors`() {
        val window = mockk<Window>(relaxed = true) { every { isDisplayable } returns true }
        every { Window.getWindows() } returns arrayOf(window)
        withRealUiDefaults {
            val previous = Color(0x11, 0x22, 0x33)
            val saved = UIManager.put("Component.focusColor", previous)
            try {
                assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                assertEquals(expected = previous, actual = UIManager.getColor("Component.focusColor"))
                // The tear aborts the plan before RepaintWindows, so this paint comes from recovery.
                verify(exactly = 1) { window.repaint() }
            } finally {
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `recovery repaint failure stays contained in the torn outcome`() {
        val window = mockk<Window>(relaxed = true) { every { isDisplayable } returns true }
        val repaintFailure = IllegalStateException("repaint failed")
        every { window.repaint() } throws repaintFailure
        every { Window.getWindows() } returns arrayOf(window)
        withRealUiDefaults {
            val saved = UIManager.put("Component.focusColor", Color.BLUE)
            try {
                val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
                assertSame(repaintFailure, outcome.failures.single { it.step == AccentApplyStep.RepaintWindows }.error)
                assertSame(Color.BLUE, UIManager.getColor("Component.focusColor"))
            } finally {
                UIManager.put("Component.focusColor", saved)
            }
        }
    }

    @Test
    fun `contained element failure keeps the completed UI colors`() {
        // Unlike the IndentRainbow tear, an element failure lets the plan run to MarkApplyClean.
        val broken = mockk<AccentElement>(relaxed = true)
        every { broken.id } returns AccentElementId.CARET_ROW
        every { broken.displayName } returns "Caret Row"
        every { broken.apply(any()) } throws IllegalStateException("element failed")
        mockEpExtensionList(listOf(broken))
        state.cgpIntegrationEnabled = false
        withSuccessfulVisualApply {
            val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))
            assertEquals(AccentApplyStep.ApplyElements, outcome.failures.single().step)
            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = UIManager.getColor("Component.focusColor"))
            assertFalse(state.lastApplyOk)
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
            every { listener.accentChanged(any(), any(), any()) } returns Unit
            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#AABBCC"))
        }
    }

    @Test
    fun `abort after the tab underline step restores the previous underline geometry`() {
        every { ComponentTreeRefresher.notifyOnly(any()) } throws IllegalStateException("notify failed")
        withSuccessfulVisualApply {
            val previousHeight = Integer.valueOf(7)
            UIManager.put("EditorTabs.underlineHeight", previousHeight)
            UIManager.put("EditorTabs.underlineArc", null)

            val outcome = assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

            assertEquals(AccentApplyStep.NotifyComponentTrees, outcome.failures.single().step)
            assertSame(previousHeight, UIManager.getDefaults()["EditorTabs.underlineHeight"])
            assertNull(UIManager.getDefaults()["EditorTabs.underlineArc"])
        }
    }

    @Test
    fun `abort after the tab underline step restores underline ownership with its keys`() {
        every { ComponentTreeRefresher.notifyOnly(any()) } throws IllegalStateException("notify failed")
        withSuccessfulVisualApply {
            val checkpoint = UiDefaultsCheckpoint()
            uiDefaultsOwner.record(checkpoint)
            uiDefaultsOwner.put("EditorTabs.underlineHeight", Integer.valueOf(9), checkpoint)
            checkpoint.commit()
            try {
                assertIs<AccentApplyOutcome.Torn>(AccentApplicator.applyFromHexString("#445566"))

                assertEquals(9, UIManager.getInt("EditorTabs.underlineHeight"))
                assertTrue(uiDefaultsOwner.ownsAny(setOf("EditorTabs.underlineHeight")))
            } finally {
                uiDefaultsOwner.restore()
            }
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
    fun `nested apply cannot mix colors or replace the restart cache`() {
        withSuccessfulVisualApply {
            val defaults = UIManager.getDefaults()
            var attempted = false
            var nestedOutcome: AccentApplyOutcome? = null
            val nestedListener =
                PropertyChangeListener { event ->
                    if (!attempted && event.propertyName == "GotItTooltip.background") {
                        attempted = true
                        nestedOutcome = AccentApplicator.applyFromHexString("#AABBCC")
                    }
                }
            defaults.addPropertyChangeListener(nestedListener)
            try {
                assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#445566"))
                assertTrue(attempted)
                val nested = assertIs<AccentApplyOutcome.Torn>(nestedOutcome)
                assertFalse(nested.visualsApplied)
                assertEquals(
                    expected = "Accent application is already in progress",
                    actual =
                        nested.failures
                            .single()
                            .error.message,
                )
                for (key in AccentUiKeys.accent) {
                    assertEquals(expected = Color(0x44, 0x55, 0x66), actual = UIManager.getColor(key), message = key)
                }
                assertEquals(expected = "#445566", actual = state.lastAppliedAccentHex)
                assertTrue(state.lastApplyOk)

                assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#AABBCC"))
                for (key in AccentUiKeys.accent) {
                    assertEquals(expected = Color(0xAA, 0xBB, 0xCC), actual = UIManager.getColor(key), message = key)
                }
                assertEquals(expected = "#AABBCC", actual = state.lastAppliedAccentHex)
                assertTrue(state.lastApplyOk)
            } finally {
                defaults.removePropertyChangeListener(nestedListener)
            }
        }
    }

    @Test
    fun `external native opt out restores owned controls and retains explicit tab styling`() {
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.tabUnderlineHeight = 7
        state.tabUnderlineGlowSync = false
        every { AyuVariant.detect() } returns null
        withSuccessfulVisualApply {
            val key = "Button.default.startBackground"
            val native = Color(0x81, 0x42, 0x73)
            val nativeUnderline = Color(0x32, 0x74, 0x91)
            val nativeIndicator = Color(0x73, 0x19, 0x42)
            UIManager.put(key, native)
            UIManager.put("EditorTabs.underlinedBorderColor", nativeUnderline)
            UIManager.put("EditorTabs.underlineHeight", 2)
            UIManager.put("ToolWindow.HeaderTab.underlineColor", nativeIndicator)
            UIManager.put("TabbedPane.underlineColor", nativeIndicator)
            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#445566"))
            assertEquals(expected = Color(0x44, 0x55, 0x66), actual = UIManager.getColor(key))

            state.externalThemeAccentOverrideEnabled = false
            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#AABBCC"))
            assertSame(expected = native, actual = UIManager.getColor(key))
            assertSame(expected = Color.BLUE, actual = UIManager.getColor("Component.focusColor"))
            assertEquals(
                expected = Color(0xAA, 0xBB, 0xCC),
                actual = UIManager.getColor("EditorTabs.underlinedBorderColor"),
            )
            assertEquals(expected = 7, actual = UIManager.getInt("EditorTabs.underlineHeight"))
            assertSame(expected = nativeIndicator, actual = UIManager.getColor("ToolWindow.HeaderTab.underlineColor"))
            assertSame(expected = nativeIndicator, actual = UIManager.getColor("TabbedPane.underlineColor"))
            verify { IndentRainbowSync.apply(AccentContext.External, "#AABBCC") }

            state.externalThemeChromeTintEnabled = false
            assertIs<AccentApplyOutcome.Applied>(AccentApplicator.applyFromHexString("#DDEEFF"))
            assertSame(expected = native, actual = UIManager.getColor(key))
            assertSame(expected = nativeUnderline, actual = UIManager.getColor("EditorTabs.underlinedBorderColor"))
            assertEquals(expected = 2, actual = UIManager.getInt("EditorTabs.underlineHeight"))
            assertFalse(uiDefaultsOwner.ownsAny(setOf("EditorTabs.underlinedBorderColor")))
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
