package dev.ayuislands.rotation

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Condition
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.EnumSet
import kotlin.test.AfterTest
import kotlin.test.assertFalse

class AccentRotationServiceTest {
    @Test
    fun `nextPresetHex wraps from last preset back to first`() {
        val (index, hex) = nextPresetHex(AYU_ACCENT_PRESETS.size - 1)
        assertEquals(0, index)
        assertEquals(AYU_ACCENT_PRESETS[0].hex, hex)
    }

    @Test
    fun `nextPresetHex advances index by one`() {
        val (index, hex) = nextPresetHex(0)
        assertEquals(1, index)
        assertEquals(AYU_ACCENT_PRESETS[1].hex, hex)
    }

    @Test
    fun `nextPresetHex wraps index 11 to 0 for 12 presets`() {
        assertEquals(12, AYU_ACCENT_PRESETS.size, "Expected 12 presets")
        val (index, _) = nextPresetHex(11)
        assertEquals(0, index)
    }

    @Test
    fun `nextPresetHex returns valid hex for every index`() {
        for (i in AYU_ACCENT_PRESETS.indices) {
            val (_, hex) = nextPresetHex(i)
            assertTrue(hex.matches(Regex("#[0-9A-Fa-f]{6}")), "Invalid hex: $hex at index $i")
        }
    }

    @Test
    fun `fromName PRESET round-trips`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName("PRESET"))
    }

    @Test
    fun `fromName RANDOM round-trips`() {
        assertEquals(AccentRotationMode.RANDOM, AccentRotationMode.fromName("RANDOM"))
    }

    @Test
    fun `fromName returns PRESET for null`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName(null))
    }

    @Test
    fun `fromName returns PRESET for unknown string`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName("garbage"))
    }

    @Test
    fun `all modes round-trip through name`() {
        for (mode in AccentRotationMode.entries) {
            assertEquals(mode, AccentRotationMode.fromName(mode.name))
        }
    }

    // Scheduler lifecycle tests drive rotateAccent() directly rather than
    // going through a real ScheduledExecutorService — the 1-hour interval
    // makes timer-based testing impractical and flaky.

    private lateinit var state: AyuIslandsState
    private lateinit var settingsMock: AyuIslandsSettings
    private val appMock = mockk<com.intellij.openapi.application.Application>(relaxed = true)

    private fun mockRotationEnvironment() {
        state = AyuIslandsState()
        settingsMock = mockk(relaxed = true)

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns appMock
        // 3-arg invokeLater(Runnable, ModalityState, Condition) — fire synchronously
        every {
            appMock.invokeLater(any<Runnable>(), any<ModalityState>(), any<Condition<*>>())
        } answers {
            firstArg<Runnable>().run()
        }

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns state
        // setAccentForVariant must persist into the state fields so the subsequent
        // AccentResolver fallback (which reads getAccentForVariant) sees the rotated value.
        every { settingsMock.setAccentForVariant(any(), any()) } answers {
            val variant = firstArg<AyuVariant>()
            val hex = secondArg<String>()
            when (variant) {
                AyuVariant.MIRAGE -> state.mirageAccent = hex
                AyuVariant.DARK -> state.darkAccent = hex
                AyuVariant.LIGHT -> state.lightAccent = hex
            }
        }
        every { settingsMock.getAccentForVariant(any()) } answers {
            when (val variant = firstArg<AyuVariant>()) {
                AyuVariant.MIRAGE -> state.mirageAccent ?: variant.defaultAccent
                AyuVariant.DARK -> state.darkAccent ?: variant.defaultAccent
                AyuVariant.LIGHT -> state.lightAccent ?: variant.defaultAccent
            }
        }

        // AccentResolver reads AccentMappingsSettings and ProjectManager. Both must be mocked
        // so rotation's apply path resolves cleanly to the (freshly-set) global hex with no
        // project overrides in play.
        val mappingsSettings = mockk<AccentMappingsSettings>()
        every { mappingsSettings.state } returns AccentMappingsState()
        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns mappingsSettings

        val projectManager = mockk<com.intellij.openapi.project.ProjectManager>()
        every { projectManager.openProjects } returns emptyArray()
        mockkStatic(com.intellij.openapi.project.ProjectManager::class)
        every {
            com.intellij.openapi.project.ProjectManager
                .getInstance()
        } returns projectManager

        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        mockkObject(ProjectAccentSwapService.Companion)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } just Runs

        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just Runs

        mockkObject(ContrastAwareColorGenerator)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `canRotate returns false when rotation flag disabled`() {
        mockRotationEnvironment()
        state.accentRotationEnabled = false
        every { LicenseChecker.isLicensedOrGrace() } returns true

        val service = AccentRotationService()

        assertFalse(service.canRotate())
    }

    @Test
    fun `canRotate returns false when not licensed`() {
        mockRotationEnvironment()
        state.accentRotationEnabled = true
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val service = AccentRotationService()

        assertFalse(service.canRotate())
    }

    @Test
    fun `canRotate returns true when enabled and licensed`() {
        mockRotationEnvironment()
        state.accentRotationEnabled = true
        every { LicenseChecker.isLicensedOrGrace() } returns true

        val service = AccentRotationService()

        assertTrue(service.canRotate())
    }

    @Test
    fun `rotateAccent applies next preset and updates state in PRESET mode`() {
        mockRotationEnvironment()
        state.accentRotationEnabled = true
        state.accentRotationMode = AccentRotationMode.PRESET.name
        state.accentRotationPresetIndex = 0
        state.accentRotationLastSwitchMs = 0L

        val expectedNext = nextPresetHex(0)
        val expectedHex = expectedNext.second
        val expectedIndex = expectedNext.first

        val before = System.currentTimeMillis()
        val service = AccentRotationService()
        service.rotateAccent()
        val after = System.currentTimeMillis()

        verify(exactly = 1) { AccentApplicator.apply(expectedHex) }
        verify(exactly = 1) { settingsMock.setAccentForVariant(AyuVariant.MIRAGE, expectedHex) }
        assertEquals(expectedIndex, state.accentRotationPresetIndex)
        assertTrue(
            state.accentRotationLastSwitchMs in before..after,
            "lastSwitchMs should be updated to a value in the call window",
        )
    }

    @Test
    fun `rotateAccent uses generator in RANDOM mode`() {
        mockRotationEnvironment()
        state.accentRotationEnabled = true
        state.accentRotationMode = AccentRotationMode.RANDOM.name
        state.accentRotationPresetIndex = 3 // must remain untouched in RANDOM mode

        val generatedHex = "#123456"
        every { ContrastAwareColorGenerator.generate(AyuVariant.MIRAGE) } returns generatedHex

        val service = AccentRotationService()
        service.rotateAccent()

        verify(exactly = 1) { ContrastAwareColorGenerator.generate(AyuVariant.MIRAGE) }
        verify(exactly = 1) { AccentApplicator.apply(generatedHex) }
        verify(exactly = 1) { settingsMock.setAccentForVariant(AyuVariant.MIRAGE, generatedHex) }
        assertEquals(3, state.accentRotationPresetIndex, "PRESET index must not advance in RANDOM mode")
    }

    @Test
    fun `rotateAccent skips when canRotate is false`() {
        mockRotationEnvironment()
        state.accentRotationEnabled = false // disabled mid-rotation
        every { LicenseChecker.isLicensedOrGrace() } returns true

        val service = AccentRotationService()
        service.rotateAccent()

        verify(exactly = 0) { AccentApplicator.apply(any()) }
        verify(exactly = 0) { settingsMock.setAccentForVariant(any(), any()) }
    }

    @Test
    fun `three consecutive failures trip the circuit breaker and notify the user`() {
        // Regression guard for commit e8345db: MAX_CONSECUTIVE_FAILURES = 3. Flipping the
        // off-by-one, dropping the reset on success, or removing the notification should
        // fail this test instead of shipping a silently-broken rotation feature.
        mockRotationEnvironment()
        val (notificationGroup, createdNotifications) = captureNotifications()
        state.accentRotationEnabled = true
        state.accentRotationMode = AccentRotationMode.PRESET.name
        every { AccentApplicator.apply(any()) } throws RuntimeException("stage=apply failed")

        val suppressLoggedErrors =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> = EnumSet.noneOf(Action::class.java)
            }

        val service = AccentRotationService()
        LoggedErrorProcessor.executeWith<RuntimeException>(suppressLoggedErrors) {
            repeat(THREE) { service.rotateAccent() }
        }

        // Notification fires exactly once — on the third consecutive failure, not earlier, not
        // again on a hypothetical fourth tick after the breaker tripped.
        verify(exactly = 1) {
            notificationGroup.createNotification(any<String>(), any<String>(), NotificationType.WARNING)
        }
        assertEquals(1, createdNotifications.size)
    }

    @Test
    fun `successful tick between failures resets the circuit-breaker budget`() {
        // Regression guard for the reset-on-success path (consecutiveFailures = 0 after a
        // clean runApplyStage). Two fail + one success + two fail must NOT trip the breaker
        // because consecutive-failures counts go 1, 2, 0, 1, 2 — never reaches 3.
        mockRotationEnvironment()
        val (notificationGroup, _) = captureNotifications()
        state.accentRotationEnabled = true
        state.accentRotationMode = AccentRotationMode.PRESET.name

        val applyCalls = mutableListOf<String>()
        every { AccentApplicator.apply(any()) } answers {
            applyCalls += firstArg<String>()
            if (applyCalls.size == THREE) return@answers Unit
            @Suppress("TooGenericExceptionThrown") // Test-only simulation of applicator failure
            throw RuntimeException("fail ${applyCalls.size}")
        }

        val suppressLoggedErrors =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> = EnumSet.noneOf(Action::class.java)
            }

        val service = AccentRotationService()
        LoggedErrorProcessor.executeWith<RuntimeException>(suppressLoggedErrors) {
            repeat(FIVE) { service.rotateAccent() }
        }

        verify(exactly = 0) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    /**
     * Wire `NotificationGroupManager.getInstance().getNotificationGroup("Ayu Islands")` to a
     * relaxed mock and return the group plus a capture list so tests can assert the circuit
     * breaker's user-visible outcome without needing the real notification subsystem.
     */
    private fun captureNotifications(): Pair<NotificationGroup, MutableList<Notification>> {
        val group = mockk<NotificationGroup>(relaxed = true)
        val captured = mutableListOf<Notification>()
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } answers {
            val notification = mockk<Notification>(relaxed = true)
            captured += notification
            notification
        }
        val notificationManager = mockk<NotificationGroupManager>()
        every { notificationManager.getNotificationGroup("Ayu Islands") } returns group
        mockkStatic(NotificationGroupManager::class)
        every { NotificationGroupManager.getInstance() } returns notificationManager
        return group to captured
    }

    @Test
    fun `rotateAccent swallows AccentApplicator failures and still syncs glow`() {
        mockRotationEnvironment()
        state.accentRotationEnabled = true
        state.accentRotationMode = AccentRotationMode.PRESET.name
        state.accentRotationPresetIndex = 0

        every { AccentApplicator.apply(any()) } throws RuntimeException("boom")

        // IntelliJ's TestLoggerFactory promotes LOG.error calls into AssertionErrors.
        // rotateAccent() is expected to log the AccentApplicator failure via LOG.error,
        // so we install a LoggedErrorProcessor that suppresses (but counts) the promotion.
        val loggedErrors = mutableListOf<Throwable?>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> {
                    loggedErrors += throwable
                    return EnumSet.noneOf(Action::class.java)
                }
            }

        val service = AccentRotationService()
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            // Must not propagate — rotateAccent catches RuntimeException inside invokeLater.
            service.rotateAccent()
        }

        verify(exactly = 1) { AccentApplicator.apply(any()) }
        // Glow sync runs in a separate try/catch and should still fire after apply() fails.
        verify(exactly = 1) { GlowOverlayManager.syncGlowForAllProjects() }
        assertEquals(1, loggedErrors.size, "Expected exactly one LOG.error for the failed apply()")
        assertEquals("boom", loggedErrors.single()?.message)
    }

    companion object {
        private const val THREE = 3
        private const val FIVE = 5
    }
}
