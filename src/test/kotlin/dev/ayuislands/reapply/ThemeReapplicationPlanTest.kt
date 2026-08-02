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
import dev.ayuislands.reapply.ReapplyStep.Syntax
import dev.ayuislands.reapply.ReapplyStep.VcsApply
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
        assertEquals(listOf(BindScheme, ApplyResolvedAccent, Font, Notify, Glow, Syntax, VcsApply), plan)
    }

    @Test
    fun `theme switch to External cleans accent then applies external accent and glow`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.ThemeSwitched(AccentContext.External))
        assertEquals(listOf(RevertAccent, VcsRevert, ApplyResolvedAccent, Glow), plan)
    }

    @Test
    fun `theme switch away cleans accent then syncs glow`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.ThemeSwitched(null))
        assertEquals(listOf(RevertAccent, VcsRevert, Glow), plan)
    }

    @Test
    fun `license revert applies explicit hex, syncs glow, reverts vcs and syntax`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.LicenseRevert("#E6B450"))
        assertEquals(listOf(ApplyExplicitHex, Glow, VcsRevert, Syntax), plan)
    }

    @Test
    fun `rotation tick applies resolved accent then syncs glow`() {
        val plan = ThemeReapplication.planFor(ReapplyReason.RotationTick(AyuVariant.DARK))
        assertEquals(listOf(ApplyResolvedAccent, Glow), plan)
    }
}
