package dev.ayuislands.studio.ui

import dev.ayuislands.settings.AccentPanelGroup
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioSettingsSection

/** Explicit UI gestures preserve their queue order even while the accepted value awaits publication. */
internal class StudioUiMetadata(
    private val opening: StudioSettingsBinding,
) {
    fun select(section: StudioSettingsSection) = edit("settingsSelectedTab", RawValue.Integer(section.ordinal))

    fun expand(
        group: AccentPanelGroup,
        value: Boolean,
    ) = edit(accentFields.getValue(group), RawValue.BooleanValue(value))

    fun expand(
        section: VcsSection,
        value: Boolean,
    ) = edit(vcsFields.getValue(section), RawValue.BooleanValue(value))

    fun acknowledge(id: String) {
        opening.metadata(StudioMetadataCommand.BadgesSeen(StudioList(listOf(id))))
    }

    fun edit(
        name: String,
        value: RawValue,
    ) {
        val address = FieldAddress(field(name).key)
        opening.metadata(StudioMetadataCommand.UiChoice(address, FieldValue.Present(value)))
    }

    private fun field(name: String) =
        opening.binding.document.auxiliary.ui.records
            .single { it.key.name == name }

    companion object {
        val accentFields =
            mapOf(
                AccentPanelGroup.SYSTEM to "systemGroupExpanded",
                AccentPanelGroup.OVERRIDES to "overridesGroupExpanded",
                AccentPanelGroup.CHROME to "chromeTintingGroupExpanded",
                AccentPanelGroup.ROTATION to "accentRotationGroupExpanded",
                AccentPanelGroup.ELEMENTS to "accentElementsGroupExpanded",
            )
        val vcsFields =
            mapOf(
                VcsSection.DIFF to "vcsDiffSectionExpanded",
                VcsSection.MERGE to "vcsMergeSectionExpanded",
                VcsSection.BLAME to "vcsBlameSectionExpanded",
            )
    }
}
