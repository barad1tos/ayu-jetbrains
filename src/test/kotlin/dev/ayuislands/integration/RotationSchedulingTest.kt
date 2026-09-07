package dev.ayuislands.integration

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class RotationSchedulingTest : BasePlatformTestCase() {
    fun testConfiguredStartReplacesScheduleAndStopIsIdempotent() =
        withRotationFixture { fixture ->
            fixture.configureUserState()
            val expectedXml = fixture.encodedState()

            LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.LICENSED) {
                fixture.service.startRotation()
                val first = fixture.scheduler.registrations.single()
                assertSchedule(first, CONFIGURED_INTERVAL_HOURS, CONFIGURED_INTERVAL_HOURS, TimeUnit.HOURS)

                fixture.service.startRotation()

                assertTrue("Repeated start must cancel the previous timer", first.future.isCancelled)
                assertEquals(2, fixture.scheduler.registrations.size)
                val replacement = fixture.scheduler.registrations.last()
                assertSchedule(
                    replacement,
                    CONFIGURED_INTERVAL_HOURS,
                    CONFIGURED_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                )
                assertFalse("The replacement timer must remain active", replacement.future.isCancelled)

                fixture.service.stopRotation()
                fixture.service.stopRotation()

                assertTrue("Stop must cancel the current timer", replacement.future.isCancelled)
            }

            assertEquals(expectedXml, fixture.encodedState())
        }

    fun testDelayedResumeClampsRuntimeIntervalAndDisposeCancelsTimer() =
        withRotationFixture { fixture ->
            fixture.configureUserState()
            val configuredXml = fixture.encodedState()

            LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.LICENSED) {
                fixture.service.startRotationWithDelay(RESUME_DELAY_MS)
                val configured = fixture.scheduler.registrations.single()
                assertSchedule(
                    configured,
                    RESUME_DELAY_MS,
                    CONFIGURED_INTERVAL_HOURS * MS_PER_HOUR,
                    TimeUnit.MILLISECONDS,
                )
                assertEquals(configuredXml, fixture.encodedState())

                fixture.settings.state.accentRotationIntervalHours = 0
                val zeroIntervalXml = fixture.encodedState()
                fixture.service.startRotationWithDelay(RESUME_DELAY_MS)

                assertTrue("Replacing delayed resume must cancel the previous timer", configured.future.isCancelled)
                val clamped = fixture.scheduler.registrations.last()
                assertSchedule(clamped, RESUME_DELAY_MS, MS_PER_HOUR, TimeUnit.MILLISECONDS)
                assertEquals(zeroIntervalXml, fixture.encodedState())

                fixture.disposeService()

                assertTrue("Disposing the service must cancel its actual future", clamped.future.isCancelled)
                assertEquals(zeroIntervalXml, fixture.encodedState())
            }
        }

    fun testDisableReloadAndReenableRestoreExactUserState() =
        withRotationFixture { fixture ->
            fixture.configureUserState()

            LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.LICENSED) {
                fixture.service.startRotation()
                val enabled = fixture.scheduler.registrations.single()

                fixture.settings.state.accentRotationEnabled = false
                val disabledXml = fixture.encodedState()
                fixture.service.startRotation()

                assertTrue("Disabling rotation must cancel the active timer", enabled.future.isCancelled)
                assertEquals(
                    "Disabled rotation must not register another timer",
                    1,
                    fixture.scheduler.registrations.size,
                )
                assertEquals(disabledXml, fixture.encodedState())

                fixture.reloadState(disabledXml)

                assertEquals(disabledXml, fixture.encodedState())
                assertEquals(
                    EXPECTED_CUSTOMIZATION_KEYS,
                    fixture.settings.state.fontPresetCustomizations.keys
                        .toList(),
                )

                fixture.settings.state.accentRotationEnabled = true
                val reenabledXml = fixture.encodedState()
                fixture.service.startRotation()

                assertEquals(2, fixture.scheduler.registrations.size)
                val restored = fixture.scheduler.registrations.last()
                assertSchedule(
                    restored,
                    CONFIGURED_INTERVAL_HOURS,
                    CONFIGURED_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                )
                assertFalse("Re-enabling rotation must leave the restored timer active", restored.future.isCancelled)
                assertEquals(reenabledXml, fixture.encodedState())
            }
        }

    fun testUnlicensedStartCancelsTimerWithoutChangingEnabledPreference() =
        withRotationFixture { fixture ->
            fixture.configureUserState()
            LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.LICENSED) {
                fixture.service.startRotation()
            }
            val licensedTimer = fixture.scheduler.registrations.single()
            val enabledXml = fixture.encodedState()

            LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.UNLICENSED) {
                fixture.service.startRotation()
            }

            assertTrue("License loss must cancel paid runtime work", licensedTimer.future.isCancelled)
            assertEquals("License loss must not create another timer", 1, fixture.scheduler.registrations.size)
            assertTrue(
                "License loss must preserve the user's enabled preference",
                fixture.settings.state.accentRotationEnabled,
            )
            assertEquals(enabledXml, fixture.encodedState())
        }

    fun testRotateNowOnExternalLafReplacesTimerWithoutApplyingAccent() =
        withRotationFixture { fixture ->
            fixture.configureUserState()
            assertNull("The fixture must remain on an external look and feel", AyuVariant.detect())
            LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.LICENSED) {
                fixture.service.startRotation()
                val previous = fixture.scheduler.registrations.single()
                val expectedXml = fixture.encodedState()

                fixture.service.rotateNow()

                assertTrue("Rotate now must cancel the previous timer", previous.future.isCancelled)
                assertEquals(2, fixture.scheduler.registrations.size)
                val replacement = fixture.scheduler.registrations.last()
                assertSchedule(
                    replacement,
                    CONFIGURED_INTERVAL_HOURS,
                    CONFIGURED_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                )
                assertFalse("Rotate now must leave one replacement timer active", replacement.future.isCancelled)
                assertEquals(expectedXml, fixture.encodedState())
            }
        }

    private fun withRotationFixture(testBody: (RotationFixture) -> Unit) {
        val settings = AyuIslandsSettings.getInstance()
        val originalXml = encodedState(settings.state)
        val scheduler = RecordingScheduler()
        val fixture = RotationFixture(settings, originalXml, scheduler)

        fixture.use { activeFixture ->
            mockkStatic(AppExecutorUtil::class)
            every { AppExecutorUtil.getAppScheduledExecutorService() } returns scheduler
            testBody(activeFixture)
        }
    }

    private fun assertSchedule(
        scheduled: ScheduledRotation,
        expectedInitialDelay: Long,
        expectedDelay: Long,
        expectedUnit: TimeUnit,
    ) {
        assertEquals(expectedInitialDelay, scheduled.initialDelay)
        assertEquals(expectedDelay, scheduled.delay)
        assertEquals(expectedUnit, scheduled.unit)
    }

    private class RotationFixture(
        val settings: AyuIslandsSettings,
        private val originalXml: String,
        val scheduler: RecordingScheduler,
    ) : AutoCloseable {
        val service = AccentRotationService()
        private var isServiceDisposed = false
        private var isClosed = false

        fun configureUserState() {
            settings.state.apply {
                accentRotationEnabled = true
                accentRotationIntervalHours = CONFIGURED_INTERVAL_HOURS.toInt()
                accentRotationMode = AccentRotationMode.RANDOM.name
                accentRotationPresetIndex = 7
                accentRotationLastSwitchMs = 1_725_184_800_000L
                mirageAccent = "#5B8DEF"
                darkAccent = "#73D0FF"
                lightAccent = "#FF8F40"
                lastShuffleColor = "#D4BFFF"
                fontPresetEnabled = true
                fontPresetName = FUTURE_PRESET
                fontPresetCustomizations.clear()
                fontPresetCustomizations[FUTURE_PRESET] = FUTURE_CUSTOMIZATION
                fontPresetCustomizations["AMBIENT"] = "15.00|1.10|true|REGULAR|JetBrains Mono"
            }
        }

        fun encodedState(): String = encodedState(settings.state)

        fun reloadState(xml: String) {
            settings.loadState(decodedState(xml))
        }

        fun disposeService() {
            if (isServiceDisposed) return
            Disposer.dispose(service)
            isServiceDisposed = true
        }

        override fun close() {
            if (isClosed) return
            isClosed = true
            var cleanupFailure: Throwable? = null
            val cleanupSteps =
                listOf(
                    ::disposeService,
                    {
                        scheduler.shutdownNow()
                        check(scheduler.awaitTermination(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            "Rotation test scheduler did not terminate"
                        }
                    },
                    { unmockkStatic(AppExecutorUtil::class) },
                    { settings.loadState(decodedState(originalXml)) },
                )
            for (cleanup in cleanupSteps) {
                try {
                    cleanup()
                } catch (failure: Throwable) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure
                    } else {
                        cleanupFailure.addSuppressed(failure)
                    }
                }
            }
            cleanupFailure?.let { throw it }
        }
    }

    private data class ScheduledRotation(
        val initialDelay: Long,
        val delay: Long,
        val unit: TimeUnit,
        val future: ScheduledFuture<*>,
    )

    private class RecordingScheduler : ScheduledThreadPoolExecutor(1) {
        val registrations = mutableListOf<ScheduledRotation>()

        override fun scheduleWithFixedDelay(
            command: Runnable,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            val future = super.scheduleWithFixedDelay(command, initialDelay, delay, unit)
            registrations += ScheduledRotation(initialDelay, delay, unit, future)
            return future
        }
    }

    private companion object {
        const val CONFIGURED_INTERVAL_HOURS = 6L
        const val MS_PER_HOUR = 3_600_000L
        const val RESUME_DELAY_MS = 37L * 60L * 1_000L
        const val CLEANUP_TIMEOUT_SECONDS = 5L
        const val FUTURE_PRESET = "FUTURE_PRESET"
        const val FUTURE_CUSTOMIZATION =
            "21.00|1.37|true|FUTURE_WEIGHT|Unavailable Font|extension=42"
        val EXPECTED_CUSTOMIZATION_KEYS = listOf(FUTURE_PRESET, "AMBIENT")
    }
}

private fun encodedState(state: AyuIslandsState): String = JDOMUtil.writeElement(XmlSerializer.serialize(state))

private fun decodedState(xml: String): AyuIslandsState =
    XmlSerializer.deserialize(JDOMUtil.load(xml), AyuIslandsState::class.java)
