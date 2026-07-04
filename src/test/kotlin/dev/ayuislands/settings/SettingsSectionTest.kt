package dev.ayuislands.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral coverage for [SettingsSection] — the pending/stored machine every
 * settings panel delegates to. Zero mocks: the snapshot source is a plain
 * `var` the tests mutate to simulate persisted-state changes.
 *
 * Branches locked:
 *  - fresh section is clean; [SettingsSection.update] diverges pending only
 *  - [SettingsSection.load] pulls the persisted baseline into both values
 *  - [SettingsSection.resetToStored] is the in-memory reset (ignores newer
 *    persisted state, unlike [SettingsSection.load])
 *  - [SettingsSection.commit] no-ops when clean, converges stored when dirty,
 *    leaves both values untouched when the action throws (retry contract),
 *    and honors mid-action normalization via [SettingsSection.update]
 */
class SettingsSectionTest {
    private data class Draft(
        val name: String = "default",
        val level: Int = 0,
    )

    private var persisted = Draft(name = "persisted", level = 7)

    private fun newSection(): SettingsSection<Draft> = SettingsSection(initial = Draft()) { persisted }

    @Test
    fun `fresh section starts clean at the initial snapshot`() {
        val section = newSection()

        assertEquals(Draft(), section.pending, "pending must start at the initial snapshot")
        assertEquals(Draft(), section.stored, "stored must start at the initial snapshot")
        assertFalse(section.isModified(), "fresh section must not report modified")
    }

    @Test
    fun `update diverges pending and leaves stored at the baseline`() {
        val section = newSection()

        section.update { it.copy(level = 42) }

        assertEquals(42, section.pending.level, "update must rewrite pending")
        assertEquals(Draft(), section.stored, "update must never touch stored")
        assertTrue(section.isModified(), "pending != stored must report modified")
    }

    @Test
    fun `load pulls the persisted snapshot into both pending and stored`() {
        val section = newSection()
        section.update { it.copy(level = 42) }

        section.load()

        assertEquals(persisted, section.pending, "load must align pending with persisted state")
        assertEquals(persisted, section.stored, "load must align stored with persisted state")
        assertFalse(section.isModified(), "section must be clean right after load")
    }

    @Test
    fun `load followed by a normalizing update leaves a deliberate diff`() {
        // The clamp-legacy-values pattern: stored keeps the raw persisted value,
        // pending holds the normalized one, so the Apply button surfaces.
        persisted = Draft(name = "legacy", level = 90)
        val section = newSection()

        section.load()
        section.update { it.copy(level = it.level.coerceAtMost(50)) }

        assertEquals(90, section.stored.level, "stored must keep the raw persisted value")
        assertEquals(50, section.pending.level, "pending must hold the normalized value")
        assertTrue(section.isModified(), "normalization diff must mark the section modified")
    }

    @Test
    fun `resetToStored reverts pending in-memory and returns the restored value`() {
        val section = newSection()
        section.load()
        section.update { it.copy(name = "edited") }

        val restored = section.resetToStored()

        assertEquals(persisted, restored, "resetToStored must return the restored snapshot")
        assertEquals(persisted, section.pending, "pending must return to stored")
        assertFalse(section.isModified(), "section must be clean after resetToStored")
    }

    @Test
    fun `resetToStored ignores newer persisted state while load re-reads it`() {
        val section = newSection()
        section.load()
        val baseline = section.stored
        persisted = Draft(name = "changed-behind-the-panel", level = 99)

        section.update { it.copy(level = 1) }
        section.resetToStored()
        assertEquals(baseline, section.pending, "resetToStored must revert to the in-memory baseline")

        section.load()
        assertEquals(persisted, section.pending, "load must re-read the newer persisted state")
        assertEquals(persisted, section.stored, "load must rebase stored on the newer persisted state")
    }

    @Test
    fun `commit is a no-op when nothing diverged`() {
        val section = newSection()
        section.load()
        var invoked = false

        section.commit { _, _ -> invoked = true }

        assertFalse(invoked, "commit must not run its action on a clean section")
    }

    @Test
    fun `commit hands the action the diverged pending and the pre-commit stored`() {
        val section = newSection()
        section.load()
        section.update { it.copy(level = 42) }
        var observedPending: Draft? = null
        var observedStored: Draft? = null

        section.commit { pending, stored ->
            observedPending = pending
            observedStored = stored
        }

        assertEquals(persisted.copy(level = 42), observedPending, "action must see the edited pending")
        assertEquals(persisted, observedStored, "action must see the pre-commit stored baseline")
        assertEquals(section.pending, section.stored, "stored must converge onto pending after commit")
        assertFalse(section.isModified(), "section must be clean after a successful commit")
    }

    @Test
    fun `commit leaves both values untouched when the action throws`() {
        val section = newSection()
        section.load()
        section.update { it.copy(level = 42) }

        assertFailsWith<IllegalStateException> {
            section.commit { _, _ -> error("simulated apply failure") }
        }

        assertEquals(42, section.pending.level, "pending must survive a failed commit for retry")
        assertEquals(persisted, section.stored, "stored must stay at the baseline when the action throws")
        assertTrue(section.isModified(), "section must stay modified so the user can retry")
    }

    @Test
    fun `commit converges stored onto pending as normalized by the action`() {
        // The Effects-panel pattern: the action folds preset values into pending
        // before persisting; stored must pick up the normalized value.
        val section = newSection()
        section.load()
        section.update { it.copy(level = 42) }

        section.commit { _, _ ->
            section.update { it.copy(level = 40, name = "normalized") }
        }

        assertEquals(
            persisted.copy(level = 40, name = "normalized"),
            section.stored,
            "stored must converge onto the pending value observed after the action",
        )
        assertFalse(section.isModified(), "section must be clean after a normalizing commit")
    }
}
