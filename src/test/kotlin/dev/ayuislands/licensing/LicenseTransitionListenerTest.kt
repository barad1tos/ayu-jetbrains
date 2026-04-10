package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseTransitionListenerTest {
    private lateinit var state: AyuIslandsState
    private val facade: LicensingFacade = mockk(relaxed = true)

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settingsMock = mockk<AyuIslandsSettings>()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns state
        mockkObject(LicenseChecker)
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<com.intellij.openapi.application.Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unlicensed transition does not flip premiumOnboardingShown`() {
        state.premiumOnboardingShown = true
        every { LicenseChecker.isLicensed() } returns false

        LicenseTransitionListener().licenseStateChanged(facade)

        assertTrue(state.premiumOnboardingShown)
    }

    @Test
    fun `licensed transition flips premiumOnboardingShown to false`() {
        state.premiumOnboardingShown = true
        every { LicenseChecker.isLicensed() } returns true

        LicenseTransitionListener().licenseStateChanged(facade)

        assertFalse(state.premiumOnboardingShown)
    }

    @Test
    fun `licensed transition with flag already false is a no-op`() {
        state.premiumOnboardingShown = false
        every { LicenseChecker.isLicensed() } returns true

        LicenseTransitionListener().licenseStateChanged(facade)

        assertFalse(state.premiumOnboardingShown)
    }
}
