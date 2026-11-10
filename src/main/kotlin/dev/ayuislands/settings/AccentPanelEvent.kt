package dev.ayuislands.settings

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.settings.mappings.MappingsPanelAction
import dev.ayuislands.studio.model.SystemAccentPolicy

/** Completed explicit preferences; resource requests and confirmations remain separate host actions. */
internal sealed interface AccentPanelEvent {
    data class Color(
        val variant: AyuVariant,
        val hex: String,
    ) : AccentPanelEvent

    data class ResetColor(
        val variant: AyuVariant,
    ) : AccentPanelEvent

    data class FollowSystem(
        val value: Boolean,
    ) : AccentPanelEvent

    data class RotationEnabled(
        val value: Boolean,
    ) : AccentPanelEvent

    data class RotationMode(
        val value: AccentRotationMode,
    ) : AccentPanelEvent

    data class RotationInterval(
        val value: Int,
    ) : AccentPanelEvent

    data class SystemPolicy(
        val value: SystemAccentPolicy,
    ) : AccentPanelEvent

    data class ChromeEnabled(
        val value: Boolean,
    ) : AccentPanelEvent

    data class ChromeSurface(
        val id: AccentElementId,
        val value: Boolean,
    ) : AccentPanelEvent

    data class ChromeIntensity(
        val value: Int,
    ) : AccentPanelEvent

    data class Element(
        val id: AccentElementId,
        val value: Boolean,
    ) : AccentPanelEvent

    data class AllElements(
        val value: Boolean,
    ) : AccentPanelEvent

    data class TabMode(
        val value: GlowTabMode,
    ) : AccentPanelEvent

    data class UnderlineHeight(
        val value: Int,
    ) : AccentPanelEvent

    data class UnderlineSync(
        val value: Boolean,
    ) : AccentPanelEvent

    data class BracketScope(
        val value: Boolean,
    ) : AccentPanelEvent

    data class SharedToggle(
        val target: AccentSharedToggle,
        val value: Boolean,
    ) : AccentPanelEvent

    data class NightTheme(
        val value: String,
    ) : AccentPanelEvent
}

internal sealed interface AccentPanelAction {
    data class Custom(
        val variant: AyuVariant,
    ) : AccentPanelAction

    data class Shuffle(
        val variant: AyuVariant,
    ) : AccentPanelAction

    data class Reset(
        val variant: AyuVariant,
    ) : AccentPanelAction

    data object Unlock : AccentPanelAction

    data class Expanded(
        val group: AccentPanelGroup,
        val value: Boolean,
    ) : AccentPanelAction

    data class Mapping(
        val action: MappingsPanelAction,
    ) : AccentPanelAction
}
