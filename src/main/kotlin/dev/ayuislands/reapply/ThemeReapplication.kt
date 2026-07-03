package dev.ayuislands.reapply

import dev.ayuislands.accent.AccentContext
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
import org.jetbrains.annotations.VisibleForTesting

/**
 * Theme reapplication — re-applies every plugin-owned visual surface (accent,
 * editor scheme, font, glow, syntax, VCS colors) in a defined order after a
 * [ReapplyReason]. This object owns the reason-to-step ordering table via the
 * pure [planFor]; each surface manager is invoked in that order by the runner.
 */
object ThemeReapplication {
    /**
     * The single source of the ordering invariant: a pure map from reason to an
     * ordered step sequence. Reads no settings — self-gating happens in the runner,
     * so this stays fixture-free and is the unit-tested surface.
     */
    @VisibleForTesting
    internal fun planFor(reason: ReapplyReason): List<ReapplyStep> =
        when (reason) {
            is ReapplyReason.ThemeSwitched -> {
                when (reason.context) {
                    is AccentContext.Ayu -> {
                        listOf(BindScheme, ApplyResolvedAccent, Font, Notify, Glow, Syntax)
                    }

                    AccentContext.External -> {
                        listOf(RevertAccent, RevertFont, ApplyResolvedAccent, Glow)
                    }

                    null -> {
                        listOf(RevertAccent, RevertFont, Glow)
                    }
                }
            }

            is ReapplyReason.LicenseRevert -> {
                listOf(ApplyExplicitHex, Glow, VcsRevert)
            }

            is ReapplyReason.RotationTick -> {
                listOf(ApplyResolvedAccent, Glow)
            }
        }
}
