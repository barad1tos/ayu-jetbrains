package dev.ayuislands.reapply

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
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
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.ui.ComponentTreeRefresher
import dev.ayuislands.vcs.VcsColorApplier
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

    /**
     * Run [reason]'s plan on a single EDT turn: directly when already on the EDT,
     * otherwise `invokeLater(nonModal)`. Each step is isolated — a throwing step is
     * recorded in [ReapplyResult] and the sequence continues. The result is delivered
     * to [onComplete] on the EDT (see the signature note in the plan).
     */
    fun reapply(
        reason: ReapplyReason,
        onComplete: (ReapplyResult) -> Unit = {},
    ) {
        val app = ApplicationManager.getApplication()
        val task = Runnable { onComplete(runPlan(reason)) }
        if (app.isDispatchThread) {
            task.run()
        } else {
            app.invokeLater(task, ModalityState.nonModal())
        }
    }

    private fun runPlan(reason: ReapplyReason): ReapplyResult {
        val failures =
            planFor(reason).mapNotNull { step ->
                runCatchingPreservingCancellation { runStep(step, reason) }
                    .exceptionOrNull()
                    ?.let { StepFailure(step, it) }
            }
        return ReapplyResult(failures)
    }

    private fun runStep(
        step: ReapplyStep,
        reason: ReapplyReason,
    ) {
        when (step) {
            BindScheme, ApplyResolvedAccent, ApplyExplicitHex, RevertAccent -> runAccentStep(step, reason)
            Font, RevertFont, Notify, Glow, Syntax, VcsRevert -> runSurfaceStep(step)
        }
    }

    /** Runs the accent-facing steps: scheme binding and accent apply/revert. */
    private fun runAccentStep(
        step: ReapplyStep,
        reason: ReapplyReason,
    ) {
        when (step) {
            BindScheme -> {
                if (!AyuIslandsSettings.getInstance().state.syncEditorScheme) return
                val variant = ayuVariantOf(reason) ?: return
                AyuEditorSchemeBinder.bindForVariant(variant)
            }

            ApplyResolvedAccent -> {
                val context = accentContextOf(reason) ?: return
                val hex = AccentApplicator.applyForFocusedProject(context)
                // A shape-invalid hex is the ONE case where the applicator skips
                // apply() entirely (leaving lastApplyOk stale from the previous
                // apply), so the clean-flag check below cannot see it. The hex
                // itself carries the signal: mirror ApplyExplicitHex's rejection
                // failure by validating the returned value.
                check(AccentHex.of(hex) != null) {
                    "resolver produced a shape-invalid hex '$hex'; apply was skipped"
                }
                ensureAccentApplyClean(step)
            }

            ApplyExplicitHex -> {
                val hex = (reason as? ReapplyReason.LicenseRevert)?.freeHex ?: return
                check(AccentApplicator.applyFromHexString(hex)) {
                    "free-default hex rejected by the applicator: '$hex'"
                }
                ensureAccentApplyClean(step)
            }

            RevertAccent -> {
                AccentApplicator.revertAll()
            }

            // Surface steps are dispatched by runStep; unreachable here.
            Font, RevertFont, Notify, Glow, Syntax, VcsRevert -> {
                return
            }
        }
    }

    /** Runs the non-accent surfaces: font, notify, glow, syntax, VCS colors. */
    private fun runSurfaceStep(step: ReapplyStep) {
        when (step) {
            Font -> {
                FontPresetApplicator.applyFromState()
            }

            RevertFont -> {
                FontPresetApplicator.revert()
            }

            Notify -> {
                notifyOpenProjects()
            }

            Glow -> {
                GlowOverlayManager.syncGlowForAllProjects()
            }

            Syntax -> {
                if (AyuVariant.isAyuActive()) {
                    SyntaxIntensityService.getInstance().reapplyForActiveLaf()
                }
            }

            VcsRevert -> {
                VcsColorApplier.revertAll()
            }

            // Accent steps are dispatched by runStep; unreachable here.
            BindScheme, ApplyResolvedAccent, ApplyExplicitHex, RevertAccent -> {
                return
            }
        }
    }

    /**
     * Escalate a torn accent apply into this step's [StepFailure]. The applicator
     * contains mid-step throws internally (WARN + skipped tail — see
     * `AccentApplyPlanRunner`), so the only in-process tear signal available to
     * plan consumers is the persisted clean flag: [reapply] runs on the EDT and
     * the apply is EDT-synchronous, so after the apply call returns the flag
     * reflects THAT apply. Throwing here restores what consumers relied on
     * before the containment refactor: the rotation circuit breaker counts the
     * failure and the license revert surfaces its "restart to complete" notice.
     *
     * Synchronicity coupling: this contract holds only while [reapply]'s EDT
     * check (`Application.isDispatchThread`) and the runner's
     * (`SwingUtilities.isEventDispatchThread`) agree — they delegate to the same
     * EDT in production. A runner dispatch refactor must preserve that.
     *
     * Does NOT cover the rejected-hex skip (apply never ran, flag stale) — the
     * callers validate the hex shape separately before consulting this.
     */
    private fun ensureAccentApplyClean(step: ReapplyStep) {
        check(AyuIslandsSettings.getInstance().state.lastApplyOk) {
            "accent apply torn during $step — see the 'Accent apply torn at' warning above"
        }
    }

    /** The [AccentContext] the accent steps resolve against, or null when the reason carries none. */
    private fun accentContextOf(reason: ReapplyReason): AccentContext? =
        when (reason) {
            is ReapplyReason.ThemeSwitched -> reason.context
            is ReapplyReason.RotationTick -> AccentContext.Ayu(reason.variant)
            is ReapplyReason.LicenseRevert -> null
        }

    private fun ayuVariantOf(reason: ReapplyReason): AyuVariant? =
        (accentContextOf(reason) as? AccentContext.Ayu)?.ayuVariant

    /**
     * Publish the component-tree refresh per open project so subscribed managers reapply.
     * No tree walk — the platform's own LAF refresh already walked it (mirrors the old
     * `AyuIslandsLafListener.notifyOpenProjects`).
     */
    private fun notifyOpenProjects() {
        for (openProject in ProjectManager.getInstance().openProjects) {
            if (openProject.isDefault || openProject.isDisposed) continue
            ComponentTreeRefresher.notifyOnly(openProject)
        }
    }
}
