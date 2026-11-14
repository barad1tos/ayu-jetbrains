package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioDraftRebase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioDraftPolicyTest {
    @Test
    fun `master toggle touches only its own field and retains every hidden value`() {
        val document = catalogTestDocument()
        val draft = catalogTestWork(document).drafts.single()
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val original =
            draft.appearance.glow.records
                .single { it.key == address.field }
                .value as RawValue.BooleanValue
        val changed = StudioDraftPolicy.edit(draft, address, FieldValue.Present(RawValue.BooleanValue(!original.value)))
        assertEquals(listOf(address), changed.tracking.touched)
        assertTrue(StudioDraftPolicy.isDirty(changed))
        assertEquals(
            draft.appearance.glow.records
                .filterNot { it.key == address.field },
            changed.appearance.glow.records.filterNot {
                it.key ==
                    address.field
            },
        )
        assertEquals(draft.appearance.font, changed.appearance.font)
        assertEquals(draft.appearance.accent, changed.appearance.accent)
        val restored = StudioDraftPolicy.edit(changed, address, FieldValue.Present(original))
        assertFalse(StudioDraftPolicy.isDirty(restored))
        assertTrue(restored.tracking.touched.isEmpty())
        assertEquals(draft.appearance, restored.appearance)
    }

    @Test
    fun `browsing preserves hidden modified drafts but unedited browsing creates no hidden dirty work`() {
        val document = catalogTestDocument()
        val initial = catalogTestWork(document)
        val first =
            StudioDraftPolicy.select(
                initial,
                document.catalog.first(),
                DraftId("first"),
                document.currents.global,
            )
        assertEquals(DraftId("first"), first.selected)
        assertEquals(document.catalog.first().appearance, first.drafts.last().appearance)
        assertFalse(StudioDraftPolicy.isDirty(first.drafts.last()))
        val second = StudioDraftPolicy.select(first, document.catalog[1], DraftId("second"), document.currents.global)
        assertTrue(StudioDraftPolicy.hiddenDirty(second).isEmpty())
        val firstDraft = first.drafts.last()
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val wasEnabled =
            firstDraft.appearance.glow.records
                .single { it.key == address.field }
                .value as RawValue.BooleanValue
        val edited =
            StudioDraftPolicy.edit(
                firstDraft,
                address,
                FieldValue.Present(RawValue.BooleanValue(!wasEnabled.value)),
            )
        val withEdit = second.copy(drafts = StudioList(second.drafts.map { if (it.id == edited.id) edited else it }))
        assertEquals(listOf(edited.id), StudioDraftPolicy.hiddenDirty(withEdit).map { it.id })
        val returned =
            StudioDraftPolicy.select(
                withEdit,
                document.catalog.first(),
                DraftId("unused-allocation"),
                document.currents.global,
            )
        assertEquals(edited.id, returned.selected)
        assertEquals(edited, returned.drafts.single { it.id == edited.id })
        assertEquals(withEdit.drafts.size, returned.drafts.size)
    }

    @Test
    fun `disjoint external syntax edits preserve draft values and accepted key order`() {
        val document = catalogTestDocument()
        val base = catalogTestWork(document).drafts.single()
        val key = LegacyFieldKey(LegacyStore.SYNTAX, "customOverrides")
        val kotlinKey = "KOTLIN_FUNCTION_DECLARATION"
        val javaKey = "JAVA_CLASS_NAME"
        val ours = FieldAddress(key, FieldPart.MapEntry(kotlinKey))
        val theirs = FieldAddress(key, FieldPart.MapEntry(javaKey))
        val requested = FieldValue.Present(RawValue.Text("#FFCD66"))
        val external = FieldValue.Present(RawValue.Text("#5CCFE6"))
        val draft = StudioDraftPolicy.edit(base, ours, requested)
        val accepted = StudioDraftPolicy.edit(base, theirs, external).appearance
        val latest = base.base.copy(appearance = accepted, revision = base.base.revision + 1)

        val rebased = StudioDraftRebase.rebase(draft, latest)
        val overrides =
            rebased.appearance.syntax.records
                .single { it.key == key }
                .value as RawValue.OrderedMap
        val tail = overrides.entries.takeLast(2)

        val expected = listOf(RawValue.Entry(javaKey, external.value), RawValue.Entry(kotlinKey, requested.value))
        assertEquals(expected, tail)
        assertEquals(listOf(ours), rebased.tracking.touched)
        assertEquals(accepted, StudioDraftPolicy.discard(rebased).appearance)
        assertEquals(base.id, rebased.id)
    }

    @Test
    fun `explicit discard restores the exact session baseline`() {
        val document = catalogTestDocument()
        val initial = document.catalog.first().appearance
        val original =
            catalogTestWork(document).drafts.single().copy(
                appearance = initial,
                tracking = DraftTracking(initial),
            )
        val changed = original.copy(appearance = document.catalog[1].appearance)
        val discarded = StudioDraftPolicy.discard(changed)
        assertEquals(original, discarded)
        assertFalse(StudioDraftPolicy.isDirty(discarded))
    }
}
