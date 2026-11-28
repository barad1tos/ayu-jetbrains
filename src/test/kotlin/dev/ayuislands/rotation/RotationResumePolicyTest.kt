package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RotationResumePolicyTest {
    private val eligible = RotationAvailability(LicenseEntitlement.LICENSED, false, AyuVariant.MIRAGE, true, false)
    private val on = RotationIntent(true, "PRESET", 6)
    private val off = on.copy(enabled = false)
    private val follow = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM)
    private val interrupted = RotationResume.FullInterval(FullIntervalReason.INTERRUPTED_HANDOFF)
    private val activate = RotationResumeChange.Accepted(RotationAcceptanceOrigin.PROFILE_ACTIVATION)
    private val enable = RotationResumeChange.Accepted(RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE)
    private val configure = RotationResumeChange.Accepted(RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT)

    @Test
    fun `unknown entitlement owns no loss or return and all observed reasons are independent`() {
        val reasons = RotationPauseReason.entries
        for (mask in 0 until (1 shl reasons.size)) {
            val expected = reasons.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val observed =
                eligible.copy(
                    entitlement =
                        if (RotationPauseReason.NO_PRO in
                            expected
                        ) {
                            LicenseEntitlement.UNKNOWN
                        } else {
                            eligible.entitlement
                        },
                    effectiveFollowSystem = RotationPauseReason.FOLLOW_SYSTEM in expected,
                    variant = eligible.variant.takeUnless { RotationPauseReason.EXTERNAL_THEME in expected },
                    targetValid = RotationPauseReason.TARGET_UNAVAILABLE !in expected,
                    recoveryPending = RotationPauseReason.RECOVERY_PENDING in expected,
                )
            assertEquals(expected.toSet(), observed.pauseReasons.toSet())
        }
        val unknown = eligible.copy(entitlement = LicenseEntitlement.UNKNOWN)
        assertEquals(eligible, unknown.retainDefinitiveEntitlement(eligible))
        val unlicensed = eligible.copy(entitlement = LicenseEntitlement.UNLICENSED)
        assertEquals(unlicensed, unknown.retainDefinitiveEntitlement(unlicensed))
    }

    @Test
    fun `eligible explicit enable replaces the old obligation and explicit disable leaves the history alone`() {
        assertEquals(
            RotationResume.ImmediateEnable,
            decide(follow, input(off), input(on), enable).resume,
        )
        assertEquals(
            RotationResumePlan.Off(eligible),
            planRotationResume(RotationResume.KeepDeadline, input(on), input(off), configure),
        )
    }

    @Test
    fun `enable while Pro is unavailable waits for that gate and then steps once`() {
        val unavailable = eligible.copy(entitlement = LicenseEntitlement.UNLICENSED)
        val paused = decide(interrupted, input(off), input(on, unavailable), enable)
        assertEquals(listOf(RotationPauseReason.NO_PRO), paused.pauseReasons)
        assertEquals(RotationResume.ImmediateEnable, paused.resume)
        val released = decide(paused.resume, input(on, unavailable), input(on))
        assertEquals(emptyList(), released.pauseReasons)
        assertEquals(RotationResume.ImmediateEnable, released.resume)
    }

    @Test
    fun `only local changes to rotation configuration schedule an immediate step`() {
        val random = on.copy(modeRaw = "RANDOM", intervalHoursRaw = 1)
        assertEquals(
            RotationResume.ImmediateConfiguration,
            decide(RotationResume.KeepDeadline, input(on), input(random), configure).resume,
        )
        for (change in listOf(
            configure,
            RotationResumeChange.Accepted(RotationAcceptanceOrigin.LOCAL_APPEARANCE_EDIT),
        )) {
            assertEquals(
                RotationResume.KeepDeadline,
                decide(RotationResume.KeepDeadline, input(random), input(random), change).resume,
            )
        }
        val withoutPro = eligible.copy(entitlement = LicenseEntitlement.UNLICENSED)
        assertEquals(
            RotationResume.ImmediateConfiguration,
            decide(RotationResume.KeepDeadline, input(on, withoutPro), input(random, withoutPro), configure).resume,
        )
    }

    @Test
    fun `Follow sets a full interval and clearing either of two gates retains it until both clear`() {
        val noPro = eligible.copy(entitlement = LicenseEntitlement.UNLICENSED)
        val both = noPro.copy(effectiveFollowSystem = true)
        val paused = decide(RotationResume.ImmediateConfiguration, input(on, noPro), input(on, both))
        assertEquals(follow, paused.resume)
        for (stillPaused in listOf(noPro, eligible.copy(effectiveFollowSystem = true))) {
            val remaining = decide(paused.resume, input(on, both), input(on, stillPaused))
            assertEquals(follow, remaining.resume)
            assertEquals(1, remaining.pauseReasons.size)
            val released = decide(remaining.resume, input(on, stillPaused), input(on))
            assertEquals(follow, released.resume)
            assertEquals(emptyList(), released.pauseReasons)
        }
    }

    @Test
    fun `continuous eligible handoff keeps the appointment while interrupted handoff waits a full interval`() {
        val shorter = on.copy(intervalHoursRaw = 1)
        assertEquals(
            RotationResume.KeepDeadline,
            decide(RotationResume.KeepDeadline, input(on), input(shorter), activate).resume,
        )
        assertEquals(
            interrupted,
            decide(RotationResume.KeepDeadline, input(off), input(shorter), activate).resume,
        )
        val withoutPro = eligible.copy(entitlement = LicenseEntitlement.UNLICENSED)
        val deferred =
            decide(RotationResume.ImmediateConfiguration, input(on, withoutPro), input(shorter, withoutPro), activate)
        assertEquals(interrupted, deferred.resume)
        assertEquals(interrupted, decide(deferred.resume, input(shorter, withoutPro), input(shorter)).resume)
    }

    @Test
    fun `closing the last project reuses the current timing without a new handoff obligation`() {
        val transfer = RotationResumeChange.Handoff(RotationHandoffKind.CONTINUITY)
        assertEquals(
            RotationResumePlan.Preserve,
            planRotationResume(follow, input(on, eligible.copy(effectiveFollowSystem = true)), input(on), transfer),
        )
    }

    @Test
    fun `catalog saves leave timing alone and migration keeps its no history obligation`() {
        val catalog = RotationResumeChange.Accepted(RotationAcceptanceOrigin.CATALOG_ONLY)
        val migration = RotationResumeChange.Accepted(RotationAcceptanceOrigin.MIGRATION)
        assertEquals(
            RotationResumePlan.Preserve,
            planRotationResume(RotationResume.KeepDeadline, input(on), input(on), catalog),
        )
        assertEquals(
            RotationResume.NoHistory,
            decide(RotationResume.NoHistory, input(off), input(on), migration).resume,
        )
    }

    @Test
    fun `external cadence survives lack of Pro and Follow supersedes it`() {
        val external = eligible.copy(variant = null)
        val externalWithoutPro = external.copy(entitlement = LicenseEntitlement.UNLICENSED)
        val withoutPro = eligible.copy(entitlement = LicenseEntitlement.UNLICENSED)
        val first = decide(RotationResume.KeepDeadline, input(on), input(on, external))
        assertEquals(RotationResume.ExternalCadence, first.resume)
        val waiting = decide(first.resume, input(on, externalWithoutPro), input(on, withoutPro))
        assertEquals(RotationResume.ExternalCadence, waiting.resume)
        assertEquals(RotationResume.ExternalCadence, decide(waiting.resume, input(on, withoutPro), input(on)).resume)
        assertEquals(
            follow,
            decide(waiting.resume, input(on, external), input(on, external.copy(effectiveFollowSystem = true))).resume,
        )
    }

    @Test
    fun `an accepted local change while on another theme asks for a fresh cadence from acceptance`() {
        val external = eligible.copy(variant = null)
        val oneHour = on.copy(intervalHoursRaw = 1)
        val edited = decide(RotationResume.ExternalCadence, input(on, external), input(oneHour, external), configure)
        assertEquals(RotationResume.ExternalCadence, edited.resume)
        assertEquals(ExternalCadencePlan.START_AT_ACCEPTANCE, edited.externalCadence)
        val unchanged = decide(edited.resume, input(oneHour, external), input(oneHour, external), configure)
        assertEquals(ExternalCadencePlan.KEEP, unchanged.externalCadence)
    }

    private fun input(
        intent: RotationIntent,
        availability: RotationAvailability = eligible,
    ): RotationResumeInput = RotationResumeInput(intent, availability)

    private fun decide(
        resume: RotationResume,
        previous: RotationResumeInput,
        current: RotationResumeInput,
        change: RotationResumeChange = RotationResumeChange.Observation,
    ): RotationResumePlan.Reconcile = assertIs(planRotationResume(resume, previous, current, change))
}
