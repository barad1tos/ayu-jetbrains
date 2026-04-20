package dev.ayuislands.licensing

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.nio.file.Path
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
        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns defaultSettings
    }

    private fun mockSandboxPluginPath() {
        val descriptor = mockk<IdeaPluginDescriptor>()
        val path = mockk<Path>()
        every { path.toString() } returns "/repo/build/idea-sandbox/plugins/ayuIslands/lib/ayuIslands.jar"
        every { descriptor.pluginPath } returns path
        every { PluginManagerCore.getPlugin(PluginId.getId("com.ayuislands.theme")) } returns descriptor
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("ayu.islands.dev")
        unmockkAll()
    }

    @Test
    fun `isLicensed returns true when all three dev gates match`() {
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns "/home/user/.gradle/idea-sandbox/config"
        mockSandboxPluginPath()

        val result = LicenseChecker.isLicensed()

        assertEquals(true, result)
    }

    @Test
    fun `isLicensed does not shortcut when dev property set but not in sandbox config path`() {
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        mockSandboxPluginPath()
        every { LicensingFacade.getInstance() } returns null

        val result = LicenseChecker.isLicensed()

        // Dev shortcut skipped because config path is not a sandbox; facade is null so returns null
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

    // ---------- Grace-window boundary tests ----------

    @Test
    fun `isLicensedOrGrace returns true within 47h 59m of last licensed check`() {
        val realState = AyuIslandsState()
        val settingsMock = io.mockk.mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns realState
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        val facade = io.mockk.mockk<LicensingFacade>()
        every { LicensingFacade.getInstance() } returns facade
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns null

        val msPerHour = 3_600_000L
        realState.lastKnownLicensedMs = System.currentTimeMillis() - (47L * msPerHour) - (59L * 60_000L)

        assertTrue(LicenseChecker.isLicensedOrGrace(), "47h 59m must still be inside grace window")
    }

    @Test
    fun `isLicensedOrGrace returns false after 48h of last licensed check`() {
        val realState = AyuIslandsState()
        val settingsMock = io.mockk.mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns realState
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        val facade = io.mockk.mockk<LicensingFacade>()
        every { LicensingFacade.getInstance() } returns facade
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns null

        val msPerHour = 3_600_000L
        realState.lastKnownLicensedMs = System.currentTimeMillis() - (48L * msPerHour) - 1L

        assertFalse(LicenseChecker.isLicensedOrGrace(), "Past 48h must fall outside grace window")
    }

    @Test
    fun `isLicensedOrGrace writes lastKnownLicensedMs monotonically on each licensed call`() {
        val realState = AyuIslandsState()
        val settingsMock = io.mockk.mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns realState
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"
        val facade = io.mockk.mockk<LicensingFacade>()
        every { LicensingFacade.getInstance() } returns facade
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "eval:1"

        realState.lastKnownLicensedMs = 0L
        LicenseChecker.isLicensedOrGrace()
        val firstStamp = realState.lastKnownLicensedMs
        assertTrue(firstStamp > 0, "First licensed call must write a positive stamp")

        // Second call must not regress — should stay the same or grow.
        LicenseChecker.isLicensedOrGrace()
        assertTrue(realState.lastKnownLicensedMs >= firstStamp, "Stamp must be monotonic")
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
