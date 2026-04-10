package dev.ayuislands.licensing

import com.intellij.openapi.application.PathManager
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LicenseCheckerTest {
    private val defaultSettings =
        io.mockk.mockk<AyuIslandsSettings>(relaxed = true).also {
            every { it.state } returns AyuIslandsState()
        }

    @BeforeTest
    fun setUp() {
        mockkStatic(PathManager::class)
        mockkStatic(LicensingFacade::class)
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns defaultSettings
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("ayu.islands.dev")
        unmockkAll()
    }

    @Test
    fun `isLicensed returns true when dev property set and in sandbox`() {
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns "/home/user/.gradle/idea-sandbox/config"

        val result = LicenseChecker.isLicensed()

        assertEquals(true, result)
    }

    @Test
    fun `isLicensed does not shortcut when dev property set but not in sandbox`() {
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        every { LicensingFacade.getInstance() } returns null

        val result = LicenseChecker.isLicensed()

        // Dev shortcut skipped because not in sandbox; facade is null so returns null
        assertNull(result)
    }

    @Test
    fun `isLicensed does not shortcut when dev property absent`() {
        // ayu.islands.dev not set — cleared in tearDown
        every { LicensingFacade.getInstance() } returns null

        val result = LicenseChecker.isLicensed()

        assertNull(result)
    }

    @Test
    fun `isLicensed returns null when facade not initialized`() {
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        every { LicensingFacade.getInstance() } returns null

        val result = LicenseChecker.isLicensed()

        assertNull(result)
    }

    @Test
    fun `isLicensed returns false when no confirmation stamp`() {
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        val facade = io.mockk.mockk<LicensingFacade>()
        every { LicensingFacade.getInstance() } returns facade
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns null

        val result = LicenseChecker.isLicensed()

        assertEquals(false, result)
    }

    @Test
    fun `isLicensed returns true for eval stamp`() {
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        val facade = io.mockk.mockk<LicensingFacade>()
        every { LicensingFacade.getInstance() } returns facade
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "eval:12345"

        val result = LicenseChecker.isLicensed()

        assertEquals(true, result)
    }

    @Test
    fun `isLicensed returns false for unknown stamp prefix`() {
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        val facade = io.mockk.mockk<LicensingFacade>()
        every { LicensingFacade.getInstance() } returns facade
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "unknown:data"

        val result = LicenseChecker.isLicensed()

        assertEquals(false, result)
    }

    @Test
    fun `isLicensedOrGrace returns true when facade null`() {
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        every { LicensingFacade.getInstance() } returns null

        val result = LicenseChecker.isLicensedOrGrace()

        // null (facade not initialized) is treated as grace period -> true
        assertTrue(result)
    }

    @Test
    fun `isLicensedOrGrace returns false when explicitly not licensed`() {
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        val facade = io.mockk.mockk<LicensingFacade>()
        every { LicensingFacade.getInstance() } returns facade
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns null

        val result = LicenseChecker.isLicensedOrGrace()

        // No stamp -> isLicensed() returns false -> isLicensedOrGrace() returns false
        assertFalse(result)
    }

    @Test
    fun `enableProDefaults sets all glow flags to true`() {
        val realState = AyuIslandsState()
        val settingsMock = io.mockk.mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns realState

        // Verify defaults before — glowEnabled and proDefaultsApplied start false,
        // but all island toggles default to true (visibility controlled by glowEnabled)
        assertFalse(realState.glowEnabled)
        assertTrue(realState.glowEditor)
        assertTrue(realState.glowProject)
        assertTrue(realState.glowTerminal)
        assertTrue(realState.glowRun)
        assertTrue(realState.glowDebug)
        assertTrue(realState.glowGit)
        assertTrue(realState.glowServices)
        assertFalse(realState.proDefaultsApplied)

        LicenseChecker.enableProDefaults()

        assertTrue(realState.glowEnabled)
        assertTrue(realState.glowEditor)
        assertTrue(realState.glowProject)
        assertTrue(realState.glowTerminal)
        assertTrue(realState.glowRun)
        assertTrue(realState.glowDebug)
        assertTrue(realState.glowGit)
        assertTrue(realState.glowServices)
        assertTrue(realState.glowFocusRing)
        assertTrue(realState.proDefaultsApplied)
    }
}
