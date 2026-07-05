package dev.ayuislands.accent

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Behavioral suite for [ScanCompletionAccentRefresher] — the sole
 * accent-owning subscriber of [ProjectLanguageDetectionListener.TOPIC].
 *
 * Every spec drives the refresher through the listener it actually
 * subscribes in its `init` block (captured from the mocked
 * `MessageBusConnection`), not through direct method calls, so the
 * subscription wiring and the reaction body are locked together. The
 * resolver + apply chain specs were relocated from
 * `ProjectLanguageDetectorTest` when the refresh reaction moved out of the
 * detector; each keeps its original behavioral assertions.
 */
class ScanCompletionAccentRefresherTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `detected outcome runs the full resolver to apply chain when focus is unchanged`() {
        // Happy-path lock: given a cacheable detected verdict on a live,
        // still-focused project, the subscriber must call the full resolver →
        // applicator → swap-cache chain. Dropping any of the three downstream
        // calls would leave users stuck on the previous accent until the next
        // focus swap.
        val project = stubProject()
        val swapService = wireHappyApplyChain(project)
        val subscriber = installRefresher(project)

        subscriber.scanCompleted(ScanOutcome.Detected("kotlin"))

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `polyglot outcome reapplies the resolver fallback`() {
        // A cacheable scan can remove the active language override as well as
        // add one (polyglot / no-winner verdicts publish as Polyglot). The
        // resolver owns that fallback decision; the subscriber must apply
        // whatever it resolves so chrome/glow do not stay on the old language
        // accent until the next focus swap.
        val project = stubProject()
        val swapService = wireHappyApplyChain(project)
        val subscriber = installRefresher(project)

        subscriber.scanCompleted(ScanOutcome.Polyglot)

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `unavailable outcome never touches the applicator`() {
        // Unavailable means the scan produced no cacheable verdict — the
        // detector cache was not repopulated, so there is nothing new to
        // apply and the previous accent must stay put. Mirrors the
        // Cold/Unavailable arm of the verdict gate that lived in the detector
        // before the reaction moved here.
        val project = stubProject()
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        justRun { AccentApplicator.apply(any()) }
        val subscriber = installRefresher(project)

        subscriber.scanCompleted(ScanOutcome.Unavailable)

        verify(exactly = 0) { AccentApplicator.resolveFocusedProject() }
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `refresh skips apply chain when focused project changed after scan`() {
        // The apply path writes app-global UIManager state. A background scan
        // from an old project must not repaint over the project that gained
        // focus while the scan was running.
        val scannedProject = stubProject()
        val focusedProject = stubProject()
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns focusedProject
        justRun { AccentApplicator.apply(any()) }
        val subscriber = installRefresher(scannedProject)

        subscriber.scanCompleted(ScanOutcome.Detected("kotlin"))

        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `refresh skips apply chain when variant detection returns null`() {
        // Null variant = no Ayu theme active. The early `?: return@…` keeps
        // the applicator from touching UIManager when there's no Ayu theme to
        // drive. Locking the guard so a future author doesn't delete it as
        // "dead code" — it's the fallback path for users on a non-Ayu theme.
        val project = stubProject()
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        justRun { AccentApplicator.apply(any()) }
        val subscriber = installRefresher(project)

        subscriber.scanCompleted(ScanOutcome.Detected("kotlin"))

        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `refresh skips apply chain when project is disposed`() {
        // Scheduling delay between the background scan and the EDT publish
        // can close the project. The early return keeps the applicator from
        // writing UIManager entries for a dead project's swap-cache.
        val project = stubProject()
        mockkObject(AccentApplicator)
        justRun { AccentApplicator.apply(any()) }
        val subscriber = installRefresher(project)
        every { project.isDisposed } returns true

        subscriber.scanCompleted(ScanOutcome.Detected("kotlin"))

        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `refresh swallows a throwing apply chain without rethrowing`() {
        // Contract lock on the runCatchingPreservingCancellation wrapper:
        // AccentApplicator.apply can throw on a LafManager race / UIManager
        // shutdown; the listener runs inside the EDT publish turn, so a
        // propagated throw becomes an uncaught EDT exception. This test
        // asserts the handler returns normally (WARN-logged internally)
        // instead of rethrowing.
        val project = stubProject()
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        every { AccentApplicator.apply(any()) } throws RuntimeException("LafManager boom")
        val subscriber = installRefresher(project)

        // Must not throw — the load-bearing assertion is simply that the call
        // completes. A regression dropping the runCatching would propagate
        // the RuntimeException and fail this test.
        subscriber.scanCompleted(ScanOutcome.Detected("kotlin"))
    }

    @Test
    fun `refresh swallows a throwing resolver without rethrowing`() {
        // The resolver owns both hit and fallback decisions. A corrupt
        // override, plugin-unload race, or resolver regression must not
        // escape the EDT publish turn as an uncaught EDT exception.
        val project = stubProject()
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } throws RuntimeException("resolver boom")
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        justRun { AccentApplicator.apply(any()) }
        val subscriber = installRefresher(project)

        // Must not throw — the runCatching contains the resolver.
        subscriber.scanCompleted(ScanOutcome.Detected("kotlin"))

        // And because the resolver failed, the apply chain must not be reached.
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `rejected hex suppresses the swap-cache publish`() {
        // applyFromHexString returns false for a rejected hex (user-facing
        // notification already fired inside the applicator). Publishing the
        // rejected value to the swap cache would make the next
        // WINDOW_ACTIVATED treat an unapplied color as current — the Boolean
        // gate must suppress notifyExternalApply.
        val project = stubProject()
        val swapService = wireHappyApplyChain(project)
        every { AccentApplicator.applyFromHexString(any()) } returns false
        val subscriber = installRefresher(project)

        subscriber.scanCompleted(ScanOutcome.Detected("kotlin"))

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `subscription is anchored to the refresher's own disposable`() {
        // Lifetime lock: the MessageBus connection's parent must be the
        // service's own Disposable so the platform tears the subscription
        // down with the project (project services are disposed on close). A
        // connection without this parent would leak the subscriber across
        // plugin reloads.
        val project = stubProject()
        val bus = mockk<MessageBus>()
        every { project.messageBus } returns bus
        val connection = mockk<MessageBusConnection>()
        val parent = slot<Disposable>()
        every { bus.connect(capture(parent)) } returns connection
        every { connection.subscribe(ProjectLanguageDetectionListener.TOPIC, any()) } returns Unit

        val refresher = ScanCompletionAccentRefresher(project)

        assertSame(
            refresher,
            parent.captured,
            "messageBus.connect parent must be the refresher itself so disposal ends the subscription",
        )
        // Platform teardown entry point on project close — must complete cleanly.
        refresher.dispose()
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun stubProject(): Project {
        val project = mockk<Project>()
        every { project.isDisposed } returns false
        return project
    }

    /**
     * Stubs the mocked bus + connection, constructs the refresher through its
     * real `init` subscription, and returns the listener it registered — the
     * exact handler production publishes will hit.
     */
    private fun installRefresher(project: Project): ProjectLanguageDetectionListener {
        val bus = mockk<MessageBus>()
        every { project.messageBus } returns bus
        val connection = mockk<MessageBusConnection>()
        every { bus.connect(any<Disposable>()) } returns connection
        val subscribed = slot<ProjectLanguageDetectionListener>()
        every { connection.subscribe(ProjectLanguageDetectionListener.TOPIC, capture(subscribed)) } returns Unit
        ScanCompletionAccentRefresher(project)
        return subscribed.captured
    }

    /**
     * Mocks the happy resolver → applicator → swap-service chain: MIRAGE
     * variant, `#FFCC66` resolution, focus on [project], stubbed apply.
     * Returns the swap-service mock for publish assertions.
     */
    private fun wireHappyApplyChain(project: Project): ProjectAccentSwapService {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        justRun { AccentApplicator.apply(any()) }
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        mockkObject(ProjectAccentSwapService.Companion)
        every { ProjectAccentSwapService.getInstance() } returns swapService
        return swapService
    }
}
