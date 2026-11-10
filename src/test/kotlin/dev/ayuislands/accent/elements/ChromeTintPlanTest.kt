package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.TintIntensity
import dev.ayuislands.accent.WcagForeground
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChromeTintPlanTest {
    @Test
    fun `candidate tint follows canonical surface order rather than stock observation order`() {
        val stocks =
            linkedMapOf(
                "NavBar.borderColor" to Color.GRAY,
                "Unrelated.background" to Color.YELLOW,
                "NavBar.background" to Color.BLACK,
            )
        val element = NavBarElement()
        val plan = element.planTint(Color.CYAN, TintIntensity.of(0), stocks, canHandleEmpty = true)
        assertEquals(listOf("NavBar.background", "NavBar.borderColor"), element.stockKeys)
        assertEquals(element.stockKeys, plan.backgrounds.keys.toList())
        assertEquals(Color.BLACK, plan.backgrounds["NavBar.background"])
        assertEquals(Color.GRAY, plan.backgrounds["NavBar.borderColor"])
        assertTrue(plan.overlays.isEmpty())
        assertEquals(listOf("NavBar.foreground", "NavBar.selectedItemForeground"), plan.foregrounds.keys.toList())
        val peer = assertNotNull(plan.peer)
        assertEquals(Color.BLACK, peer.color)
        assertTrue(peer.target is ChromeTarget.ByClassName)
        assertEquals(3, stocks.size)
    }

    @Test
    fun `empty Ayu status bar still clears overlays while empty external status bar stays untouched`() {
        val element = StatusBarElement()
        val ayu = element.planTint(Color.CYAN, TintIntensity.DEFAULT, emptyMap(), canHandleEmpty = true)
        assertTrue(ayu.backgrounds.isEmpty())
        assertTrue(ayu.foregrounds.isEmpty())
        assertNull(ayu.peer)
        assertEquals(6, ayu.overlays.size)
        assertTrue("StatusBar.Breadcrumbs.floatingBackground" in ayu.overlays)
        val external = element.planTint(Color.CYAN, TintIntensity.DEFAULT, emptyMap(), canHandleEmpty = false)
        assertTrue(external.backgrounds.isEmpty())
        assertTrue(external.overlays.isEmpty())
        assertTrue(external.foregrounds.isEmpty())
        assertNull(external.peer)
    }

    @Test
    fun `status bar foreground samples only the opaque root and never its border`() {
        val element = StatusBarElement()
        val missingRoot =
            element.planTint(
                Color.CYAN,
                TintIntensity.of(0),
                mapOf("StatusBar.borderColor" to Color.WHITE),
                canHandleEmpty = true,
            )
        assertTrue(missingRoot.foregrounds.isEmpty())
        val full =
            element.planTint(
                Color.CYAN,
                TintIntensity.of(0),
                mapOf("StatusBar.background" to Color.BLACK, "StatusBar.borderColor" to Color.WHITE),
                canHandleEmpty = true,
            )
        assertEquals(8, full.foregrounds.size)
        assertTrue(full.foregrounds.values.all { it.rgb == Color.WHITE.rgb })
        assertTrue(assertNotNull(full.peer).target is ChromeTarget.StatusBar)
    }

    @Test
    fun `stripe foregrounds retain independent contrast for selected and unselected surfaces`() {
        val plan =
            ToolWindowStripeElement().planTint(
                Color.CYAN,
                TintIntensity.of(0),
                mapOf(
                    "ToolWindow.Stripe.background" to Color.BLACK,
                    "ToolWindow.Button.selectedBackground" to Color.WHITE,
                ),
                canHandleEmpty = true,
            )
        assertEquals(
            listOf("ToolWindow.Button.selectedForeground", "ToolWindow.Stripe.foreground"),
            plan.foregrounds.keys.toList(),
        )
        assertEquals(Color(0x1F2430).rgb, plan.foregrounds["ToolWindow.Button.selectedForeground"]?.rgb)
        assertEquals(Color.WHITE.rgb, plan.foregrounds["ToolWindow.Stripe.foreground"]?.rgb)
    }

    @Test
    fun `pure planning does not run capability probes legacy setters or diagnostic hooks`() {
        val element =
            object : AbstractChromeElement() {
                override val id = AccentElementId.NAV_BAR
                override val displayName = "Test chrome"
                override val backgroundKeys = listOf("Test.background")
                override val foregroundKeys = listOf("Test.foreground")
                override val foregroundTextTarget = WcagForeground.TextTarget.PRIMARY_TEXT
                override val peerTarget: ChromeTarget? = null
                override val isEnabled: Boolean
                    get() = error("Native capability probe must stay outside pure planning")

                override fun onBackgroundsTinted(tintedBackgrounds: Map<String, Color>) {
                    error("Legacy hook must not run during preparation")
                }
            }
        val plan =
            element.planTint(
                Color.CYAN,
                TintIntensity.of(0),
                mapOf("Test.background" to Color.BLACK),
                canHandleEmpty = true,
            )
        assertEquals(Color.BLACK, plan.backgrounds["Test.background"])
        assertEquals(Color.WHITE.rgb, plan.foregrounds["Test.foreground"]?.rgb)
        assertNull(plan.peer)
    }
}
