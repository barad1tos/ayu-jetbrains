package dev.ayuislands.studio

import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.ExtensionResolution
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.PolicyResolution
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioAcceptanceMerge
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioSharedMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioAcceptanceMergeTest {
    @Test
    fun `a local edit leaves other accepted values in place and requires exact conflict approval`() {
        val original = catalogTestWork(catalogTestDocument()).drafts.single()
        val key =
            FieldAddress(LegacyFieldKey(LegacyStore.SYNTAX, "customOverrides"), FieldPart.MapEntry("JAVA_CLASS_NAME"))
        val requested = FieldValue.Present(RawValue.Text("#FFCD66"))
        val draft = StudioDraftPolicy.edit(original, key, requested)
        val other = StudioDraftPolicy.edit(original, key, FieldValue.Present(RawValue.Text("#5CCFE6")))
        val foreign = OpaqueRecord("future-glow", StudioBytes(byteArrayOf(-1)))
        val latest = other.appearance.copy(glow = other.appearance.glow.copy(extensions = StudioList(listOf(foreign))))
        val review = mutableListOf<AcceptanceReview>()

        assertEquals(latest, StudioAcceptanceMerge.appearance(draft, latest, AcceptanceChoices(), review))
        val conflict = assertIs<AcceptanceReview.Field>(review.single()).conflict
        assertEquals(requested, conflict.mine)
        val choices = AcceptanceChoices(fields = StudioList(listOf(FieldResolution(conflict, ConflictChoice.USE_MINE))))
        review.clear()
        val resolved = StudioAcceptanceMerge.appearance(draft, latest, choices, review)

        assertEquals(latest.glow, resolved.glow)
        assertEquals(requested, StudioAppearanceFields.value(resolved, key))
        assertTrue(review.isEmpty())

        val changedAgain =
            StudioDraftPolicy
                .edit(
                    original,
                    key,
                    FieldValue.Present(RawValue.Text("#B8CC52")),
                ).appearance
        assertEquals(changedAgain, StudioAcceptanceMerge.appearance(draft, changedAgain, choices, review))
        assertEquals(
            FieldValue.Present(RawValue.Text("#B8CC52")),
            assertIs<AcceptanceReview.Field>(review.single()).conflict.latest,
        )
    }

    @Test
    fun `full profile applies its snapshot and requires review before replacing unknown extension data`() {
        val document = catalogTestDocument()
        val definition = document.catalog.first()
        val id = DraftId("chosen")
        val selected = StudioDraftPolicy.select(catalogTestWork(document), definition, id, document.currents.global)
        val draft = selected.drafts.single { it.id == id }
        val requested = OpaqueRecord("requested", StudioBytes(byteArrayOf(4, 0)))
        val external = OpaqueRecord("external", StudioBytes(byteArrayOf(3, -1)))
        val mine =
            draft.copy(
                appearance =
                    draft.appearance.copy(
                        font = draft.appearance.font.copy(extensions = StudioList(listOf(requested))),
                    ),
            )
        val current = draft.base.appearance
        val latest = current.copy(font = current.font.copy(extensions = StudioList(listOf(external))))
        val review = mutableListOf<AcceptanceReview>()

        val unresolved = StudioAcceptanceMerge.appearance(mine, latest, AcceptanceChoices(), review)
        assertEquals(latest.font.extensions, unresolved.font.extensions)
        val conflict = assertIs<AcceptanceReview.Extensions>(review.single()).conflict
        val choices =
            AcceptanceChoices(extensions = StudioList(listOf(ExtensionResolution(conflict, ConflictChoice.USE_MINE))))
        review.clear()
        val applied = StudioAcceptanceMerge.appearance(mine, latest, choices, review)
        val requestedValues = StudioAppearanceFields.fields(mine.appearance).map { it.key to it.value }
        assertEquals(requestedValues, StudioAppearanceFields.fields(applied).map { it.key to it.value })
        assertEquals(mine.appearance.font.extensions, applied.font.extensions)
        val inheritedAccent = LegacyFieldKey(LegacyStore.SETTINGS, "followSystemAccent")
        assertEquals(
            StudioAppearanceFields.field(latest, inheritedAccent).origin,
            StudioAppearanceFields.field(applied, inheritedAccent).origin,
        )
        assertTrue(review.isEmpty())
        assertTrue(StudioAcceptanceMerge.hasAppearanceIntent(draft))
        assertFalse(StudioAcceptanceMerge.hasAppearanceIntent(catalogTestWork(document).drafts.single()))
    }

    @Test
    fun `shared policy conflict leaves accepted policy and unrelated fields unchanged until approved`() {
        val document = catalogTestDocument()
        val latest = document.shared.copy(systemAccentPolicy = SystemAccentPolicy.FORCE_OFF)
        val intent = SharedIntent.SystemPolicy(SystemAccentPolicy.BY_PROFILE, SystemAccentPolicy.FORCE_ON)
        val review = mutableListOf<AcceptanceReview>()

        assertEquals(latest, StudioSharedMerge.merge(latest, listOf(intent), AcceptanceChoices(), review))
        val conflict = assertIs<AcceptanceReview.Policy>(review.single()).conflict
        val choices = AcceptanceChoices(policy = PolicyResolution(conflict, ConflictChoice.USE_MINE))
        review.clear()

        assertEquals(
            latest.copy(systemAccentPolicy = intent.after),
            StudioSharedMerge.merge(latest, listOf(intent), choices, review),
        )
        assertTrue(review.isEmpty())
    }

    @Test
    fun `shared map additions preserve foreign keys and append local keys in draft order`() {
        val document = catalogTestDocument()
        val key = LegacyFieldKey(LegacyStore.MAPPINGS, "projectAccents")
        val original =
            document.shared.mappings.records
                .single { it.key == key }
        val accepted = original.copy(value = accents("external" to "#FFCD66"))
        val mine = original.copy(value = accents("local-first" to "#5CCFE6", "local-second" to "#B8CC52"))
        val mappings = document.shared.mappings
        val latest =
            document.shared.copy(
                mappings =
                    mappings.copy(
                        records =
                            StudioList(
                                mappings.records.map {
                                    if (it.key == key) accepted else it
                                },
                            ),
                    ),
            )
        val review = mutableListOf<AcceptanceReview>()

        val result =
            StudioSharedMerge.merge(
                latest,
                listOf(SharedIntent.Field(original, mine)),
                AcceptanceChoices(),
                review,
            )

        val expected = accents("external" to "#FFCD66", "local-first" to "#5CCFE6", "local-second" to "#B8CC52")
        assertEquals(
            expected,
            result.mappings.records
                .single { it.key == key }
                .value,
        )
        assertEquals(latest, result.copy(mappings = latest.mappings))
        assertTrue(review.isEmpty())
    }

    @Test
    fun `explicit project-map insertion keeps its requested position`() {
        val document = catalogTestDocument()
        val mappings = document.shared.mappings
        val original = mappings.records.single { it.key.name == "projectAccents" }
        val before = original.copy(value = accents("first" to "#73D0FF", "last" to "#FFCD66"))
        val after = before.copy(value = accents("first" to "#73D0FF", "new" to "#5CCFE6", "last" to "#FFCD66"))
        val latest =
            document.shared.copy(
                mappings =
                    mappings.copy(
                        records =
                            StudioList(
                                mappings.records.map {
                                    if (it.key ==
                                        before.key
                                    ) {
                                        before
                                    } else {
                                        it
                                    }
                                },
                            ),
                    ),
            )
        val review = mutableListOf<AcceptanceReview>()

        val applied =
            StudioSharedMerge.merge(
                latest,
                listOf(SharedIntent.Field(before, after)),
                AcceptanceChoices(),
                review,
            )

        assertEquals(
            after.value,
            applied.mappings.records
                .single { it.key == before.key }
                .value,
        )
        assertTrue(review.isEmpty())
    }

    @Test
    fun `explicit insertion preserves an external color edit without asking about order`() {
        val document = catalogTestDocument()
        val mappings = document.shared.mappings
        val original = mappings.records.single { it.key.name == "projectAccents" }
        val before = original.copy(value = accents("first" to "#73D0FF", "last" to "#FFCD66"))
        val after = before.copy(value = accents("first" to "#73D0FF", "new" to "#5CCFE6", "last" to "#FFCD66"))
        val accepted = before.copy(value = accents("first" to "#B8CC52", "last" to "#FFCD66"))
        val latest =
            document.shared.copy(
                mappings =
                    mappings.copy(
                        records =
                            StudioList(
                                mappings.records.map {
                                    if (it.key ==
                                        before.key
                                    ) {
                                        accepted
                                    } else {
                                        it
                                    }
                                },
                            ),
                    ),
            )
        val review = mutableListOf<AcceptanceReview>()

        val applied =
            StudioSharedMerge.merge(
                latest,
                listOf(SharedIntent.Field(before, after)),
                AcceptanceChoices(),
                review,
            )

        val expected = accents("first" to "#B8CC52", "new" to "#5CCFE6", "last" to "#FFCD66")
        assertEquals(
            expected,
            applied.mappings.records
                .single { it.key == before.key }
                .value,
        )
        assertTrue(review.isEmpty())
    }

    private fun accents(vararg entries: Pair<String, String>): RawValue.OrderedMap =
        RawValue.OrderedMap(StudioList(entries.map { (key, color) -> RawValue.Entry(key, RawValue.Text(color)) }))
}
