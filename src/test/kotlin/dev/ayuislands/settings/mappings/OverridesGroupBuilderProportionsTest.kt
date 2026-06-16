package dev.ayuislands.settings.mappings

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageScanner
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wiring tests for the project language resolution diagnostics panel rendered by
 * [OverridesGroupBuilder] inside Settings -> Accent -> Overrides.
 *
 * The legacy seam names are intentionally retained
 * ([OverridesGroupBuilder.currentProportionsTextForTest],
 * [OverridesGroupBuilder.proportionsPanelLabelsForTest]) so older harness code
 * can keep driving the diagnostics row while the production path switches from
 * [ProjectLanguageDetector.proportions] to [ProjectLanguageDetector.verdict].
 */
class OverridesGroupBuilderProportionsTest {
    private lateinit var mappingsState: AccentMappingsState

    @BeforeTest
    fun setUp() {
        mappingsState = AccentMappingsState()
        val settings = mockk<AccentMappingsSettings>()
        every { settings.state } returns mappingsState
        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns settings

        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.dominant(any()) } returns null
        every { ProjectLanguageDetector.verdict(any()) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.proportions(any()) } returns null
        every { ProjectLanguageDetector.rescan(any()) } returns Unit

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val ayuSettings = mockk<AyuIslandsSettings>()
        every { ayuSettings.state } returns AyuIslandsState()
        every { ayuSettings.getAccentForVariant(AyuVariant.MIRAGE) } returns "#5CCFE6"
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns ayuSettings
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `currentProportionsTextForTest renders detected resolution summary from verdict`() {
        val project = stubProject(tmpKey("resolution-detected"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected(
                languageId = "kotlin",
                weights = mapOf("kotlin" to 900L, "java" to 100L),
            )
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project)
                seedPendingForTest(
                    languages = listOf(LanguageMapping("kotlin", "Kotlin", "#FFCC66")),
                )
            }

        assertEquals(
            "Accent source: Language override\nLanguage scan: Dominant Kotlin\nKotlin 90% · Java 10%",
            builder.currentProportionsTextForTest(),
        )
        verify(exactly = 0) { ProjectLanguageDetector.proportions(any()) }
    }

    @Test
    fun `currentProportionsTextForTest renders language fallback source for unmapped detected language`() {
        val project = stubProject(tmpKey("resolution-language-fallback"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected(
                languageId = "typescript",
                weights = mapOf("typescript" to 900L, "javascript" to 100L),
            )
        every { ProjectLanguageDetector.dominant(project) } returns "typescript"

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project)
                seedPendingForTest(
                    languages = listOf(LanguageMapping("kotlin", "Kotlin", "#FFCC66")),
                )
                seedResolutionOverridesForTest(languageFallbackAccent = "#73D0FF")
            }

        assertEquals(
            listOf(
                "Accent source: Language fallback override",
                "Language scan: Dominant TypeScript",
                "TypeScript 90% · JavaScript 10%",
            ).joinToString("\n"),
            builder.currentProportionsTextForTest(),
        )
        verify(exactly = 0) { ProjectLanguageDetector.proportions(any()) }
    }

    @Test
    fun `currentProportionsTextForTest includes project fallback hex for no-winner fallback`() {
        val projectKey = tmpKey("resolution-fallback")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project)
                seedResolutionOverridesForTest(fallbackAccents = mapOf(projectKey to "#5CCFE6"))
            }

        assertEquals(
            listOf(
                "Accent source: Project fallback #5CCFE6",
                "Language scan: No dominant language",
                "JavaScript 50% · TypeScript 50%",
            ).joinToString("\n"),
            builder.currentProportionsTextForTest(),
        )
        verify(exactly = 0) { ProjectLanguageDetector.proportions(any()) }
    }

    @Test
    fun `proportionsPanelLabelsForTest returns summary followed by diagnostics actions`() {
        val project = stubProject(tmpKey("resolution-actions"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        assertEquals(
            listOf(
                "Accent source:",
                "Global",
                "Language scan:",
                "No dominant language",
                "JavaScript 50%",
                "·",
                "TypeScript 50%",
                ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL,
                ProjectLanguageResolutionPanel.RESCAN_LABEL,
            ),
            builder.proportionsPanelLabelsForTest().map { it.second },
        )
    }

    @Test
    fun `proportionsPanelLabelsForTest keeps source iconless and splits language percentages`() {
        val project = stubProject(tmpKey("resolution-icons"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 700L, "java" to 300L))

        val labels =
            OverridesGroupBuilder()
                .apply { setParentProjectForTest(project) }
                .proportionsPanelLabelsForTest()

        assertNull(labels.first { it.second == "Global" }.first)
        assertTrue(labels.any { it.second == "Kotlin 70%" })
        assertTrue(labels.any { it.second == "Java 30%" })
        assertNull(labels.first { it.second == "·" }.first)
    }

    @Test
    fun `forced language pending state renders forced summary and clear action`() {
        val projectKey = tmpKey("resolution-forced")
        val project = stubProject(projectKey)

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project)
                seedPendingForTest(
                    languages = listOf(LanguageMapping("typescript", "TypeScript", "#3178C6")),
                )
                seedResolutionOverridesForTest(forcedLanguages = mapOf(projectKey to "typescript"))
            }

        assertEquals(
            "Accent source: Forced language override\nLanguage scan: Forced TypeScript",
            builder.currentProportionsTextForTest(),
        )
        assertTrue(
            ProjectLanguageResolutionPanel.CLEAR_FORCED_LANGUAGE_LABEL in
                builder.proportionsPanelLabelsForTest().map { it.second },
        )
    }

    @Test
    fun `diagnostics refresh uses live license state for source and action visibility`() {
        val project = stubProject(tmpKey("resolution-license-flip"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected(
                languageId = "kotlin",
                weights = mapOf("kotlin" to 1_000L),
            )

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project, licensed = true)
                seedPendingForTest(languages = listOf(LanguageMapping("kotlin", "Kotlin", "#FFCC66")))
            }

        assertEquals(
            "Accent source: Language override\nLanguage scan: Kotlin 100%",
            builder.currentProportionsTextForTest(),
        )

        every { LicenseChecker.isLicensedOrGrace() } returns false

        assertEquals(
            "Accent source: Global\nLanguage scan: Kotlin 100%",
            builder.currentProportionsTextForTest(),
        )
        val labels = builder.proportionsPanelLabelsForTest().map { it.second }
        assertFalse("Force Kotlin" in labels)
        assertFalse(ProjectLanguageResolutionPanel.RESCAN_LABEL in labels)
    }

    @Test
    fun `diagnostics read path does not invoke ProjectLanguageScanner scan or detector proportions`() {
        mockkObject(ProjectLanguageScanner)
        every { ProjectLanguageScanner.scan(any()) } returns emptyMap()

        val project = stubProject(tmpKey("resolution-no-scan"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected(
                languageId = "kotlin",
                weights = mapOf("kotlin" to 1_000L),
            )

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        builder.currentProportionsTextForTest()
        builder.proportionsPanelLabelsForTest()

        verify(exactly = 0) { ProjectLanguageScanner.scan(any()) }
        verify(exactly = 0) { ProjectLanguageDetector.proportions(any()) }
    }

    @Test
    fun `diagnostics render with pending language row does not consult dominant or scanner`() {
        mockkObject(ProjectLanguageScanner)
        every { ProjectLanguageScanner.scan(any()) } returns emptyMap()

        val project = stubProject(tmpKey("resolution-language-cache-only"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected(
                languageId = "kotlin",
                weights = mapOf("kotlin" to 1_000L),
            )
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project)
                seedPendingForTest(languages = listOf(LanguageMapping("kotlin", "Kotlin", "#FFCC66")))
            }

        assertEquals(
            "Accent source: Language override\nLanguage scan: Kotlin 100%",
            builder.currentProportionsTextForTest(),
        )
        builder.proportionsPanelLabelsForTest()

        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
        verify(exactly = 0) { ProjectLanguageScanner.scan(any()) }
    }

    @Test
    fun `diagnostics render with pending fallback row does not consult dominant or scanner`() {
        mockkObject(ProjectLanguageScanner)
        every { ProjectLanguageScanner.scan(any()) } returns emptyMap()

        val projectKey = tmpKey("resolution-fallback-cache-only")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project)
                seedResolutionOverridesForTest(fallbackAccents = mapOf(projectKey to "#5CCFE6"))
            }

        assertEquals(
            listOf(
                "Accent source: Project fallback #5CCFE6",
                "Language scan: No dominant language",
                "JavaScript 50% · TypeScript 50%",
            ).joinToString("\n"),
            builder.currentProportionsTextForTest(),
        )
        builder.proportionsPanelLabelsForTest()

        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
        verify(exactly = 0) { ProjectLanguageScanner.scan(any()) }
    }

    @Test
    fun `Set fallback uses current pending project override accent instead of global variant accent`() {
        val projectKey = tmpKey("resolution-current-accent")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 700L, "javascript" to 300L))

        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(project)
                seedPendingForTest(projects = listOf(ProjectMapping(projectKey, "Focused project", "#112233")))
            }

        click(findLabel(builder, ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL))

        assertEquals("#112233", builder.fallbackAccentsForTest()[projectKey])
    }

    @Test
    fun `Set fallback uses pending global accent provider when no project override is active`() {
        val projectKey = tmpKey("resolution-pending-global-accent")
        val project = stubProject(projectKey)
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 700L, "javascript" to 300L))

        val builder =
            OverridesGroupBuilder(currentGlobalAccentHex = { "#112233" }).apply {
                setParentProjectForTest(project)
            }

        click(findLabel(builder, ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL))

        assertEquals("#112233", builder.fallbackAccentsForTest()[projectKey])
    }

    @Test
    fun `Set fallback action is hidden when pending current accent is invalid`() {
        val project = stubProject(tmpKey("resolution-invalid-current-accent"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 700L, "javascript" to 300L))

        val builder =
            OverridesGroupBuilder(currentGlobalAccentHex = { "not-a-hex" }).apply {
                setParentProjectForTest(project)
            }

        val labels = builder.proportionsPanelLabelsForTest().map { it.second }

        assertFalse(ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL in labels)
    }

    @Test
    fun `Rescan label click is blocked when license flips to unlicensed`() {
        val project = stubProject(tmpKey("resolution-rescan-defense"))
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        val rescan = findLabel(builder, ProjectLanguageResolutionPanel.RESCAN_LABEL)
        every { LicenseChecker.isLicensedOrGrace() } returns false

        click(rescan)

        verify(exactly = 0) { ProjectLanguageDetector.rescan(project) }
    }

    @Test
    fun `Rescan label click rescans focused licensed project`() {
        val project = stubProject(tmpKey("resolution-rescan"))
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        click(findLabel(builder, ProjectLanguageResolutionPanel.RESCAN_LABEL))

        verify(exactly = 1) { ProjectLanguageDetector.rescan(project) }
    }

    @Test
    fun `Rescan label is hidden when there is no focused project`() {
        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(null) }

        val labels = builder.proportionsPanelLabelsForTest().map { it.second }

        assertFalse(ProjectLanguageResolutionPanel.RESCAN_LABEL in labels)
    }

    @Test
    fun `Rescan label is hidden when diagnostics are unlicensed`() {
        val project = stubProject(tmpKey("resolution-rescan-unlicensed"))
        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val labels = builder.proportionsPanelLabelsForTest().map { it.second }

        assertFalse(ProjectLanguageResolutionPanel.RESCAN_LABEL in labels)
    }

    @Test
    fun `Rescan label uses hand cursor`() {
        val project = stubProject(tmpKey("resolution-rescan-cursor"))
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        assertEquals(
            Cursor.HAND_CURSOR,
            findLabel(builder, ProjectLanguageResolutionPanel.RESCAN_LABEL).cursor.type,
        )
    }

    @Test
    fun `Rescan label exposes re-detect tooltip`() {
        val project = stubProject(tmpKey("resolution-rescan-tooltip"))
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold

        val labels =
            OverridesGroupBuilder()
                .apply { setParentProjectForTest(project) }
                .proportionsPanelLabelsForTest()

        assertEquals(
            ProjectLanguageResolutionPanel.RESCAN_TOOLTIP,
            labels.first { it.second == ProjectLanguageResolutionPanel.RESCAN_LABEL }.third,
        )
    }

    @Test
    fun `dispose disconnects the live detection subscription`() {
        val builder = OverridesGroupBuilder()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        installDetectionConnection(builder, connection)

        builder.dispose()

        verify(exactly = 1) { connection.disconnect() }
        assertNull(readDetectionConnection(builder))
    }

    @Test
    fun `dispose is idempotent across repeated calls`() {
        val builder = OverridesGroupBuilder()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        installDetectionConnection(builder, connection)

        builder.dispose()
        builder.dispose()

        verify(exactly = 1) { connection.disconnect() }
        assertNull(readDetectionConnection(builder))
    }

    @Test
    fun `dispose swallows disconnect failure and clears the connection`() {
        val builder = OverridesGroupBuilder()
        val connection = mockk<MessageBusConnection>()
        every { connection.disconnect() } throws IllegalStateException("already disposed")
        installDetectionConnection(builder, connection)

        builder.dispose()

        verify(exactly = 1) { connection.disconnect() }
        assertNull(readDetectionConnection(builder))
    }

    @Test
    fun `dispose before wiring is a no-op`() {
        val builder = OverridesGroupBuilder()

        builder.dispose()
        builder.dispose()

        assertNull(readDetectionConnection(builder))
    }

    @Test
    fun `buildGroup does not warm detector through dominant`() {
        val project = stubProject(tmpKey("resolution-build-cache-only"))
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        installApplicationActionManager()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")

        panel {
            OverridesGroupBuilder().buildGroup(this, project)
        }

        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `buildGroup connects diagnostics subscription with owned disposable parent`() {
        val project = stubProject(tmpKey("resolution-parented-subscription"))
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        installApplicationActionManager()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection

        val builder = OverridesGroupBuilder()
        panel {
            builder.buildGroup(this, project)
        }

        verify(exactly = 1) { messageBus.connect(any<Disposable>()) }
        assertEquals(connection, readDetectionConnection(builder))
    }

    @Test
    fun `buildGroup re-entry disconnects previous diagnostics subscription before reconnecting`() {
        val project = stubProject(tmpKey("resolution-reentry-subscription"))
        val messageBus = mockk<MessageBus>()
        val firstConnection = mockk<MessageBusConnection>(relaxed = true)
        val secondConnection = mockk<MessageBusConnection>(relaxed = true)
        installApplicationActionManager()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returnsMany listOf(firstConnection, secondConnection)

        val builder = OverridesGroupBuilder()
        panel {
            builder.buildGroup(this, project)
        }
        panel {
            builder.buildGroup(this, project)
        }

        verify(exactly = 2) { messageBus.connect(any<Disposable>()) }
        verify(exactly = 1) { firstConnection.disconnect() }
        assertEquals(secondConnection, readDetectionConnection(builder))
    }

    @Test
    fun `pending change listener registration de-duplicates identical runnable`() {
        val builder = OverridesGroupBuilder()
        var calls = 0
        val listener = Runnable { calls += 1 }

        builder.addPendingChangeListener(listener)
        builder.addPendingChangeListener(listener)

        builder.setPendingFallbackAccent(tmpKey("listener-dedupe"), "#112233")

        assertEquals(1, calls)
    }

    private fun tmpKey(name: String): String =
        File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}").canonicalPath

    private fun stubProject(basePath: String): Project {
        val project = mockk<Project>()
        every { project.basePath } returns basePath
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.name } returns basePath.substringAfterLast(File.separatorChar)
        return project
    }

    private fun findLabel(
        builder: OverridesGroupBuilder,
        text: String,
    ): JComponent {
        builder.proportionsPanelLabelsForTest()
        val panel =
            builder.javaClass
                .getDeclaredField("proportionsPanel")
                .apply { isAccessible = true }
                .get(builder) as JPanel
        val renderedTexts =
            panel
                .descendants()
                .filterIsInstance<JComponent>()
                .mapNotNull(::componentText)
                .toList()
        return panel
            .descendants()
            .filterIsInstance<JComponent>()
            .firstOrNull { componentText(it) == text }
            ?: error(
                "Label '$text' missing; got " +
                    renderedTexts,
            )
    }

    private fun click(component: JComponent) {
        if (component is AbstractButton) {
            component.doClick()
            return
        }
        val event =
            MouseEvent(
                component,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                0,
                0,
                1,
                false,
            )
        component.mouseListeners
            .forEach { it.mouseClicked(event) }
    }

    private fun componentText(component: JComponent): String? =
        when (component) {
            is JBLabel -> component.text
            is ActionLink -> component.text
            is AbstractButton -> component.text
            else -> null
        }

    private fun installApplicationActionManager() {
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

    private fun installDetectionConnection(
        builder: OverridesGroupBuilder,
        connection: MessageBusConnection?,
    ) {
        builder.javaClass
            .getDeclaredField("detectionConnection")
            .apply { isAccessible = true }
            .set(builder, connection)
    }

    private fun readDetectionConnection(builder: OverridesGroupBuilder): MessageBusConnection? =
        builder.javaClass
            .getDeclaredField("detectionConnection")
            .apply { isAccessible = true }
            .get(builder) as MessageBusConnection?
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
