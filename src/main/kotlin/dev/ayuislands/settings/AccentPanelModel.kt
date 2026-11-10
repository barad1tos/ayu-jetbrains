package dev.ayuislands.settings

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictEntry
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.settings.mappings.MappingsPanelView
import dev.ayuislands.studio.model.SystemAccentPolicy

/** Raw requested values; projections do not clamp or replace unavailable choices. */
internal data class AccentPanelSettings(
    val colors: Map<AyuVariant, String?>,
    val followSystemAccent: Boolean,
    val rotation: RotationIntent,
    val chrome: AccentChromeSettings,
    val elements: AccentElementSettings,
    val system: AccentSystemSettings,
    val systemAccentPolicy: SystemAccentPolicy,
    val effectiveFollowSystem: Boolean,
)

internal data class AccentChromeSettings(
    val enabled: Boolean,
    val surfaces: Map<AccentElementId, Boolean>,
    val intensity: Int,
)

internal data class AccentElementSettings(
    val toggles: Map<AccentElementId, Boolean>,
    val forceOverrides: List<String>,
    val tabMode: String?,
    val underlineHeight: Int,
    val underlineSync: Boolean,
    val bracketScope: Boolean,
)

internal data class AccentSystemSettings(
    val toggles: Map<AccentSharedToggle, Boolean>,
    val nightTheme: String?,
)

internal data class AccentPanelContext(
    val variant: AyuVariant,
    val isSystemAppearanceSupported: Boolean,
    val isSystemAccentSupported: Boolean,
    val isIslandsUi: Boolean,
    val isCustomHeaderActive: Boolean,
    val toolbarUnavailableReason: String?,
    val conflicts: Map<AccentElementId, ConflictEntry> = emptyMap(),
    val glowWidth: Int? = null,
)

internal data class AccentPanelView(
    val settings: AccentPanelSettings,
    val context: AccentPanelContext,
    val mappings: MappingsPanelView,
    val isEditable: Boolean,
    val isLicensed: Boolean,
)

/** Local editor sample only; native Chrome surfaces are not changed by this input. */
internal data class AccentPreviewInput(
    val hex: String,
    val elements: Map<AccentElementId, Boolean>,
    val conflicts: Set<AccentElementId> = emptySet(),
)

internal enum class AccentPanelGroup(
    val title: String,
) {
    SYSTEM("System"),
    OVERRIDES("Overrides"),
    CHROME("Chrome Tinting"),
    ROTATION("Accent Rotation"),
    ELEMENTS("Accent Elements"),
}

internal enum class AccentSharedToggle(
    val field: String,
    val title: String,
    val isPremium: Boolean = false,
) {
    SYNC_EDITOR_SCHEME("syncEditorScheme", "Sync editor color scheme with active Ayu theme"),
    FOLLOW_SYSTEM_APPEARANCE("followSystemAppearance", "Follow system appearance (Light / Dark)"),
    QUICK_SWITCHER_WIDGET("quickSwitcherWidgetEnabled", "Show quick-switcher widget in main toolbar"),
    PROJECT_ICON_ACCENT("projectIconAccentEnabled", "Automatically pin an accent from the project icon", true),
}
