package dev.ayuislands.accent

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioNativeChange
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ScanCompletionAccentRefresherTest {
    private val studio = mockk<StudioService>()
    private val project = mockk<Project>()
    private val bus = mockk<MessageBus>()
    private val connection = mockk<MessageBusConnection>()
    private val subscribed = slot<ProjectLanguageDetectionListener>()
    private val parent = slot<Disposable>()

    @BeforeTest
    fun setUp() {
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns studio
        every { studio.nativeChanged(any()) } returns Job().apply { complete() }
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { project.isDisposed } returns false
        every { project.messageBus } returns bus
        every { bus.connect(capture(parent)) } returns connection
        every { connection.subscribe(ProjectLanguageDetectionListener.TOPIC, capture(subscribed)) } returns Unit
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `cacheable detection and polyglot verdicts submit current Accent context`() {
        ScanCompletionAccentRefresher(project)
        subscribed.captured.scanCompleted(ScanOutcome.Detected("kotlin"))
        subscribed.captured.scanCompleted(ScanOutcome.Polyglot)
        verify(exactly = 2) { studio.nativeChanged(StudioNativeChange.AccentContext) }
    }

    @Test
    fun `unavailable verdict does not observe or apply appearance`() {
        ScanCompletionAccentRefresher(project)
        subscribed.captured.scanCompleted(ScanOutcome.Unavailable)
        verify(exactly = 0) { AccentApplicator.resolveFocusedProject() }
        verify(exactly = 0) { studio.nativeChanged(any()) }
    }

    @Test
    fun `stale project scans never repaint over the newly focused project`() {
        ScanCompletionAccentRefresher(project)
        every { AccentApplicator.resolveFocusedProject() } returns mockk<Project>()
        subscribed.captured.scanCompleted(ScanOutcome.Detected("kotlin"))
        verify(exactly = 0) { studio.nativeChanged(any()) }
    }

    @Test
    fun `disposed projects and non Ayu contexts preserve the existing scan refresh gates`() {
        ScanCompletionAccentRefresher(project)
        every { project.isDisposed } returns true
        subscribed.captured.scanCompleted(ScanOutcome.Detected("kotlin"))
        every { project.isDisposed } returns false
        every { AyuVariant.detect() } returns null
        subscribed.captured.scanCompleted(ScanOutcome.Detected("kotlin"))
        verify(exactly = 0) { studio.nativeChanged(any()) }
    }

    @Test
    fun `a failed dispatch leaves the listener usable and platform cancellation propagates`() {
        ScanCompletionAccentRefresher(project)
        every { studio.nativeChanged(any()) } throws IllegalStateException("admission unavailable")
        subscribed.captured.scanCompleted(ScanOutcome.Detected("kotlin"))
        every { studio.nativeChanged(any()) } throws ProcessCanceledException()
        assertFailsWith<ProcessCanceledException> {
            subscribed.captured.scanCompleted(ScanOutcome.Detected("kotlin"))
        }
    }

    @Test
    fun `subscription remains anchored to the refresher lifetime`() {
        val refresher = ScanCompletionAccentRefresher(project)
        assertSame(refresher, parent.captured)
        refresher.dispose()
    }
}
