package dev.ayuislands.studio

import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontOwnershipTransition
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.session.StudioFontPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioFontPolicyTest {
    private val baseline = snapshot("Before", 13f)
    private val applied = snapshot("Applied", 15f)
    private val requested = snapshot("Requested", 17f)
    private val manual = snapshot("Personal", 19f)
    private val record = FontOwnershipRecord(FontOwnershipStatus.OWNED, baseline, applied)

    @Test
    fun `ordinary owned Apply keeps its managed baseline through another profile`() {
        assertNull(StudioFontPolicy.change(EDITOR, record, applied, requested))
        val write =
            assertIs<FontOwnershipTransition.Write>(
                StudioFontPolicy.apply(EDITOR, record, applied, requested, emptyList()),
            )
        assertEquals(baseline, write.record.baseline)
        assertEquals(requested, write.target)
    }

    @Test
    fun `foreign whole surface requires review and KeepLatest never claims its manual fields`() {
        assertFailsWith<IllegalStateException> {
            StudioFontPolicy.apply(EDITOR, record, manual, requested, emptyList())
        }
        val change = assertNotNull(StudioFontPolicy.change(EDITOR, record, manual, requested))
        val retained =
            assertIs<FontOwnershipTransition.Retain>(
                StudioFontPolicy.apply(
                    EDITOR,
                    record,
                    manual,
                    requested,
                    listOf(NativeResolution(change, ConflictChoice.KEEP_LATEST)),
                ),
            )
        assertEquals(FontOwnershipStatus.SUSPENDED, retained.record.status)
        assertEquals(baseline, retained.record.baseline)
        assertEquals(applied, retained.record.applied)
    }

    @Test
    fun `exact reviewed reacquisition starts from the manual baseline and cannot authorize another surface`() {
        val change = assertNotNull(StudioFontPolicy.change(EDITOR, record, manual, requested))
        val choices = listOf(NativeResolution(change, ConflictChoice.USE_MINE))
        val write =
            assertIs<FontOwnershipTransition.Write>(
                StudioFontPolicy.apply(EDITOR, record, manual, requested, choices),
            )
        assertEquals(manual, write.record.baseline)
        assertEquals(manual, write.record.applied)
        assertEquals(requested, write.target)
        assertEquals(FontOwnershipStatus.OWNED, write.record.status)
        assertFailsWith<IllegalStateException> { StudioFontPolicy.apply(CONSOLE, record, manual, requested, choices) }
        assertFailsWith<IllegalStateException> { StudioFontPolicy.apply(EDITOR, record, baseline, requested, choices) }
        assertFailsWith<IllegalStateException> { StudioFontPolicy.apply(EDITOR, record, manual, baseline, choices) }
    }

    @Test
    fun `suspended ownership stays revoked even when values later equal the old applied snapshot`() {
        val suspended = record.copy(status = FontOwnershipStatus.SUSPENDED)
        val change = assertNotNull(StudioFontPolicy.change(EDITOR, suspended, applied, requested))
        assertEquals(
            FontOwnershipTransition.Unchanged,
            StudioFontPolicy.apply(
                EDITOR,
                suspended,
                applied,
                requested,
                listOf(NativeResolution(change, ConflictChoice.KEEP_LATEST)),
            ),
        )
        val write =
            assertIs<FontOwnershipTransition.Write>(
                StudioFontPolicy.apply(
                    EDITOR,
                    suspended,
                    applied,
                    requested,
                    listOf(NativeResolution(change, ConflictChoice.USE_MINE)),
                ),
            )
        assertEquals(applied, write.record.baseline)
    }

    private fun snapshot(
        family: String,
        size: Float,
    ): FontSnapshot =
        FontSnapshot.Explicit(FontData(listOf(family), listOf(FontFamily(family, size)), size, 1.2f, false, null, null))

    private companion object {
        const val EDITOR = "canonical/editor-font"
        const val CONSOLE = "canonical/console-font"
    }
}
