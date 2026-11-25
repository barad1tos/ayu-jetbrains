package dev.ayuislands.studio.model

import dev.ayuislands.settings.VcsSection
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsIntensity

internal sealed interface StudioVcsEdit {
    data class Enable(
        val enabled: Boolean,
    ) : StudioVcsEdit

    data class Preset(
        val section: VcsSection,
        val value: VcsColorPreset,
    ) : StudioVcsEdit

    data class Intensity(
        val category: VcsColorCategory,
        val value: VcsIntensity,
    ) : StudioVcsEdit
}
