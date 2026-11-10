package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.color.ProjectIconAccentExtractor
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioQuickCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real image extraction and race checks; the application boundary records submitted immutable commands. */
class ProjectIconAccentAssignerTest {
    @field:TempDir
    lateinit var tempDir: Path

    private lateinit var state: AyuIslandsState
    private lateinit var mappingsState: AccentMappingsState
    private val commands = mutableListOf<Pair<TargetObservation, StudioQuickCommand.PinIfAbsent>>()

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        mappingsState = AccentMappingsState()

        commands.clear()
        val service = mockk<StudioService>()
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns service
        every { service.capture(any()) } answers {
            TargetObservation(
                StudioTarget.Project(StudioProjectKey(tempDir.toFile().canonicalPath)),
                1,
                TargetPresence.ACTIVE,
            )
        }
        every { service.view(any()) } answers { StudioFixtures.legacyView(state, mappings = mappingsState) }
        every { service.submitQuick(any(), any(), any()) } answers {
            commands.add(firstArg<TargetObservation>() to assertIs<StudioQuickCommand.PinIfAbsent>(secondArg()))
            thirdArg<
                (
                    ImmediateExecutionResult,
                ) -> Unit,
            >()(ImmediateExecutionResult.Complete(AcceptanceResult.Accepted(1, StudioList(emptyList()))))
            Job().apply { complete() }
        }

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(StudioService.Companion)
        unmockkObject(LicenseChecker)
    }

    @Test
    fun `submits dominant icon color and display name for the captured project`() {
        state.projectIconAccentEnabled = true
        writeIcon(Color(0x5C, 0xCF, 0xE6))
        val project = stubProject()

        assertTrue(assign(project))

        val key = tempDir.toFile().canonicalPath
        val (target, command) = commands.single()
        assertEquals(StudioTarget.Project(StudioProjectKey(key)), target.target)
        assertEquals("#5CCFE6", command.hex)
        assertEquals("demo-project", command.displayName)
        assertNotNull(AccentHex.of(command.hex), "submitted hex must stay parseable")
        assertTrue(mappingsState.projectAccents.isEmpty(), "producer must not write the old store")
    }

    @Test
    fun `does nothing while the toggle is off`() {
        writeIcon(Color(0x5C, 0xCF, 0xE6))

        assertFalse(assign(stubProject()))
        assertTrue(mappingsState.projectAccents.isEmpty())
    }

    @Test
    fun `does nothing for unlicensed users even with the toggle imported as on`() {
        state.projectIconAccentEnabled = true
        every { LicenseChecker.isLicensedOrGrace() } returns false
        writeIcon(Color(0x5C, 0xCF, 0xE6))

        assertFalse(assign(stubProject()))
        assertTrue(mappingsState.projectAccents.isEmpty())
    }

    @Test
    fun `never overwrites an existing mapping`() {
        state.projectIconAccentEnabled = true
        writeIcon(Color(0x5C, 0xCF, 0xE6))
        val key = tempDir.toFile().canonicalPath
        mappingsState.projectAccents[key] = "#FFCC66"
        mappingsState.projectDisplayNames[key] = "pinned-by-hand"

        assertFalse(assign(stubProject()))
        assertEquals("#FFCC66", mappingsState.projectAccents[key], "existing mapping is user intent")
        assertEquals("pinned-by-hand", mappingsState.projectDisplayNames[key])
    }

    @Test
    fun `does nothing when the project has no icon`() {
        state.projectIconAccentEnabled = true

        assertFalse(assign(stubProject()))
        assertTrue(mappingsState.projectAccents.isEmpty())
    }

    @Test
    fun `does nothing when extraction finds no qualifying color`() {
        state.projectIconAccentEnabled = true
        writeIcon(Color(0x80, 0x80, 0x80))

        assertFalse(assign(stubProject()))
        assertTrue(mappingsState.projectAccents.isEmpty())
    }

    @Test
    fun `preserves a manual pin created while icon extraction is running`() {
        state.projectIconAccentEnabled = true
        writeIcon(Color(0x5C, 0xCF, 0xE6))
        val project = stubProject()
        val key = tempDir.toFile().canonicalPath
        val extractionStarted = CompletableDeferred<Unit>()
        val allowExtraction = CountDownLatch(1)
        mockkObject(ProjectIconAccentExtractor)
        every { ProjectIconAccentExtractor.extract(any<File>()) } answers {
            extractionStarted.complete(Unit)
            check(allowExtraction.await(5, TimeUnit.SECONDS)) { "Timed out waiting for the manual pin" }
            AccentHex.of("#5CCFE6")
        }

        try {
            runBlocking {
                val assignment =
                    async {
                        ProjectIconAccentAssigner.assignIfAbsent(project, EmptyCoroutineContext)
                    }
                try {
                    withTimeout(5.seconds) { extractionStarted.await() }
                    mappingsState.projectAccents[key] = "#FFCC66"
                    mappingsState.projectDisplayNames[key] = "pinned-by-hand"
                } finally {
                    allowExtraction.countDown()
                }

                assertFalse(withTimeout(5.seconds) { assignment.await() })
            }
        } finally {
            unmockkObject(ProjectIconAccentExtractor)
        }

        assertEquals("#FFCC66", mappingsState.projectAccents[key], "manual accent must win the decode race")
        assertEquals("pinned-by-hand", mappingsState.projectDisplayNames[key])
    }

    @Test
    fun `disabling auto assignment during extraction preserves the empty mapping`() {
        state.projectIconAccentEnabled = true
        writeIcon(Color(0x5C, 0xCF, 0xE6))
        val project = stubProject()
        val extractionStarted = CompletableDeferred<Unit>()
        val allowExtraction = CountDownLatch(1)
        mockkObject(ProjectIconAccentExtractor)
        every { ProjectIconAccentExtractor.extract(any<File>()) } answers {
            extractionStarted.complete(Unit)
            check(allowExtraction.await(5, TimeUnit.SECONDS)) { "Timed out waiting for the toggle change" }
            AccentHex.of("#5CCFE6")
        }

        try {
            runBlocking {
                val assignment =
                    async {
                        ProjectIconAccentAssigner.assignIfAbsent(project, EmptyCoroutineContext)
                    }
                try {
                    withTimeout(5.seconds) { extractionStarted.await() }
                    state.projectIconAccentEnabled = false
                } finally {
                    allowExtraction.countDown()
                }

                assertFalse(withTimeout(5.seconds) { assignment.await() })
            }
        } finally {
            unmockkObject(ProjectIconAccentExtractor)
        }

        assertTrue(mappingsState.projectAccents.isEmpty(), "a disabled feature must reject the late result")
        assertTrue(mappingsState.projectDisplayNames.isEmpty())
    }

    private fun assign(project: Project): Boolean =
        runBlocking { ProjectIconAccentAssigner.assignIfAbsent(project, EmptyCoroutineContext) }

    private fun stubProject(): Project =
        mockk(relaxed = true) {
            every { basePath } returns tempDir.toString()
            every { name } returns "demo-project"
            every { isDisposed } returns false
        }

    private fun writeIcon(color: Color) {
        val ideaDir = Files.createDirectories(tempDir.resolve(".idea"))
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 16) {
            for (y in 0 until 16) {
                image.setRGB(x, y, color.rgb)
            }
        }
        ImageIO.write(image, "png", ideaDir.resolve("icon.png").toFile())
    }
}
