package dev.ayuislands.licensing

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Free-tier lockdown: assert that `revertToFreeDefaults` strips every premium
 * capability while preserving the free-tier features — accent color, accent
 * rotation toggles (disabled), panel defaults — and that the operation is
 * idempotent and safe under concurrent invocation.
 *
 * "Free tier" is the pair (6 themes + full accent palette + colour picker). The
 * themes live in `plugin.xml` as `<themeProvider>` entries; a sanity test here
 * pins that the plugin still ships exactly the six expected ids.
 */
class FreeTierLockdownTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private lateinit var rotationService: AccentRotationService

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns "#FFCC66"

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } just runs

        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs

        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        rotationService = mockk(relaxed = true)
        every { app.getService(AccentRotationService::class.java) } returns rotationService

        mockkStatic(NotificationGroupManager::class)
        val ngm = mockk<NotificationGroupManager>()
        val group = mockk<NotificationGroup>()
        val notification = mockk<Notification>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns ngm
        every { ngm.getNotificationGroup(any()) } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        every { notification.notify(any()) } just runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ---------- Individual premium-feature lockdown ----------

    @Test
    fun `revertToFreeDefaults disables glow`() {
        state.glowEnabled = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertFalse(state.glowEnabled)
    }

    @Test
    fun `revertToFreeDefaults disables accent rotation and stops the service`() {
        state.accentRotationEnabled = true

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertFalse(state.accentRotationEnabled)
        verify(exactly = 1) { rotationService.stopRotation() }
    }

    @Test
    fun `revertToFreeDefaults disables both plugin integrations`() {
        state.cgpIntegrationEnabled = true
        state.irIntegrationEnabled = true

        LicenseChecker.revertToFreeDefaults(AyuVariant.DARK)

        assertFalse(state.cgpIntegrationEnabled)
        assertFalse(state.irIntegrationEnabled)
    }

    @Test
    fun `revertToFreeDefaults resets all eight accent element toggles to true`() {
        for (id in AccentElementId.entries) {
            state.setToggle(id, false)
        }

        LicenseChecker.revertToFreeDefaults(AyuVariant.LIGHT)

        for (id in AccentElementId.entries) {
            assertTrue(state.isToggleEnabled(id), "${id.name} must be re-enabled on revert")
        }
        assertEquals(8, AccentElementId.entries.size, "element count locked to 8 — update reverter if this changes")
    }

    @Test
    fun `revertToFreeDefaults resets glowTabMode to MINIMAL`() {
        state.glowTabMode = "FULL"
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertEquals("MINIMAL", state.glowTabMode)
    }

    @Test
    fun `revertToFreeDefaults resets all three panel width modes to DEFAULT`() {
        state.projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.commitPanelWidthMode = PanelWidthMode.FIXED.name
        state.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
        assertEquals(PanelWidthMode.DEFAULT.name, state.commitPanelWidthMode)
        assertEquals(PanelWidthMode.DEFAULT.name, state.gitPanelWidthMode)
    }

    @Test
    fun `revertToFreeDefaults resets project-view hide toggles`() {
        state.hideProjectRootPath = true
        state.hideProjectViewHScrollbar = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertFalse(state.hideProjectRootPath)
        assertFalse(state.hideProjectViewHScrollbar)
    }

    // ---------- Free-tier preservation ----------

    @Test
    fun `revertToFreeDefaults preserves custom accent colors per variant`() {
        state.mirageAccent = "#ABCDEF"
        state.darkAccent = "#123456"
        state.lightAccent = "#FEDCBA"

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals("#ABCDEF", state.mirageAccent, "Mirage accent is a free-tier asset")
        assertEquals("#123456", state.darkAccent)
        assertEquals("#FEDCBA", state.lightAccent)
    }

    @Test
    fun `revertToFreeDefaults preserves followSystemAppearance preference`() {
        state.followSystemAppearance = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertTrue(state.followSystemAppearance, "followSystemAppearance is free and orthogonal to glow")
    }

    @Test
    fun `revertToFreeDefaults preserves everBeenPro so future re-purchase skips redefaulting`() {
        state.everBeenPro = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertTrue(state.everBeenPro, "everBeenPro must survive revert; it gates redefaulting on re-purchase")
    }

    // ---------- Plugin.xml pin: 6 theme providers ----------

    @Test
    fun `plugin registers exactly six theme providers three variants times base-and-islands`() {
        // Parse plugin.xml through a DOM builder instead of regex — a text match on
        // `<themeProvider` would miscount if a theme provider ever got commented out
        // or another element's name starts with the same prefix. DOM ignores comments
        // and returns only live nodes.
        //
        // Hardened against malicious manifests: secure-processing on, DOCTYPE
        // declarations rejected outright, both external-entity flags off. The plugin's
        // own plugin.xml has no DOCTYPE, so flipping disallow-doctype-decl to true
        // costs nothing and prevents DTD-based attacks if the test classpath is ever
        // swapped for a tampered manifest.
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        val doc =
            javaClass
                .getResourceAsStream("/META-INF/plugin.xml")
                ?.use { factory.newDocumentBuilder().parse(it) }
                ?: error("plugin.xml must be on the classpath")

        val providers = doc.getElementsByTagName("themeProvider")
        val providerIds =
            (0 until providers.length).map { (providers.item(it) as Element).getAttribute("id") }
        assertEquals(
            6,
            providers.length,
            "free-tier theme catalog is locked to six providers (ids: $providerIds)",
        )
    }

    // ---------- Idempotency and concurrency ----------

    @Test
    fun `revertToFreeDefaults is idempotent across ten sequential calls`() {
        state.glowEnabled = true
        state.accentRotationEnabled = true
        state.cgpIntegrationEnabled = true

        repeat(10) {
            LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        }

        assertFalse(state.glowEnabled)
        assertFalse(state.accentRotationEnabled)
        assertFalse(state.cgpIntegrationEnabled)
        for (id in AccentElementId.entries) assertTrue(state.isToggleEnabled(id))
        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
    }

    @Test
    fun `revertToFreeDefaults converges to the same state under concurrent callers`() {
        // BaseState is not thread-safe for parallel writes, so the `synchronized(state)`
        // block in revertToFreeDefaults is what makes this pass. Uses plain Threads
        // rather than kotlinx.coroutines because mockkStatic recorders serialize
        // callers on an internal lock — 16+ coroutines compound the contention into
        // an effective hang. Four threads × three replays is enough to expose a race
        // in the state mutation without overwhelming the mock layer.
        repeat(3) {
            state.glowEnabled = true
            state.accentRotationEnabled = true
            state.cgpIntegrationEnabled = true
            state.irIntegrationEnabled = true
            state.glowTabMode = "FULL"
            state.hideProjectRootPath = true
            state.hideProjectViewHScrollbar = true
            for (id in AccentElementId.entries) state.setToggle(id, false)

            val threads =
                (1..4).map {
                    Thread { LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE) }
                }
            threads.forEach { it.start() }
            threads.forEach { it.join(5_000L) }
            threads.forEach { assertFalse(it.isAlive, "thread still alive — suspected deadlock") }

            assertFalse(state.glowEnabled)
            assertFalse(state.accentRotationEnabled)
            assertFalse(state.cgpIntegrationEnabled)
            assertFalse(state.irIntegrationEnabled)
            assertEquals("MINIMAL", state.glowTabMode)
            assertFalse(state.hideProjectRootPath)
            assertFalse(state.hideProjectViewHScrollbar)
            for (id in AccentElementId.entries) {
                assertTrue(state.isToggleEnabled(id), "${id.name} must be true after concurrent revert")
            }
        }
    }
}
