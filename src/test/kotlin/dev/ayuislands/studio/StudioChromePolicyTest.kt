package dev.ayuislands.studio

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeTintSnapshot
import dev.ayuislands.accent.TintIntensity
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.session.StudioChromeAction
import dev.ayuislands.studio.session.StudioChromeInput
import dev.ayuislands.studio.session.StudioChromePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioChromePolicyTest {
    @Test
    fun `Ayu gates clear effects without rewriting granular choices`() {
        val enabled = input()
        assertEquals(StudioChromeAction.TINT, action(enabled))
        assertEquals(StudioChromeAction.CLEAR, action(enabled.copy(entitlement = LicenseEntitlement.UNLICENSED)))
        assertEquals(StudioChromeAction.CLEAR, action(enabled.copy(settings = settings(master = false))))
        assertEquals(StudioChromeAction.CLEAR, action(enabled, conflict = true))
        val forced = enabled.copy(forceOverrides = listOf("future-id", AccentElementId.NAV_BAR.name))
        assertEquals(StudioChromeAction.TINT, action(forced, conflict = true))
        assertEquals(
            StudioChromeAction.CLEAR,
            action(forced.copy(entitlement = LicenseEntitlement.UNLICENSED), conflict = true),
        )
        assertEquals(settings(), enabled.settings)
        assertEquals(listOf("future-id", AccentElementId.NAV_BAR.name), forced.forceOverrides)
    }

    @Test
    fun `external closed gates clear only surfaces owned by Ayu`() {
        val enabled = input().copy(context = AccentContext.External)
        assertEquals(StudioChromeAction.TINT, action(enabled))
        val closed =
            listOf(
                enabled.copy(externalAllowed = false),
                enabled.copy(entitlement = LicenseEntitlement.UNLICENSED),
                enabled.copy(settings = settings(master = false)),
            )
        for (candidate in closed) {
            assertEquals(StudioChromeAction.SKIP, action(candidate))
            assertEquals(StudioChromeAction.CLEAR, action(candidate, owned = true))
        }
        assertEquals(StudioChromeAction.SKIP, action(enabled, conflict = true))
        assertEquals(StudioChromeAction.CLEAR, action(enabled, conflict = true, owned = true))
    }

    @Test
    fun `inactive context reverts every Chrome target without changing saved gates`() {
        for (entitlement in listOf(LicenseEntitlement.LICENSED, LicenseEntitlement.UNLICENSED)) {
            val candidate = input().copy(context = null, entitlement = entitlement, settings = settings(master = false))
            for (id in AccentElementId.entries.filter { it.group == AccentGroup.CHROME }) {
                for (owned in listOf(false, true)) {
                    assertEquals(
                        StudioChromeAction.CLEAR,
                        StudioChromePolicy.action(candidate, id, hasConflict = true, isOwned = owned),
                    )
                }
            }
            assertEquals(settings(master = false), candidate.settings)
        }
    }

    @Test
    fun `each chrome switch retains its independent gate`() {
        val candidate = input().copy(settings = settings().copy(chromeMainToolbar = false, chromeNavBar = false))
        val enabled =
            setOf(AccentElementId.STATUS_BAR, AccentElementId.TOOL_WINDOW_STRIPE, AccentElementId.PANEL_BORDER)
        for (id in listOf(
            AccentElementId.STATUS_BAR,
            AccentElementId.MAIN_TOOLBAR,
            AccentElementId.TOOL_WINDOW_STRIPE,
            AccentElementId.NAV_BAR,
            AccentElementId.PANEL_BORDER,
        )) {
            val expected = if (id in enabled) StudioChromeAction.TINT else StudioChromeAction.CLEAR
            assertEquals(expected, StudioChromePolicy.action(candidate, id, hasConflict = false, isOwned = false))
        }
    }

    @Test
    fun `unknown entitlement and non chrome IDs cannot silently choose a native action`() {
        assertFailsWith<IllegalArgumentException> {
            action(input().copy(entitlement = LicenseEntitlement.UNKNOWN))
        }
        assertFailsWith<IllegalArgumentException> {
            StudioChromePolicy.action(input(), AccentElementId.LINKS, hasConflict = false, isOwned = false)
        }
    }

    private fun action(
        candidate: StudioChromeInput,
        conflict: Boolean = false,
        owned: Boolean = false,
    ) = StudioChromePolicy.action(candidate, AccentElementId.NAV_BAR, conflict, owned)

    private fun input(): StudioChromeInput =
        StudioChromeInput(
            AccentContext.Ayu(AyuVariant.MIRAGE),
            settings(),
            externalAllowed = true,
            forceOverrides = emptyList(),
            entitlement = LicenseEntitlement.LICENSED,
        )

    private fun settings(master: Boolean = true): ChromeTintSnapshot =
        ChromeTintSnapshot(
            chromeStatusBar = true,
            chromeMainToolbar = true,
            chromeToolWindowStripe = true,
            chromeNavBar = true,
            chromePanelBorder = true,
            intensity = TintIntensity.of(20),
            chromeTintingEnabled = master,
        )
}
