package dev.ayuislands

import com.intellij.openapi.wm.IdeFrame
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.persistence.StudioLegacyView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AppearanceSyncListenerTest {
    private val studio = mockk<StudioService>()
    private val frame = mockk<IdeFrame>()
    private val listener = AppearanceSyncListener()

    @BeforeTest
    fun setUp() {
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns studio
        every { studio.nativeChanged(any()) } returns Job().apply { complete() }
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `missing accepted authority never invents a global Follow preference`() {
        every { studio.acceptedView } returns null
        listener.applicationActivated(frame)
        verify(exactly = 0) { studio.nativeChanged(any()) }
    }

    @Test
    fun `Follow activation reads the latest accepted gate and delegates the system observation`() {
        val view = mockk<StudioLegacyView>()
        every { studio.acceptedView } returns view
        every { view.settings.followSystemAppearance } returns false
        listener.applicationActivated(frame)
        verify(exactly = 0) { studio.nativeChanged(any()) }

        every { view.settings.followSystemAppearance } returns true
        listener.applicationActivated(frame)
        verify(exactly = 1) {
            studio.nativeChanged(StudioNativeChange.Theme(StudioThemeChange.FollowSystem))
        }

        every { view.settings.followSystemAppearance } returns false
        listener.applicationActivated(frame)
        verify(exactly = 1) { studio.nativeChanged(any()) }
    }
}
