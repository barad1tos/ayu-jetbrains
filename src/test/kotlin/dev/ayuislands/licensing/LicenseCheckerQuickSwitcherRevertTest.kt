package dev.ayuislands.licensing

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.vcs.VcsColorApplier
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pattern G symmetric-reset lock for `state.quickSwitcherWidgetEnabled` (default ON,
 * revert symmetry on downgrade). Mirrors the harness of [LicenseCheckerVcsRevokeTest].
 *
 * A user who hides the chip while licensed and then downgrades would otherwise lose their
 * only Settings affordance to re-enable it (the chip is the entry point to the popup; with
 * `quickSwitcherWidgetEnabled = false` and the field hidden from Settings, the toggle is
 * effectively orphaned). The Pattern G reset restores the free-tier default on downgrade.
 */
class LicenseCheckerQuickSwitcherRevertTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeEach
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns "#FFCC66"

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(AccentApplicator)
        every { AccentApplicator.applyFromHexString(any()) } returns true

        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs

        mockkObject(VcsColorApplier)
        every { VcsColorApplier.revertAll() } just runs

        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        every { app.getService(AccentRotationService::class.java) } returns rotationService

        mockkStatic(NotificationGroupManager::class)
        val ngm = mockk<NotificationGroupManager>()
        val group = mockk<NotificationGroup>()
        val notification = mockk<Notification>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns ngm
        every { ngm.getNotificationGroup(any()) } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        every { notification.notify(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `revertToFreeDefaults resets quickSwitcherWidgetEnabled to true (free-tier default)`() {
        state.quickSwitcherWidgetEnabled = false
        assertEquals(false, state.quickSwitcherWidgetEnabled)

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals(
            true,
            state.quickSwitcherWidgetEnabled,
            "Pattern G symmetric reset must restore the chip to its free-tier default (visible)",
        )
    }

    @Test
    fun `revertToFreeDefaults is idempotent on quickSwitcherWidgetEnabled when already default`() {
        state.quickSwitcherWidgetEnabled = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertEquals(true, state.quickSwitcherWidgetEnabled)
    }

    @Test
    fun `quickSwitcherWidgetEnabled reset lives inside the synchronized state block (Pattern G)`() {
        // Pattern G + DoS lock: the reset line MUST land inside
        // `synchronized(state) { ... }`. Outside the lock races with concurrent Settings
        // reads (the chip's BGT update tick polls `state.quickSwitcherWidgetEnabled`
        // every ~500 ms). Verified by line-number brace-counting.
        val source =
            FileUtil.loadFile(File("src/main/kotlin/dev/ayuislands/licensing/LicenseChecker.kt"))
        val lines = source.lines()
        val resetIdx =
            lines.indexOfFirst { it.contains("state.quickSwitcherWidgetEnabled = true") }
        require(resetIdx >= 0) { "Reset line not found in LicenseChecker.kt" }

        val syncStart =
            (0..resetIdx)
                .reversed()
                .firstOrNull { lines[it].contains("synchronized(state)") }
                ?: error("synchronized(state) block start not found before reset line")

        var depth = 0
        var syncEnd = -1
        for (i in syncStart..lines.lastIndex) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth == 0 && i > syncStart) {
                syncEnd = i
                break
            }
        }
        require(syncEnd > syncStart) { "synchronized(state) closing brace not located" }

        assertTrue(
            resetIdx in syncStart..syncEnd,
            "state.quickSwitcherWidgetEnabled = true must be INSIDE synchronized(state) " +
                "(reset at line ${resetIdx + 1}, synchronized at ${syncStart + 1}-${syncEnd + 1})",
        )
    }
}
