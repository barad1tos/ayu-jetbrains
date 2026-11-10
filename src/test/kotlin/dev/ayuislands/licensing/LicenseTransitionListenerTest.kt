package dev.ayuislands.licensing

import dev.ayuislands.studio.StudioService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlin.test.Test

class LicenseTransitionListenerTest {
    @Test
    fun `every Marketplace callback enters the shared entitlement lane without a second remembered state`() {
        val service = mockk<StudioService>()
        mockkObject(StudioService.Companion)
        try {
            every { StudioService.getInstance() } returns service
            every { service.refreshEntitlement() } returns Job().apply { complete() }
            val listener = LicenseTransitionListener()
            repeat(3) { listener.licenseStateChanged(null) }
            verify(exactly = 3) { service.refreshEntitlement() }
        } finally {
            unmockkObject(StudioService.Companion)
        }
    }
}
