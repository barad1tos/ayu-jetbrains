package dev.ayuislands.studio.prototype

import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.syntax.SyntaxPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioPrototypeSessionTest {
    @Test
    fun `partial acceptance survives equal desired and actual values`() {
        val session = StudioPrototypeSession()
        session.setIncluded(StudioPrototypeSection.FONT, true)
        session.stageCreateProfile("Same font")
        session.apply()
        assertEquals(
            StudioPrototypeResult.PARTIAL,
            session.activateProfile("Same font", StudioPrototypeOutcome.FONT_UNAVAILABLE),
        )
        assertEquals(session.current, session.draft)

        session.editAppearance { it }

        assertFalse(session.hasPendingChanges)
        assertTrue(session.statusMessage.contains("Partially available"))
        assertEquals(StudioPrototypeResult.PARTIAL, session.apply(StudioPrototypeOutcome.FONT_UNAVAILABLE))
        assertTrue(session.isPartiallyApplied)
        session.apply()
        assertFalse(session.statusMessage.contains("Partially available"))
    }

    @Test
    fun `partial acceptance remains visible through no-op edits and reverted changes`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it.copy(fontSize = 21f) }
        session.apply(StudioPrototypeOutcome.FONT_UNAVAILABLE)

        session.editAppearance { it }
        assertTrue(session.statusMessage.contains("Partially available"))
        session.editAppearance { it.copy(glowEnabled = false) }
        assertTrue(session.statusMessage.contains("Modified"))
        assertTrue(session.statusMessage.contains("Partially available"))
        session.editAppearance { it.copy(glowEnabled = true) }
        session.setIncluded(StudioPrototypeSection.GLOW, false)
        session.setIncluded(StudioPrototypeSection.GLOW, true)

        assertFalse(session.hasPendingChanges)
        assertEquals(14f, session.current.fontSize)
        assertEquals(21f, session.draft.fontSize)
        assertTrue(session.statusMessage.contains("Partially available"))
        session.apply()
        assertEquals(21f, session.current.fontSize)
        assertFalse(session.statusMessage.contains("Partially available"))
    }

    @Test
    fun `partial profile acceptance remains visible through unchanged update and staged create`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Presentation", StudioPrototypeOutcome.FONT_UNAVAILABLE)

        session.stageUpdateProfile()

        assertFalse(session.hasPendingChanges)
        assertTrue(session.statusMessage.contains("Partially available"))
        session.stageCreateProfile("Follow-up")
        assertTrue(session.statusMessage.contains("Partially available"))
        assertTrue(session.statusMessage.contains("Unsaved profile"))
        assertEquals(14f, session.current.fontSize)
        assertEquals(18f, session.draft.fontSize)
        session.apply()
        assertEquals(18f, session.current.fontSize)
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `successful baseline stays clean after no-op edits and reversible view choices`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it }
        session.editAppearance { it.copy(glowEnabled = false) }
        session.editAppearance { it.copy(glowEnabled = true) }
        session.setIncluded(StudioPrototypeSection.GLOW, false)
        session.setIncluded(StudioPrototypeSection.GLOW, true)

        assertFalse(session.hasPendingChanges)
        assertFalse(session.statusMessage.contains("Partially available"))
        assertEquals(StudioPrototypeAppearance(), session.current)
    }

    @Test
    fun `create remains staged until apply and cancel discards it`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it.copy(vcsDiffIntensity = 70) }

        assertEquals(StudioPrototypeEditResult.STAGED, session.stageCreateProfile("Review"))
        assertFalse(session.profiles.any { it.name == "Review" })
        assertEquals(
            70,
            session.pendingProfiles
                .single { it.name == "Review" }
                .appearance.vcsDiffIntensity,
        )
        assertTrue(session.hasCatalogChanges)

        session.discardPending()

        assertEquals(33, session.draft.vcsDiffIntensity)
        assertFalse(session.pendingProfiles.any { it.name == "Review" })
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `cancel after apply keeps accepted appearance and profile`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it.copy(vcsDiffIntensity = 70) }
        session.stageCreateProfile("Review")
        session.apply()
        session.editAppearance { it.copy(vcsDiffIntensity = 90) }

        session.discardPending()

        assertEquals(70, session.current.vcsDiffIntensity)
        assertEquals(70, session.draft.vcsDiffIntensity)
        assertEquals(
            70,
            session.profiles
                .single { it.name == "Review" }
                .appearance.vcsDiffIntensity,
        )
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `catalog only apply does not activate a profile or change appearance`() {
        val initial = StudioPrototypeAppearance(fontSize = 16f, glowEnabled = false)
        val session = StudioPrototypeSession(initial)
        session.stageCreateProfile("My setup")

        assertEquals(StudioPrototypeResult.APPLIED, session.apply())

        assertEquals(initial, session.current)
        assertNull(session.activeProfileName)
        assertTrue(session.profiles.any { it.name == "My setup" })
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `update prepares a saved definition without changing it immediately`() {
        val session = StudioPrototypeSession()
        session.stageCreateProfile("Review")
        session.apply()
        session.activateProfile("Review")
        session.editAppearance { it.copy(syntaxPreset = SyntaxPreset.NEON) }

        assertEquals(StudioPrototypeEditResult.STAGED, session.stageUpdateProfile())
        assertEquals(
            SyntaxPreset.AMBIENT,
            session.profiles
                .single { it.name == "Review" }
                .appearance.syntaxPreset,
        )
        assertEquals(
            SyntaxPreset.NEON,
            session.pendingProfiles
                .single { it.name == "Review" }
                .appearance.syntaxPreset,
        )

        session.apply()

        assertEquals(
            SyntaxPreset.NEON,
            session.profiles
                .single { it.name == "Review" }
                .appearance.syntaxPreset,
        )
    }

    @Test
    fun `popup activation refuses dirty appearance and staged catalog`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it.copy(glowEnabled = false) }

        assertEquals(StudioPrototypeResult.DIRTY_BLOCKED, session.activateProfile("Evening review"))
        assertNull(session.activeProfileName)
        assertFalse(session.draft.glowEnabled)

        session.discardPending()
        session.stageCreateProfile("Review")

        assertEquals(StudioPrototypeResult.DIRTY_BLOCKED, session.activateProfile("Evening review"))
        assertTrue(session.hasCatalogChanges)
    }

    @Test
    fun `glow off on and style changes preserve independent intensities`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it.copy(glowIntensities = it.glowIntensities + (GlowStyle.SOFT to 61)) }
        session.editAppearance { it.copy(glowEnabled = false) }
        session.editAppearance { it.copy(glowStyle = GlowStyle.SHARP_NEON) }
        session.editAppearance { it.copy(glowEnabled = true, glowStyle = GlowStyle.SOFT) }
        session.apply()

        assertEquals(
            mapOf(GlowStyle.SOFT to 61, GlowStyle.SHARP_NEON to 35, GlowStyle.GRADIENT to 25),
            session.current.glowIntensities,
        )
        assertEquals(GlowStyle.SOFT, session.current.glowStyle)
        assertTrue(session.current.glowEnabled)
    }

    @Test
    fun `membership edits do not change appearance and survive accepted round trip`() {
        val session = StudioPrototypeSession()
        val initial = session.current
        session.setIncluded(StudioPrototypeSection.FONT, true)
        session.setIncluded(StudioPrototypeSection.GLOW, false)

        assertEquals(initial, session.draft)
        assertTrue(session.hasPendingChanges)
        session.apply()
        session.setIncluded(StudioPrototypeSection.ACCENT, true)
        session.discardPending()

        assertEquals(
            setOf(StudioPrototypeSection.FONT, StudioPrototypeSection.SYNTAX, StudioPrototypeSection.VCS),
            session.includedSections,
        )
        assertEquals(session.includedSections, session.currentIncludedSections)
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `profile switching restores excluded sections from base`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Presentation")
        assertEquals(18f, session.current.fontSize)

        session.activateProfile("Evening review")

        assertEquals(14f, session.current.fontSize)
        assertEquals("#FFCD66", session.current.accentHex)
        session.activateProfile(null)

        assertEquals(StudioPrototypeAppearance(), session.current)
        assertNull(session.activeProfileName)
    }

    @Test
    fun `manual edits outside active profile survive return to yours`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Evening review")
        session.editAppearance { it.copy(accentHex = "#99AAFF", vcsDiffIntensity = 76) }
        session.apply()
        session.activateProfile(null)

        assertEquals("#99AAFF", session.current.accentHex)
        assertEquals(33, session.current.vcsDiffIntensity)
    }

    @Test
    fun `removing a section in an accepted update retains its manual value in yours`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Evening review")
        session.setIncluded(StudioPrototypeSection.VCS, false)
        session.editAppearance { it.copy(vcsDiffIntensity = 76) }
        session.stageUpdateProfile()
        session.apply()

        session.activateProfile(null)

        assertEquals(76, session.current.vcsDiffIntensity)
    }

    @Test
    fun `adding a section in an accepted update preserves the previous base value`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Evening review")
        session.setIncluded(StudioPrototypeSection.ACCENT, true)
        session.editAppearance { it.copy(accentHex = "#99AAFF") }
        session.stageUpdateProfile()
        session.apply()

        session.activateProfile(null)

        assertEquals("#FFCD66", session.current.accentHex)
        session.activateProfile("Evening review")
        assertEquals("#99AAFF", session.current.accentHex)
    }

    @Test
    fun `plain apply does not implicitly update active saved definition`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Evening review")
        val saved = session.profiles.single { it.name == "Evening review" }
        session.editAppearance { it.copy(vcsDiffIntensity = 76) }
        session.apply()

        assertEquals(saved, session.profiles.single { it.name == "Evening review" })
        assertEquals(76, session.current.vcsDiffIntensity)
    }

    @Test
    fun `unavailable font keeps current value and retains desired profile payload for retry`() {
        val session = StudioPrototypeSession()
        session.setIncluded(StudioPrototypeSection.FONT, true)
        session.editAppearance { it.copy(fontSize = 22f, vcsDiffIntensity = 72) }
        session.stageCreateProfile("Large review")

        assertEquals(StudioPrototypeResult.PARTIAL, session.apply(StudioPrototypeOutcome.FONT_UNAVAILABLE))
        assertEquals(14f, session.current.fontSize)
        assertEquals(72, session.current.vcsDiffIntensity)
        assertEquals(22f, session.draft.fontSize)
        assertEquals(
            22f,
            session.profiles
                .single { it.name == "Large review" }
                .appearance.fontSize,
        )
        assertFalse(session.hasPendingChanges)

        assertEquals(StudioPrototypeResult.APPLIED, session.apply())
        assertEquals(22f, session.current.fontSize)
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `unavailable profile font restores base rather than prior profile font`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Presentation")
        assertEquals(18f, session.current.fontSize)

        assertEquals(
            StudioPrototypeResult.PARTIAL,
            session.activateProfile("Presentation", StudioPrototypeOutcome.FONT_UNAVAILABLE),
        )
        assertEquals(14f, session.current.fontSize)
        assertEquals(18f, session.draft.fontSize)
        assertEquals(
            18f,
            session.profiles
                .single { it.name == "Presentation" }
                .appearance.fontSize,
        )
    }

    @Test
    fun `rejected apply preserves current and all pending work`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it.copy(fontSize = 20f) }
        session.stageCreateProfile("Review")

        assertEquals(StudioPrototypeResult.REJECTED, session.apply(StudioPrototypeOutcome.REJECTED))
        assertEquals(14f, session.current.fontSize)
        assertEquals(20f, session.draft.fontSize)
        assertTrue(session.hasCatalogChanges)
        assertFalse(session.profiles.any { it.name == "Review" })

        assertEquals(StudioPrototypeResult.APPLIED, session.apply())
        assertEquals(20f, session.current.fontSize)
        assertTrue(session.profiles.any { it.name == "Review" })
    }

    @Test
    fun `rejected activation keeps the previous active profile`() {
        val session = StudioPrototypeSession()
        session.activateProfile("Presentation")
        val current = session.current

        assertEquals(
            StudioPrototypeResult.REJECTED,
            session.activateProfile("Evening review", StudioPrototypeOutcome.REJECTED),
        )
        assertEquals("Presentation", session.activeProfileName)
        assertEquals(current, session.current)
    }

    @Test
    fun `no project permits catalog editing but not profile activation`() {
        val session = StudioPrototypeSession(isProjectAvailable = false)
        session.stageCreateProfile("Review")
        session.apply()

        assertTrue(session.profiles.any { it.name == "Review" })
        assertEquals(StudioPrototypeResult.NO_PROJECT, session.activateProfile("Review"))
        assertNull(session.activeProfileName)
        assertEquals(StudioPrototypeResult.APPLIED, session.activateProfile(null))
    }

    @Test
    fun `invalid names and missing profiles preserve catalog order`() {
        val session = StudioPrototypeSession()
        val original = session.profiles

        assertEquals(StudioPrototypeEditResult.NAME_REQUIRED, session.stageCreateProfile("  "))
        assertEquals(StudioPrototypeEditResult.NAME_EXISTS, session.stageCreateProfile(" evening REVIEW "))
        assertEquals(StudioPrototypeEditResult.NO_ACTIVE_PROFILE, session.stageUpdateProfile())
        assertEquals(StudioPrototypeResult.UNKNOWN_PROFILE, session.activateProfile("Missing"))
        assertEquals(original, session.profiles)
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `update keeps profile order and cancel discards only pending definition`() {
        val session = StudioPrototypeSession()
        val original = session.profiles
        session.activateProfile("Evening review")
        session.editAppearance { it.copy(vcsDiffIntensity = 64) }
        session.stageUpdateProfile()

        assertEquals(original.map { it.name }, session.pendingProfiles.map { it.name })
        session.discardPending()

        assertEquals(original, session.pendingProfiles)
        assertFalse(session.hasPendingChanges)
    }

    @Test
    fun `mutable input collections cannot rewrite accepted or staged snapshots`() {
        val intensities = mutableMapOf(GlowStyle.SOFT to 20, GlowStyle.SHARP_NEON to 35, GlowStyle.GRADIENT to 25)
        val session = StudioPrototypeSession(StudioPrototypeAppearance(glowIntensities = intensities))
        intensities[GlowStyle.SOFT] = 99
        assertEquals(20, session.current.glowIntensities[GlowStyle.SOFT])
        session.editAppearance { it.copy(glowIntensities = intensities) }
        session.stageCreateProfile("Review")
        intensities[GlowStyle.SOFT] = 77

        assertEquals(99, session.draft.glowIntensities[GlowStyle.SOFT])
        assertEquals(
            99,
            session.pendingProfiles
                .single { it.name == "Review" }
                .appearance.glowIntensities[GlowStyle.SOFT],
        )
        assertFailsWith<UnsupportedOperationException> {
            (session.current.glowIntensities as MutableMap)[GlowStyle.SOFT] = 44
        }
        assertFailsWith<UnsupportedOperationException> {
            (session.includedSections as MutableSet).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (session.pendingProfiles as MutableList).clear()
        }
    }

    @Test
    fun `cancel after partial acceptance retains actual and desired font values`() {
        val session = StudioPrototypeSession()
        session.editAppearance { it.copy(fontSize = 22f) }
        session.apply(StudioPrototypeOutcome.FONT_UNAVAILABLE)
        session.editAppearance { it.copy(vcsDiffIntensity = 98) }

        session.discardPending()

        assertEquals(14f, session.current.fontSize)
        assertEquals(22f, session.draft.fontSize)
        assertEquals(33, session.draft.vcsDiffIntensity)
        assertEquals(StudioPrototypeResult.PARTIAL, session.lastResult)
        assertFalse(session.hasPendingChanges)
    }
}
