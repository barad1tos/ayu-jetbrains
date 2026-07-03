package dev.ayuislands.reapply

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.reapply.ReapplyStep.ApplyExplicitHex
import dev.ayuislands.reapply.ReapplyStep.ApplyResolvedAccent
import dev.ayuislands.reapply.ReapplyStep.BindScheme
import dev.ayuislands.reapply.ReapplyStep.Font
import dev.ayuislands.reapply.ReapplyStep.Glow
import dev.ayuislands.reapply.ReapplyStep.Notify
import dev.ayuislands.reapply.ReapplyStep.RevertAccent
import dev.ayuislands.reapply.ReapplyStep.RevertFont
import dev.ayuislands.reapply.ReapplyStep.Syntax
import dev.ayuislands.reapply.ReapplyStep.VcsRevert
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeReapplicationPlanTest {
    @Test
    fun `theme switch to Ayu binds scheme first then accent, font, notify, glow, syntax`() {
        val plan =
            ThemeReapplication.planFor(
                ReapplyReason.ThemeSwitched(AccentContext.Ayu(AyuVariant.DARK)),
            )
        assertEquals(listOf(BindScheme, ApplyResolvedAccent, Font, Notify, Glow, Syntax), plan)
    }

    @Test
    fun `theme switch to External reverts accent and font, then applies external accent and glow`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.ThemeSwitched(AccentContext.External))
        assertEquals(listOf(RevertAccent, RevertFont, ApplyResolvedAccent, Glow), plan)
    }

    @Test
    fun `theme switch away reverts accent and font, then syncs glow`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.ThemeSwitched(null))
        assertEquals(listOf(RevertAccent, RevertFont, Glow), plan)
    }

    @Test
    fun `license revert applies explicit hex, syncs glow, reverts vcs`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.LicenseRevert("#E6B450"))
        assertEquals(listOf(ApplyExplicitHex, Glow, VcsRevert), plan)
    }

    @Test
    fun `rotation tick applies resolved accent then syncs glow`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.RotationTick(AyuVariant.DARK))
        assertEquals(listOf(ApplyResolvedAccent, Glow), plan)
    }
}
