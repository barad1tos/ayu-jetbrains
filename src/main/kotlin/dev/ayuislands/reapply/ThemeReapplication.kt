package dev.ayuislands.reapply

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentLifecycle
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.LifecycleTiming
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
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
                        listOf(BindScheme, ApplyResolvedAccent, Font, Notify, Glow, Syntax, VcsApply)
                    }

                    AccentContext.External -> {
                        listOf(RevertAccent, VcsRevert, ApplyResolvedAccent, Glow)
                    }

                    null -> {
                        listOf(RevertAccent, VcsRevert, Glow)
                    }
                }
            }

            is ReapplyReason.LicenseRevert -> {
                listOf(ApplyExplicitHex, Glow, VcsRevert, Syntax)
            }

            is ReapplyReason.RotationTick -> {
                listOf(ApplyResolvedAccent, Glow)
            }
        }

    /**
     * Dispatch the request on EDT. A lifecycle request waits for an active accent
     * apply or restoration to close before its plan runs; rotation retains direct
     * execution on EDT. Each step isolates ordinary failures in [ReapplyResult].
     * [onComplete] receives the actual result on EDT after execution, or
     * [ReapplyStatus.SUPERSEDED] if a queued downgrade is no longer applicable.
     */
    fun reapply(
        reason: ReapplyReason,
        onComplete: (ReapplyResult) -> Unit = {},
    ) {
        val app = ApplicationManager.getApplication()
        val task =
            Runnable {
                if (reason is ReapplyReason.RotationTick) {
                    onComplete(runPlan(reason))
                } else {
                    AccentLifecycle.request { timing -> onComplete(runLifecycle(reason, timing)) }
                }
            }
        if (app.isDispatchThread) {
            task.run()
        } else {
            app.invokeLater(task, ModalityState.nonModal())
        }
    }

    private fun runLifecycle(
        reason: ReapplyReason,
        timing: LifecycleTiming,
    ): ReapplyResult =
        runCatchingPreservingCancellation {
            val current = if (timing == LifecycleTiming.DEFERRED) currentReason(reason) else reason
            if (current == null) ReapplyResult(emptyList(), ReapplyStatus.SUPERSEDED) else runPlan(current)
        }.getOrElse { failure -> ReapplyResult(listOf(StepFailure(ApplyResolvedAccent, failure))) }

    private fun currentReason(reason: ReapplyReason): ReapplyReason? {
        val context = AccentContext.detect()
        if (reason !is ReapplyReason.LicenseRevert) return ReapplyReason.ThemeSwitched(context)
        if (LicenseChecker.currentEntitlement() == LicenseEntitlement.LICENSED) return null
        val variant = (context as? AccentContext.Ayu)?.ayuVariant ?: return ReapplyReason.ThemeSwitched(context)
        return ReapplyReason.LicenseRevert(AyuIslandsSettings.getInstance().getAccentForVariant(variant))
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
            Font, Notify, Glow, Syntax, VcsApply, VcsRevert -> runSurfaceStep(step)
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
                AccentApplicator.applyForFocusedProject(context).requireClean()
            }

            ApplyExplicitHex -> {
                val hex = (reason as? ReapplyReason.LicenseRevert)?.freeHex ?: return
                AccentApplicator.applyFromHexString(hex).requireClean()
            }

            RevertAccent -> {
                AccentApplicator.revertNow()
            }

            // Surface steps are dispatched by runStep; unreachable here.
            Font, Notify, Glow, Syntax, VcsApply, VcsRevert -> {
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

            VcsApply -> {
                VcsColorApplier.applyAll()
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
