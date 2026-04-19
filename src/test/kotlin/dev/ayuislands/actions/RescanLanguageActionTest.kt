package dev.ayuislands.actions

import com.intellij.lang.Language
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.ProjectLanguageDetectionListener
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [RescanLanguageAction] through its public AnAction surface.
 *
 * The action is a thin orchestrator: wire a one-shot subscription, call
 * [ProjectLanguageDetector.rescan], and fire a balloon when the detection
 * listener reports completion. All three responsibilities get a behavior-locked
 * test so a refactor that drops any single step (e.g. "rescan without balloon"
 * or "subscribe but forget to disconnect") fails.
 */
class RescanLanguageActionTest {
    private val action = RescanLanguageAction()
    private val presentation = Presentation()
    private val event = mockk<AnActionEvent>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val bus = mockk<MessageBus>(relaxed = true)
    private val connection = mockk<MessageBusConnection>(relaxed = true)
    private val notificationGroupManager = mockk<NotificationGroupManager>(relaxed = true)
    private val notificationGroup = mockk<NotificationGroup>(relaxed = true)
    private val notification = mockk<Notification>(relaxed = true)

    @BeforeTest
    fun setUp() {
        every { event.presentation } returns presentation
        every { event.project } returns project
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.messageBus } returns bus
        every { bus.connect() } returns connection

        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.rescan(project) } returns Unit

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(NotificationGroupManager::class)
        every { NotificationGroupManager.getInstance() } returns notificationGroupManager
        every { notificationGroupManager.getNotificationGroup("Ayu Islands") } returns notificationGroup
        every {
            notificationGroup.createNotification(
                any<String>(),
                any<String>(),
                any<NotificationType>(),
            )
        } returns notification
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ── update() visibility contract ───────────────────────────────────────────

    @Test
    fun `update enables and shows the action on a live non-default project`() {
        action.update(event)

        assertTrue(presentation.isEnabled)
        assertTrue(presentation.isVisible)
    }

    @Test
    fun `update hides the action when no project is focused`() {
        every { event.project } returns null

        action.update(event)

        assertFalse(presentation.isEnabled)
        assertFalse(presentation.isVisible)
    }

    @Test
    fun `update hides the action on the default project`() {
        // IntelliJ's "default project" is the template project that backs global
        // settings — there is no real codebase to rescan, so the action must be
        // hidden entirely.
        every { project.isDefault } returns true

        action.update(event)

        assertFalse(presentation.isEnabled)
        assertFalse(presentation.isVisible)
    }

    @Test
    fun `update hides the action on a disposed project`() {
        every { project.isDisposed } returns true

        action.update(event)

        assertFalse(presentation.isEnabled)
        assertFalse(presentation.isVisible)
    }

    @Test
    fun `update hides the action when the user is unlicensed`() {
        // Rescan is a Pro feature by project policy — every new feature is
        // premium by default per CLAUDE.md. The Tools menu entry must not
        // appear for unlicensed users, and the action must not dispatch
        // even if a stale menu click slips through a BGT update race.
        every { LicenseChecker.isLicensedOrGrace() } returns false

        action.update(event)

        assertFalse(presentation.isEnabled)
        assertFalse(presentation.isVisible)
    }

    @Test
    fun `actionPerformed is a no-op when the user is unlicensed`() {
        // Defense-in-depth: BGT update() should have hidden the action, but
        // the click path re-checks the license so a race between update and
        // click never triggers a rescan for an unlicensed user.
        every { LicenseChecker.isLicensedOrGrace() } returns false

        action.actionPerformed(event)

        verify(exactly = 0) { ProjectLanguageDetector.rescan(any()) }
        verify(exactly = 0) { bus.connect() }
    }

    // ── actionPerformed() dispatch contract ────────────────────────────────────

    @Test
    fun `actionPerformed calls ProjectLanguageDetector rescan exactly once`() {
        action.actionPerformed(event)

        verify(exactly = 1) { ProjectLanguageDetector.rescan(project) }
    }

    @Test
    fun `actionPerformed subscribes to ProjectLanguageDetectionListener TOPIC`() {
        action.actionPerformed(event)

        verify(exactly = 1) {
            connection.subscribe(ProjectLanguageDetectionListener.TOPIC, any())
        }
    }

    @Test
    fun `actionPerformed is a no-op when event has no project`() {
        every { event.project } returns null

        action.actionPerformed(event)

        verify(exactly = 0) { ProjectLanguageDetector.rescan(any()) }
        verify(exactly = 0) { bus.connect() }
    }

    @Test
    fun `actionPerformed is a no-op on a disposed project`() {
        // BGT update() can race with the click; re-check isDisposed on
        // actionPerformed to avoid acting on a project that went away between
        // the visibility poll and the click.
        every { project.isDisposed } returns true

        action.actionPerformed(event)

        verify(exactly = 0) { ProjectLanguageDetector.rescan(any()) }
    }

    // ── completion balloon contract ────────────────────────────────────────────

    @Test
    fun `scan completion with detected id fires a balloon naming the language`() {
        val listener = captureSubscribedListener()
        action.actionPerformed(event)

        mockkStatic(Language::class)
        val kotlin = mockk<Language>()
        every { kotlin.id } returns "kotlin"
        every { kotlin.displayName } returns "Kotlin"
        every { Language.getRegisteredLanguages() } returns listOf(kotlin)

        listener.captured.scanCompleted("kotlin")

        verify(exactly = 1) {
            notificationGroup.createNotification(
                "Project language re-detected",
                "Kotlin",
                NotificationType.INFORMATION,
            )
        }
        verify(exactly = 1) { notification.notify(project) }
    }

    @Test
    fun `scan completion with null id fires a polyglot balloon`() {
        val listener = captureSubscribedListener()
        action.actionPerformed(event)

        listener.captured.scanCompleted(null)

        verify(exactly = 1) {
            notificationGroup.createNotification(
                "Project language re-detected",
                "Polyglot — no single dominant language; global accent applies",
                NotificationType.INFORMATION,
            )
        }
    }

    @Test
    fun `scan completion falls back to raw id when Language registry has no match`() {
        val listener = captureSubscribedListener()
        action.actionPerformed(event)

        mockkStatic(Language::class)
        every { Language.getRegisteredLanguages() } returns emptyList()

        listener.captured.scanCompleted("Exoticlang")

        verify(exactly = 1) {
            notificationGroup.createNotification(
                "Project language re-detected",
                "Exoticlang",
                NotificationType.INFORMATION,
            )
        }
    }

    @Test
    fun `scan completion disconnects the subscription so a second scan does not trigger another balloon`() {
        // One click per balloon — subsequent completions on the same connection
        // must be ignored. The AtomicBoolean latch + disconnect is the contract;
        // a regression that forgets the latch would fire the balloon twice when
        // another scan completes on the shared MessageBus (e.g. the Settings
        // row's subscription).
        val listener = captureSubscribedListener()
        action.actionPerformed(event)

        listener.captured.scanCompleted("kotlin")
        listener.captured.scanCompleted("python")

        verify(exactly = 1) { connection.disconnect() }
        // Only the first balloon fires.
        verify(exactly = 1) { notification.notify(any<Project>()) }
    }

    @Test
    fun `scan completion skips balloon when project has since been disposed`() {
        val listener = captureSubscribedListener()
        action.actionPerformed(event)

        every { project.isDisposed } returns true
        listener.captured.scanCompleted("kotlin")

        verify(exactly = 0) { notification.notify(any<Project>()) }
    }

    // ── defensive swallows (round 2) ───────────────────────────────────────────

    @Test
    fun `connection disconnect failure is swallowed and balloon still fires`() {
        // Defensive: `MessageBusConnection.disconnect()` is documented
        // idempotent but has thrown on `AlreadyDisposedException` in
        // platform regressions. The `runCatchingPreservingCancellation`
        // wrap must absorb the throw so `notifyUser` still runs — a
        // regression that drops the wrap would let the exception bubble
        // out of `scanCompleted` and suppress the user-visible balloon.
        val listener = captureSubscribedListener()
        every { connection.disconnect() } throws IllegalStateException("already disposed")
        action.actionPerformed(event)

        listener.captured.scanCompleted("kotlin")

        verify(exactly = 1) { notification.notify(any<Project>()) }
    }

    @Test
    fun `balloon emit failure is swallowed without rethrow`() {
        // Defensive: `NotificationGroupManager.getNotificationGroup` can
        // fail during a plugin-unload race. The `runCatchingPreservingCancellation`
        // wrap around the balloon emit must contain it so the action
        // doesn't propagate an uncaught EDT exception.
        val listener = captureSubscribedListener()
        every {
            notificationGroup.createNotification(
                any<String>(),
                any<String>(),
                any<NotificationType>(),
            )
        } throws RuntimeException("plugin unload race")
        action.actionPerformed(event)

        // Must not throw — load-bearing assertion is simply that the call completes.
        listener.captured.scanCompleted("kotlin")
    }

    @Test
    fun `balloon shows raw id when Language registry throws`() {
        // Defensive: a third-party plugin throwing from
        // `Language.getRegisteredLanguages()` during `humanLabelFor` must
        // fall through to the raw id so the balloon still fires with
        // *some* label. Previously the `runCatching` onFailure was
        // untested — a regression narrowing the wrap would regress to an
        // uncaught EDT exception. Uses a made-up id to keep spell-check
        // quiet; the raw-id passthrough is language-agnostic.
        val listener = captureSubscribedListener()
        mockkStatic(Language::class)
        every { Language.getRegisteredLanguages() } throws RuntimeException("plugin registry corrupted")
        action.actionPerformed(event)

        listener.captured.scanCompleted("Exoticlang")

        verify(exactly = 1) {
            notificationGroup.createNotification(
                "Project language re-detected",
                "Exoticlang",
                NotificationType.INFORMATION,
            )
        }
    }

    @Test
    fun `balloon shows raw id when Language displayName is blank`() {
        // Defensive: the `.takeIf { it.isNotBlank() }` guard must fall
        // through to the raw id when a registered Language reports a
        // blank displayName (pathological third-party plugin). Without
        // the guard the balloon would show an empty body.
        val listener = captureSubscribedListener()
        mockkStatic(Language::class)
        val blankLang = mockk<Language>()
        every { blankLang.id } returns "Exoticlang"
        every { blankLang.displayName } returns "   "
        every { Language.getRegisteredLanguages() } returns listOf(blankLang)
        action.actionPerformed(event)

        listener.captured.scanCompleted("Exoticlang")

        verify(exactly = 1) {
            notificationGroup.createNotification(
                "Project language re-detected",
                "Exoticlang",
                NotificationType.INFORMATION,
            )
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun captureSubscribedListener(): CapturingSlot<ProjectLanguageDetectionListener> {
        val slot = slot<ProjectLanguageDetectionListener>()
        every {
            connection.subscribe(ProjectLanguageDetectionListener.TOPIC, capture(slot))
        } returns Unit
        return slot
    }
}
