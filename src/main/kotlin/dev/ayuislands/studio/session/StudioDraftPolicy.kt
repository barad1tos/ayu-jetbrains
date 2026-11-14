package dev.ayuislands.studio.session

import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.migration.StudioFieldMapping
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioList

internal object StudioDraftPolicy {
    fun edit(
        draft: DraftRecord,
        address: FieldAddress,
        value: FieldValue,
        rotationOrigin: RotationAcceptanceOrigin? = null,
    ): DraftRecord {
        val original = StudioAppearanceFields.field(draft.tracking.initial, address.field)
        val current = StudioAppearanceFields.field(draft.appearance, address.field)
        val edited = StudioFieldEdits.edit(current, address.part, value)
        val replacement = if (edited.value == original.value) original else edited
        val appearance = StudioAppearanceFields.replace(draft.appearance, replacement)
        val touches =
            draft.tracking.touched
                .filterNot { it == address }
                .toMutableList()
        when {
            replacement.value == original.value -> touches.removeAll { it.field == address.field }
            address.part == FieldPart.Value -> {
                touches.removeAll { it.field == address.field }
                touches.add(address)
            }
            StudioAppearanceFields.value(appearance, address) !=
                StudioAppearanceFields.value(draft.tracking.initial, address) &&
                touches.none { it.field == address.field && it.part == FieldPart.Value } -> touches.add(address)
        }
        trackEntryOrder(address, original.value, edited.value, touches)
        val origin =
            when {
                draft.tracking.intent == DraftIntent.FULL_PROFILE -> {
                    RotationAcceptanceOrigin.PROFILE_ACTIVATION
                }

                draft.tracking.rotationOrigin == RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE &&
                    rotationOrigin == RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT -> {
                    RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE
                }

                rotationOrigin != null -> {
                    rotationOrigin
                }

                else -> {
                    draft.tracking.rotationOrigin
                }
            }
        return draft.copy(
            appearance = appearance,
            tracking = draft.tracking.copy(touched = StudioList(touches), rotationOrigin = origin),
        )
    }

    fun isDirty(draft: DraftRecord): Boolean =
        draft.appearance != draft.tracking.initial || draft.tracking.reapply.isNotEmpty()

    private fun trackEntryOrder(
        address: FieldAddress,
        original: RawValue,
        edited: RawValue,
        touches: MutableList<FieldAddress>,
    ) {
        if (address.part !is FieldPart.MapEntry) return
        if (original !is RawValue.OrderedMap || edited !is RawValue.OrderedMap) return
        val order = FieldAddress(address.field, FieldPart.MapOrder)
        if (FieldAddress(address.field, FieldPart.Value) in touches) return
        val before = original.entries.map { it.key }
        val after = edited.entries.map { it.key }
        val defaultOrder = before.filter { it in after } + after.filterNot { it in before }
        if (after == defaultOrder) {
            touches.remove(order)
        } else if (order !in touches) {
            touches.add(order)
        }
    }

    fun hiddenDirty(work: SessionWork): List<DraftRecord> = work.drafts.filter { it.id != work.selected && isDirty(it) }

    fun select(
        work: SessionWork,
        profile: ProfileDefinition,
        id: DraftId,
        current: AcceptedAppearance,
    ): SessionWork {
        val existing =
            work.drafts.singleOrNull {
                it.source?.id == profile.id &&
                    it.tracking.intent == DraftIntent.FULL_PROFILE
            }
        if (existing != null) {
            return work.copy(
                selected = existing.id,
                drafts =
                    StudioList(
                        work.drafts.map {
                            if (it.id ==
                                existing.id
                            ) {
                                it.copy(
                                    sourceName = profile.name,
                                    tracking =
                                        it.tracking.copy(
                                            rotationOrigin = RotationAcceptanceOrigin.PROFILE_ACTIVATION,
                                        ),
                                )
                            } else {
                                it
                            }
                        },
                    ),
            )
        }
        require(work.drafts.none { it.id == id }) { "Draft identity is already in use" }
        val reference =
            when (profile) {
                is ProfileDefinition.Factory -> ProfileReference(profile.id, profile.recipeVersion.toLong())
                is ProfileDefinition.User -> ProfileReference(profile.id, profile.revision)
            }
        val appearance = StudioFieldMapping.copyAppearance(profile.appearance)
        val draft =
            DraftRecord(
                id,
                reference,
                current,
                appearance,
                sourceName = profile.name,
                tracking =
                    DraftTracking(
                        appearance,
                        intent = DraftIntent.FULL_PROFILE,
                        rotationOrigin = RotationAcceptanceOrigin.PROFILE_ACTIVATION,
                    ),
            )
        return work.copy(drafts = StudioList(work.drafts + draft), selected = draft.id)
    }

    fun discard(draft: DraftRecord): DraftRecord =
        draft.copy(
            appearance = draft.tracking.initial,
            tracking =
                draft.tracking.copy(
                    touched = StudioList(emptyList()),
                    reapply = StudioList(emptyList()),
                    rotationOrigin = null,
                ),
        )
}
