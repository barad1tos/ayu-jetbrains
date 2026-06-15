package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageScanner
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.io.File
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
            "Detected: Kotlin 90% - using Language override",
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
            "No dominant language: JavaScript 50% - TypeScript 50% - using Project fallback #5CCFE6",
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
                "No dominant language: JavaScript 50% - TypeScript 50% - using Global",
                ProjectLanguageResolutionPanel.SET_FALLBACK_LABEL,
                ProjectLanguageResolutionPanel.RESCAN_LABEL,
            ),
            builder.proportionsPanelLabelsForTest().map { it.second },
        )
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
            "Forced language: TypeScript - using Forced language override",
            builder.currentProportionsTextForTest(),
        )
        assertTrue(
            ProjectLanguageResolutionPanel.CLEAR_FORCED_LANGUAGE_LABEL in
                builder.proportionsPanelLabelsForTest().map { it.second },
        )
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
            "Detected: Kotlin 100% - using Language override",
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
            "No dominant language: JavaScript 50% - TypeScript 50% - using Project fallback #5CCFE6",
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
        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project, licensed = false) }

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
        return panel.components
            .filterIsInstance<com.intellij.ui.components.JBLabel>()
            .firstOrNull { it.text == text }
            ?: error(
                "Label '$text' missing; got " +
                    panel.components.map { (it as? com.intellij.ui.components.JBLabel)?.text },
            )
    }

    private fun click(component: JComponent) {
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
            .filter { it.javaClass.name.startsWith("dev.ayuislands") }
            .forEach { it.mouseClicked(event) }
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
