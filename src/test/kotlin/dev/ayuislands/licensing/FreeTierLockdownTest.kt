package dev.ayuislands.licensing

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeTierLockdownTest {
    private lateinit var state: AyuIslandsState

    @BeforeTest fun setUp() {
        state = AyuIslandsState()
    }

    // ---------- Individual premium-feature lockdown ----------

    @Test
    fun `accepted license loss preserves glow preference`() {
        state.glowEnabled = true
        val accepted = confirmUnlicensed(state).settings
        assertTrue(accepted.glowEnabled)
    }

    @Test
    fun `accepted license loss preserves waveform preferences`() {
        state.glowEnabled = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.COUNTER_CLOCKWISE.name
        state.waveformBaseline = WaveformBaseline.CENTERED.name
        state.waveformTraceDensity = 4
        state.waveformTraceLength = 640
        state.waveformAmplitude = 16
        state.waveformIntensity = 88

        val accepted = confirmUnlicensed(state).settings

        assertTrue(accepted.glowEnabled)
        assertEquals(GlowShape.WAVEFORM.name, accepted.glowShape)
        assertEquals(WaveformMovement.COUNTER_CLOCKWISE.name, accepted.waveformDirection)
        assertEquals(WaveformBaseline.CENTERED.name, accepted.waveformBaseline)
        assertEquals(4, accepted.waveformTraceDensity)
        assertEquals(640, accepted.waveformTraceLength)
        assertEquals(16, accepted.waveformAmplitude)
        assertEquals(88, accepted.waveformIntensity)
    }

    @Test
    fun `accepted license loss preserves accent rotation`() {
        state.accentRotationEnabled = true

        val accepted = confirmUnlicensed(state).settings

        assertTrue(accepted.accentRotationEnabled)
    }

    @Test
    fun `accepted license loss preserves both plugin integrations`() {
        state.cgpIntegrationEnabled = true
        state.irIntegrationEnabled = true

        val accepted = confirmUnlicensed(state).settings

        assertTrue(accepted.cgpIntegrationEnabled)
        assertTrue(accepted.irIntegrationEnabled)
    }

    @Test
    fun `accepted license loss preserves every element choice`() {
        // Free choices remain immediately effective. Premium CHROME choices stay
        // persisted while runtime entitlement suppresses their rendering.
        for (id in AccentElementId.entries) {
            state.setToggle(id, id.group == AccentGroup.CHROME)
        }

        val accepted = confirmUnlicensed(state).settings

        for (id in AccentElementId.entries.filter { elementId -> elementId.group != AccentGroup.CHROME }) {
            assertFalse(accepted.isToggleEnabled(id), "${id.name} must be preserved on free-tier revert")
        }
        for (id in AccentElementId.entries.filter { elementId -> elementId.group == AccentGroup.CHROME }) {
            assertTrue(accepted.isToggleEnabled(id), "${id.name} must be preserved on free-tier revert")
        }
        assertEquals(13, AccentElementId.entries.size, "element inventory changed — update the preservation test")
    }

    @Test
    fun `accepted license loss preserves chrome intensity and UI state`() {
        state.chromeTintIntensity = 75
        state.chromeTintingGroupExpanded = true

        val accepted = confirmUnlicensed(state).settings

        assertEquals(75, accepted.chromeTintIntensity)
        assertTrue(accepted.chromeTintingGroupExpanded)
    }

    @Test
    fun `accepted license loss preserves the project icon accent preference`() {
        state.projectIconAccentEnabled = true

        val accepted = confirmUnlicensed(state).settings

        assertTrue(accepted.projectIconAccentEnabled)
    }

    @Test
    fun `accepted license loss preserves the external chrome tint preference`() {
        // Runtime entitlement gates paid behavior. Keep the stored choice so a
        // renewed license restores the exact external-theme preference.
        state.externalThemeChromeTintEnabled = true

        val accepted = confirmUnlicensed(state).settings

        assertTrue(accepted.externalThemeChromeTintEnabled)
    }

    @Test
    fun `accepted license loss preserves glowTabMode preference`() {
        state.glowTabMode = "FULL"
        val accepted = confirmUnlicensed(state).settings
        assertEquals("FULL", accepted.glowTabMode)
    }

    @Test
    fun `accepted license loss preserves all three panel width modes`() {
        state.projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.commitPanelWidthMode = PanelWidthMode.FIXED.name
        state.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name

        val accepted = confirmUnlicensed(state).settings

        assertEquals(PanelWidthMode.AUTO_FIT.name, accepted.projectPanelWidthMode)
        assertEquals(PanelWidthMode.FIXED.name, accepted.commitPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, accepted.gitPanelWidthMode)
    }

    @Test
    fun `accepted license loss preserves project-view hide toggles`() {
        state.hideProjectRootPath = true
        state.hideProjectViewHScrollbar = true
        val accepted = confirmUnlicensed(state).settings
        assertTrue(accepted.hideProjectRootPath)
        assertTrue(accepted.hideProjectViewHScrollbar)
    }

    @Test
    fun `accepted license loss preserves commit path display controls`() {
        state.commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
        state.commitPanelPathMinHiddenLevels = 2
        state.commitPanelPathMaxHiddenLevels = 4

        val accepted = confirmUnlicensed(state).settings

        assertEquals(CommitPathDisplayMode.TOOLTIP.name, accepted.commitPanelPathDisplayMode)
        assertEquals(2, accepted.commitPanelPathMinHiddenLevels)
        assertEquals(4, accepted.commitPanelPathMaxHiddenLevels)
    }

    // ---------- Free-tier preservation ----------

    @Test
    fun `accepted license loss preserves custom accent colors per variant`() {
        state.mirageAccent = "#ABCDEF"
        state.darkAccent = "#123456"
        state.lightAccent = "#FEDCBA"

        val accepted = confirmUnlicensed(state).settings

        assertEquals("#ABCDEF", accepted.mirageAccent, "Mirage accent is a free-tier asset")
        assertEquals("#123456", accepted.darkAccent)
        assertEquals("#FEDCBA", accepted.lightAccent)
    }

    @Test
    fun `accepted license loss preserves followSystemAppearance preference`() {
        state.followSystemAppearance = true
        val accepted = confirmUnlicensed(state).settings
        assertTrue(accepted.followSystemAppearance, "followSystemAppearance is free and orthogonal to glow")
    }

    @Test
    fun `accepted license loss preserves everBeenPro so future re-purchase skips default restore`() {
        state.everBeenPro = true
        val accepted = confirmUnlicensed(state).settings
        assertTrue(accepted.everBeenPro, "everBeenPro must survive revert; it gates default restore on re-purchase")
    }

    // ---------- Plugin.xml pin: 6 theme providers ----------

    @Test
    @Suppress("HttpUrlsUsage")
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
}
