package dev.ayuislands

import dev.ayuislands.studio.StudioService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlin.test.AfterTest
import kotlin.test.Test

class AyuIslandsAppListenerTest {
    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `first frame opens the existing application authority before native appearance work`() {
        val studio = mockk<StudioService>()
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns studio
        every { studio.start() } returns Job().apply { complete() }

        AyuIslandsAppListener().appFrameCreated(mutableListOf())

        verify(exactly = 1) { studio.start() }
        verify(exactly = 0) { studio.nativeChanged(any()) }
    }
}
