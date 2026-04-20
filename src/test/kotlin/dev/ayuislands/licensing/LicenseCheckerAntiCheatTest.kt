package dev.ayuislands.licensing

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Anti-cheat guards around [LicenseChecker.isLicensedOrGrace] and [LicenseChecker.isDevBuild].
 *
 * Covers the attack vectors documented in the plan:
 *  - Clock rollback / future-timestamp tamper on `lastKnownLicensedMs`.
 *  - Monotonic-clamp behavior on the write path.
 *  - Triple-gate check in [LicenseChecker.isDevBuild] so `-Didea.config.path` alone
 *    cannot unlock dev mode.
 *
 * XML signing is out of scope — see the `KDoc` on [LicenseChecker.isLicensedOrGrace].
 */
class LicenseCheckerAntiCheatTest {
    private lateinit var state: AyuIslandsState
    private lateinit var facade: LicensingFacade

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkStatic(LicensingFacade::class)
        facade = mockk()
        every { LicensingFacade.getInstance() } returns facade

        mockkStatic(PathManager::class)
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"

        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null

        // Use the real clock instead of mocking System.currentTimeMillis — the earlier
        // revision patched it with mockkStatic(System::class), which kept the Gradle
        // test-executor from shutting down in CI. Every assertion below uses offsets
        // larger than the scheduling jitter (minutes / hours), so real-clock drift is
        // harmless.

        System.clearProperty("ayu.islands.dev")
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("ayu.islands.dev")
        unmockkAll()
    }

    private fun setStampUnlicensed() {
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns null
    }

    private fun setStampLicensed() {
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "eval:12345"
    }

    private val hoursMs = 3_600_000L

    // ---------- Clock rollback / future-tamper guard ----------

    @Test
    fun `isLicensedOrGrace returns false when lastKnownLicensedMs is Long MAX_VALUE`() {
        setStampUnlicensed()
        state.lastKnownLicensedMs = Long.MAX_VALUE

        val result = LicenseChecker.isLicensedOrGrace()

        assertFalse(result, "Far-future stamp must not grant grace")
        assertEquals(0L, state.lastKnownLicensedMs, "Tampered stamp must be reset on detection")
    }

    @Test
    fun `isLicensedOrGrace returns false when lastKnownLicensedMs is one hour in the future`() {
        setStampUnlicensed()
        state.lastKnownLicensedMs = System.currentTimeMillis() + hoursMs

        val result = LicenseChecker.isLicensedOrGrace()

        assertFalse(result)
        assertEquals(0L, state.lastKnownLicensedMs)
    }

    @Test
    fun `isLicensedOrGrace returns false when lastKnownLicensedMs is one minute in the future`() {
        // Using a 60 s offset instead of 1 ms avoids flakiness from thread scheduling —
        // the System.currentTimeMillis() call inside isLicensedOrGrace will land at most
        // a few ms after the seed, still comfortably in the future.
        setStampUnlicensed()
        state.lastKnownLicensedMs = System.currentTimeMillis() + 60_000L

        val result = LicenseChecker.isLicensedOrGrace()

        assertFalse(result, "Even a one-minute rollback is rejected — no fudge factor")
        assertEquals(0L, state.lastKnownLicensedMs)
    }

    // ---------- Grace-window boundaries ----------

    @Test
    fun `isLicensedOrGrace grants grace when stamp is 45 hours old`() {
        setStampUnlicensed()
        state.lastKnownLicensedMs = System.currentTimeMillis() - (45L * hoursMs)

        val result = LicenseChecker.isLicensedOrGrace()

        assertTrue(result, "45 h is well inside the 48 h grace window")
    }

    @Test
    fun `isLicensedOrGrace denies grace when stamp is older than 48 hours`() {
        setStampUnlicensed()
        // 48 h + 1 s ago — 1 s buffer absorbs any mid-test drift.
        state.lastKnownLicensedMs = System.currentTimeMillis() - (48L * hoursMs) - 1_000L

        val result = LicenseChecker.isLicensedOrGrace()

        assertFalse(result, "Past the 48 h window, grace must be denied")
    }

    @Test
    fun `isLicensedOrGrace returns false well after 48-hour boundary`() {
        setStampUnlicensed()
        state.lastKnownLicensedMs = System.currentTimeMillis() - (72L * hoursMs)

        val result = LicenseChecker.isLicensedOrGrace()

        assertFalse(result)
    }

    @Test
    fun `isLicensedOrGrace returns false when lastKnownLicensedMs is zero fresh install`() {
        setStampUnlicensed()
        state.lastKnownLicensedMs = 0L

        val result = LicenseChecker.isLicensedOrGrace()

        assertFalse(result, "Never-licensed user must never receive grace")
    }

    // ---------- Monotonic clamp on write ----------

    @Test
    fun `licensed check writes a current stamp when lastKnownLicensedMs is zero`() {
        setStampLicensed()
        state.lastKnownLicensedMs = 0L
        val before = System.currentTimeMillis()

        LicenseChecker.isLicensedOrGrace()

        val after = System.currentTimeMillis()
        assertTrue(
            state.lastKnownLicensedMs in before..after,
            "Stamp ${state.lastKnownLicensedMs} must be inside [$before, $after]",
        )
    }

    @Test
    fun `licensed check keeps a greater lastKnownLicensedMs untouched via monotonic clamp`() {
        // Attacker set the stamp to the future; monotonic clamp must not regress it,
        // but the rollback guard on the next unlicensed call will clear it anyway.
        setStampLicensed()
        val tampered = System.currentTimeMillis() + 10_000L
        state.lastKnownLicensedMs = tampered

        LicenseChecker.isLicensedOrGrace()

        assertEquals(tampered, state.lastKnownLicensedMs, "maxOf never regresses stored stamp")
    }

    @Test
    fun `licensed check upgrades a past lastKnownLicensedMs to now`() {
        setStampLicensed()
        val before = System.currentTimeMillis()
        state.lastKnownLicensedMs = before - 1_000_000L

        LicenseChecker.isLicensedOrGrace()

        assertTrue(
            state.lastKnownLicensedMs >= before,
            "Stamp must move forward to at least $before, got ${state.lastKnownLicensedMs}",
        )
    }

    @Test
    fun `property monotonic clamp never lowers lastKnownLicensedMs on licensed call`(): Unit =
        runBlocking {
            setStampLicensed()
            checkAll(Arb.long(0L..Long.MAX_VALUE / 2)) { seed ->
                val before = System.currentTimeMillis()
                state.lastKnownLicensedMs = seed
                LicenseChecker.isLicensedOrGrace()
                assertTrue(
                    state.lastKnownLicensedMs >= seed,
                    "clamp regressed seed=$seed -> ${state.lastKnownLicensedMs}",
                )
                assertTrue(
                    state.lastKnownLicensedMs >= before,
                    "licensed call must bring stamp to at least $before, got ${state.lastKnownLicensedMs}",
                )
            }
        }

    @Test
    fun `property unlicensed call with future stamp always resets to zero`(): Unit =
        runBlocking {
            setStampUnlicensed()
            checkAll(Arb.long(hoursMs..Long.MAX_VALUE / 2)) { offset ->
                state.lastKnownLicensedMs = System.currentTimeMillis() + offset
                val result = LicenseChecker.isLicensedOrGrace()
                assertFalse(result, "future-stamp offset=$offset must not grant grace")
                assertEquals(0L, state.lastKnownLicensedMs, "stamp must reset after detection")
            }
        }

    // ---------- Dev bypass triple-gate ----------

    private val devSandboxConfigPath = "/home/user/.gradle/idea-sandbox/config"
    private val prodConfigPath = "/home/user/.config/JetBrains/IntelliJIdea2025.1"
    private val devSandboxPluginPath = "/repo/build/idea-sandbox/plugins/ayuIslands/lib/ayuIslands.jar"
    private val prodPluginPath = "/Users/u/Library/Application Support/JetBrains/IC2025.1/plugins/ayuIslands"

    private fun setPluginPath(path: String?) {
        if (path == null) {
            every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
            return
        }
        val descriptor = mockk<IdeaPluginDescriptor>()
        val pluginPath = mockk<Path>()
        every { pluginPath.toString() } returns path
        every { descriptor.pluginPath } returns pluginPath
        every { PluginManagerCore.getPlugin(PluginId.getId("com.ayuislands.theme")) } returns descriptor
    }

    @Test
    fun `isLicensed returns true only when all three dev gates match`() {
        // The four (sysprop, configPath, pluginPath) combinations that include any
        // false must all reject; only the all-true combination passes.
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns devSandboxConfigPath
        setPluginPath(devSandboxPluginPath)
        every { LicensingFacade.getInstance() } returns null

        assertEquals(true, LicenseChecker.isLicensed())
    }

    @Test
    fun `dev bypass rejected when sysprop missing`() {
        System.clearProperty("ayu.islands.dev")
        every { PathManager.getConfigPath() } returns devSandboxConfigPath
        setPluginPath(devSandboxPluginPath)
        every { LicensingFacade.getInstance() } returns null

        // facade null + no dev bypass -> isLicensed returns null (facade not initialized)
        assertEquals(null, LicenseChecker.isLicensed())
    }

    @Test
    fun `dev bypass rejected when configPath is attacker-forged but pluginPath is prod`() {
        // Attacker launches IDE with `-Didea.config.path=/tmp/idea-sandbox/config` AND
        // sets `-Dayu.islands.dev=true`, but the plugin is installed from the real
        // JetBrains plugins directory. Triple-gate must reject.
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns "/tmp/idea-sandbox/config"
        setPluginPath(prodPluginPath)
        every { LicensingFacade.getInstance() } returns null

        assertEquals(null, LicenseChecker.isLicensed())
    }

    @Test
    fun `dev bypass rejected when configPath is prod but pluginPath is dev`() {
        // Symmetric: suppose plugin somehow lives in idea-sandbox but configPath is prod.
        // All three must match; partial match is insufficient.
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns prodConfigPath
        setPluginPath(devSandboxPluginPath)
        every { LicensingFacade.getInstance() } returns null

        assertEquals(null, LicenseChecker.isLicensed())
    }

    @Test
    fun `dev bypass rejected when plugin descriptor is null`() {
        // PluginManagerCore returns null if the plugin id can't be found — unusual but
        // possible during early classloader init. Triple-gate must not crash and must reject.
        System.setProperty("ayu.islands.dev", "true")
        every { PathManager.getConfigPath() } returns devSandboxConfigPath
        setPluginPath(null)
        every { LicensingFacade.getInstance() } returns null

        assertEquals(null, LicenseChecker.isLicensed())
    }

    // ---------- Marketplace-trust contract (pinning) ----------

    @Test
    fun `eval prefix stamp accepted without signature verification`() {
        // Documents the current Marketplace-trust contract: JetBrains LicensingFacade
        // vouches for eval stamps, so LicenseChecker does not re-verify them. If this
        // behavior ever changes, update the test and the KDoc together.
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "eval:tampered-garbage"

        val result = LicenseChecker.isLicensed()

        assertEquals(true, result)
    }

    @Test
    fun `unknown prefix stamp rejected`() {
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "fake:whatever"

        val result = LicenseChecker.isLicensed()

        assertEquals(false, result)
    }

    @Test
    fun `empty stamp rejected`() {
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns ""

        val result = LicenseChecker.isLicensed()

        assertEquals(false, result)
    }

    // ---------- Grace-idempotency across repeated unlicensed calls ----------

    @Test
    fun `repeated unlicensed calls within grace do not mutate lastKnownLicensedMs`() {
        setStampUnlicensed()
        val stamp = System.currentTimeMillis() - hoursMs
        state.lastKnownLicensedMs = stamp

        repeat(1000) {
            LicenseChecker.isLicensedOrGrace()
        }

        assertEquals(stamp, state.lastKnownLicensedMs, "unlicensed grace path must not touch stamp")
    }

    @Test
    fun `repeated future-stamp detections always reset to zero`() {
        setStampUnlicensed()
        repeat(100) {
            state.lastKnownLicensedMs = System.currentTimeMillis() + 60_000L
            LicenseChecker.isLicensedOrGrace()
            assertEquals(0L, state.lastKnownLicensedMs)
        }
    }
}
