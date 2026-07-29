package dev.ayuislands.licensing

import com.intellij.util.Alarm
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class LicenseRecheckSchedulerTest {
    @Test
    fun `independent retry slots do not cancel each other`() {
        val startupAlarm = mockk<Alarm>(relaxed = true)
        val transitionAlarm = mockk<Alarm>(relaxed = true)
        val alarms =
            mapOf(
                LicenseRecheckSlot.STARTUP to startupAlarm,
                LicenseRecheckSlot.TRANSITION to transitionAlarm,
            )
        val scheduler = LicenseRecheckScheduler { slot, _ -> alarms.getValue(slot) }

        scheduler.schedule(LicenseRecheckSlot.STARTUP, 5_000L) {}
        scheduler.schedule(LicenseRecheckSlot.TRANSITION, 100_000L) {}

        verify(exactly = 1) { startupAlarm.cancelAllRequests() }
        verify(exactly = 1) { startupAlarm.addRequest(any<Runnable>(), 5_000) }
        verify(exactly = 1) { transitionAlarm.cancelAllRequests() }
        verify(exactly = 1) { transitionAlarm.addRequest(any<Runnable>(), 100_000) }
    }
}
