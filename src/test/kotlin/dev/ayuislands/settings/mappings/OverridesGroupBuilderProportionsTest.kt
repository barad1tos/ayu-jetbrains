package dev.ayuislands.settings.mappings

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetectionListener
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageScanner
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.accent.ScanOutcome
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.io.File
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverridesGroupBuilderProportionsTest {
    @BeforeTest
    fun setUp() {
        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.dominant(any()) } returns null
        every { ProjectLanguageDetector.verdict(any()) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.verdict(any(), any<Boolean>()) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.rescan(any()) } returns Unit

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns AyuIslandsState()
        every { settings.getAccentForVariant(AyuVariant.MIRAGE) } returns "#5CCFE6"
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        installUiServices()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `buildGroup connects one owned MessageBus subscription`() {
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val connectionParent = slot<Disposable>()
        every { messageBus.connect(capture(connectionParent)) } returns connection
        val project = stubProject(tmpKey("owned-subscription"), messageBus)
        val builder = OverridesGroupBuilder(stateProvider = { AccentMappingsState() })

        try {
            buildGroup(builder, project)

            verify(exactly = 1) { messageBus.connect(any<Disposable>()) }
            verify(exactly = 1) {
                connection.subscribe(ProjectLanguageDetectionListener.TOPIC, any<ProjectLanguageDetectionListener>())
            }
            assertTrue(connectionParent.isCaptured)
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `scan completion refreshes the displayable diagnostics panel from the latest verdict`() {
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val listener = slot<ProjectLanguageDetectionListener>()
        every { messageBus.connect(any<Disposable>()) } returns connection
        every {
            connection.subscribe(ProjectLanguageDetectionListener.TOPIC, capture(listener))
        } returns Unit
        val project = stubProject(tmpKey("scan-refresh"), messageBus)
        var verdict: ProjectLanguageVerdict = ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.verdict(project) } answers { verdict }
        val builder = OverridesGroupBuilder(stateProvider = { AccentMappingsState() })

        try {
            val root = buildGroup(builder, project)
            val diagnostics = resolutionPanel(root)
            diagnostics.addNotify()
            assertTrue("Detection pending" in diagnostics.currentSummaryForTest())

            verdict = ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))
            listener.captured.scanCompleted(ScanOutcome.Detected("kotlin"))
            SwingUtilities.invokeAndWait {}

            assertTrue("Kotlin (100%)" in diagnostics.currentSummaryForTest())
            verify(exactly = 2) { ProjectLanguageDetector.verdict(project) }
            diagnostics.removeNotify()
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `rebuilding disconnects the previous subscription before reconnecting`() {
        val events = mutableListOf<String>()
        val messageBus = mockk<MessageBus>()
        val firstConnection = mockk<MessageBusConnection>(relaxed = true)
        val secondConnection = mockk<MessageBusConnection>(relaxed = true)
        val connections = ArrayDeque(listOf(firstConnection, secondConnection))
        every { messageBus.connect(any<Disposable>()) } answers {
            events += "connect"
            connections.removeFirst()
        }
        every { firstConnection.disconnect() } answers {
            events += "disconnect"
        }
        val project = stubProject(tmpKey("reentry-subscription"), messageBus)
        val state = AccentMappingsState()
        val draft = AccentMappingsDraft()
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })

        try {
            buildGroup(builder, project)
            val pending = ProjectMapping(tmpKey("pending-reentry"), "Pending re-entry", "#AABBCC")
            draft.addProject(pending)
            val rebuilt = buildGroup(builder, project)
            val cards =
                rebuilt
                    .descendants()
                    .filterIsInstance<JPanel>()
                    .single { it.layout is CardLayout }
            val projectTable = rebuilt.descendants().filterIsInstance<JTable>().single { it.columnCount == 3 }
            val languageTable = rebuilt.descendants().filterIsInstance<JTable>().single { it.columnCount == 2 }

            assertEquals(listOf("connect", "disconnect", "connect"), events)
            assertEquals(listOf(pending), draft.projectMappings)
            assertTrue(builder.isModified())
            assertEquals(2, cards.componentCount)
            assertTrue(SwingUtilities.isDescendingFrom(projectTable, cards.components.single { it.isVisible }))

            rebuilt
                .descendants()
                .filterIsInstance<JRadioButton>()
                .single { it.text == "Languages" }
                .doClick()
            assertTrue(SwingUtilities.isDescendingFrom(languageTable, cards.components.single { it.isVisible }))
            verify(exactly = 2) { messageBus.connect(any<Disposable>()) }
            verify(exactly = 1) { firstConnection.disconnect() }
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `dispose disconnects idempotently tolerates failure and is safe before build`() {
        OverridesGroupBuilder().apply {
            dispose()
            dispose()
        }

        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>()
        every { connection.subscribe(any(), any<ProjectLanguageDetectionListener>()) } returns Unit
        every { connection.disconnect() } throws IllegalStateException("already disposed")
        every { messageBus.connect(any<Disposable>()) } returns connection
        val builder = OverridesGroupBuilder(stateProvider = { AccentMappingsState() })
        buildGroup(builder, stubProject(tmpKey("disconnect-failure"), messageBus))

        builder.dispose()
        builder.dispose()

        verify(exactly = 1) { connection.disconnect() }
    }

    @Test
    fun `strict preview refresh does not invoke detector warming`() {
        mockkObject(ProjectLanguageScanner)
        every { ProjectLanguageScanner.scan(any()) } returns emptyMap()
        val projectKey = tmpKey("strict-preview")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")
        val draft =
            AccentMappingsDraft().apply {
                addLanguage(LanguageMapping("kotlin", "Kotlin", "#FFCC66"))
            }
        val builder = preparedBuilder(draft)

        try {
            buildGroup(builder, project)

            verify(exactly = 0) { ProjectLanguageDetector.verdict(project, warmCache = true) }
            verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
            verify(exactly = 0) { ProjectLanguageScanner.scan(any()) }
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `diagnostics render uses one detector verdict for source status and actions`() {
        val project = stubProject(tmpKey("consistent-diagnostics"))
        every { ProjectLanguageDetector.verdict(project) } returnsMany
            listOf(
                ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L)),
                ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 700L, "javascript" to 300L)),
            )
        val draft =
            AccentMappingsDraft().apply {
                addLanguage(LanguageMapping("kotlin", "Kotlin", "#FFCC66"))
            }
        val builder = preparedBuilder(draft)

        try {
            val root = buildGroup(builder, project)
            val resolutionPanel = resolutionPanel(root)

            assertEquals(
                "Accent source: Language override (Kotlin, 100%)\n" +
                    "Detected in this project: Kotlin (100%)",
                resolutionPanel.currentSummaryForTest(),
            )
            assertTrue("Force Kotlin" in actionTexts(root))
            assertFalse(ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL in actionTexts(root))
            verify(exactly = 1) { ProjectLanguageDetector.verdict(project) }
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `pending listener registration de-duplicates the same runnable`() {
        val builder = OverridesGroupBuilder()
        var calls = 0
        val listener = Runnable { calls += 1 }

        builder.addPendingChangeListener(listener)
        builder.addPendingChangeListener(listener)
        builder.setPendingFallbackAccent(tmpKey("listener-deduplication"), "#112233")

        assertEquals(1, calls)
    }

    @Test
    fun `live license changes alter mutation and rescan availability`() {
        val project = stubProject(tmpKey("license-refresh"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))
        val draft =
            AccentMappingsDraft().apply {
                addLanguage(LanguageMapping("kotlin", "Kotlin", "#FFCC66"))
            }
        val builder = preparedBuilder(draft)

        try {
            val root = buildGroup(builder, project)
            assertTrue("Force Kotlin" in actionTexts(root))
            assertTrue(ProjectLanguageResolutionPanel.RESCAN_LABEL in actionTexts(root))

            every { LicenseChecker.isLicensedOrGrace() } returns false
            builder.reset()

            assertFalse("Force Kotlin" in actionTexts(root))
            assertFalse(ProjectLanguageResolutionPanel.RESCAN_LABEL in actionTexts(root))
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `stale diagnostics links cannot mutate or rescan after license loss`() {
        val projectKey = tmpKey("stale-license-actions")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 700L, "javascript" to 300L))
        val draft = AccentMappingsDraft()
        val builder = preparedBuilder(draft)

        try {
            val root = buildGroup(builder, project)
            val links = root.descendants().filterIsInstance<ActionLink>().associateBy(ActionLink::getText)
            val setFallback = requireNotNull(links[ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL])
            val forceLanguage = requireNotNull(links["Force TypeScript"])
            val rescan = requireNotNull(links[ProjectLanguageResolutionPanel.RESCAN_LABEL])

            every { LicenseChecker.isLicensedOrGrace() } returns false
            setFallback.doClick()
            forceLanguage.doClick()
            rescan.doClick()

            assertNull(draft.projectFallbackAccent(projectKey))
            assertNull(draft.forcedLanguageId(projectKey))
            verify(exactly = 0) { ProjectLanguageDetector.rescan(project) }
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `stale clear links preserve pending overrides after license loss`() {
        val projectKey = tmpKey("stale-clear-actions")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))
        val draft =
            AccentMappingsDraft().apply {
                setProjectFallbackAccent(projectKey, "#112233")
                setForcedLanguage(projectKey, "kotlin")
            }
        val builder = preparedBuilder(draft)

        try {
            val root = buildGroup(builder, project)
            val links = root.descendants().filterIsInstance<ActionLink>().associateBy(ActionLink::getText)
            val clearFallback = requireNotNull(links[ProjectLanguageResolutionPanel.CLEAR_FALLBACK_LABEL])
            val clearLanguage = requireNotNull(links[ProjectLanguageResolutionPanel.CLEAR_FORCED_LANGUAGE_LABEL])

            every { LicenseChecker.isLicensedOrGrace() } returns false
            clearFallback.doClick()
            clearLanguage.doClick()

            assertEquals("#112233", draft.projectFallbackAccent(projectKey))
            assertEquals("kotlin", draft.forcedLanguageId(projectKey))
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `current pending project accent wins over global provider for fallback action`() {
        val projectKey = tmpKey("pending-project-accent")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 700L, "javascript" to 300L))
        val draft =
            AccentMappingsDraft().apply {
                addProject(ProjectMapping(projectKey, "Focused project", "#112233"))
            }
        val state = AccentMappingsState().also(draft::writeTo)
        val builder =
            OverridesGroupBuilder(
                currentGlobalAccentHex = { "#ABCDEF" },
                draft = draft,
                stateProvider = { state },
            )

        try {
            val root = buildGroup(builder, project)
            resolutionPanel(root).triggerActionForTest(ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL)

            assertEquals("#112233", draft.projectFallbackAccent(projectKey))
        } finally {
            builder.dispose()
        }
    }

    private fun preparedBuilder(draft: AccentMappingsDraft): OverridesGroupBuilder {
        val state = AccentMappingsState().also(draft::writeTo)
        return OverridesGroupBuilder(draft = draft, stateProvider = { state })
    }

    private fun buildGroup(
        builder: OverridesGroupBuilder,
        project: Project,
    ): Component =
        panel {
            builder.buildGroup(this, project)
        }

    private fun actionTexts(root: Component): List<String> =
        root
            .descendants()
            .filterIsInstance<ActionLink>()
            .map(ActionLink::getText)
            .toList()

    private fun resolutionPanel(root: Component): ProjectLanguageResolutionPanel =
        root
            .descendants()
            .filterIsInstance<ProjectLanguageResolutionPanel>()
            .single()

    private fun tmpKey(name: String): String =
        File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}").canonicalPath

    private fun stubProject(
        basePath: String,
        messageBus: MessageBus = mockk(relaxed = true),
    ): Project {
        val project = mockk<Project>()
        every { project.basePath } returns basePath
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.name } returns basePath.substringAfterLast(File.separatorChar)
        every { project.messageBus } returns messageBus
        return project
    }

    private fun installUiServices() {
        mockkStatic(ApplicationManager::class)
        val application = mockk<Application>(relaxed = true)
        val actionManager = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns application
        every { application.getService(ActionManager::class.java) } returns actionManager
        every { actionManager.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val coroutineSupportClass = Class.forName("com.intellij.openapi.application.CoroutineSupport") as Class<Any>
        val coroutineSupport = mockkClass(coroutineSupportClass.kotlin, relaxed = true)
        every { application.getService(coroutineSupportClass) } returns coroutineSupport

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUi = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { application.getService(experimentalUiClass) } returns experimentalUi
    }
}

private fun Component.descendants(): Sequence<Component> =
    sequence {
        yield(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { child ->
                yieldAll(child.descendants())
            }
        }
    }
