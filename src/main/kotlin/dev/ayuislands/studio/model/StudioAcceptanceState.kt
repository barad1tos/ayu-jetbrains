package dev.ayuislands.studio.model

import dev.ayuislands.rotation.RotationCandidate

internal enum class AcceptanceOrigin { SETTINGS, QUICK_SWITCHER, INITIALIZATION }

internal enum class AuthorityStatus { READY, READ_ONLY, RECOVERY }

internal enum class TargetPresence { ACTIVE, INACTIVE, UNAVAILABLE }

internal data class TargetObservation(
    val target: StudioTarget,
    val generation: Long,
    val presence: TargetPresence,
)

internal enum class LimitationReason { PRO_REQUIRED, EXTERNAL_THEME, UNSUPPORTED_PLATFORM, MISSING_PLUGIN }

internal data class EffectLimitation(
    val owner: EffectOwner,
    val reason: LimitationReason,
    /** An unavailable enhancement may still require its supported owner to restore managed native state. */
    val blocksEffect: Boolean = true,
)

internal data class CapabilitySnapshot(
    val revision: Long,
    val limitations: StudioList<EffectLimitation>,
)

internal enum class FontAvailability {
    AVAILABLE,
    MISSING_CURATED,
    MISSING_CUSTOM,
    CORRUPTED,
    RESTART_REQUIRED,
    UNCONFIRMED,
}

internal data class FontObservation(
    val requested: StudioFields,
    val availability: FontAvailability,
)

internal data class NativeSurfaceChange(
    val owner: EffectOwner,
    val identity: String,
    val before: FieldValue,
    val actual: FieldValue,
    val requested: FieldValue,
)

internal data class NativeResolution(
    val change: NativeSurfaceChange,
    val choice: ConflictChoice,
)

internal data class PolicyConflict(
    val base: SystemAccentPolicy,
    val latest: SystemAccentPolicy,
    val mine: SystemAccentPolicy,
)

internal data class PolicyResolution(
    val conflict: PolicyConflict,
    val choice: ConflictChoice,
)

internal data class ExtensionConflict(
    val domain: StudioDomain,
    val base: StudioList<OpaqueRecord>,
    val latest: StudioList<OpaqueRecord>,
    val mine: StudioList<OpaqueRecord>,
)

internal data class ExtensionResolution(
    val conflict: ExtensionConflict,
    val choice: ConflictChoice,
)

internal data class AcceptanceChoices(
    val catalog: CatalogApprovals = CatalogApprovals(),
    val fields: StudioList<FieldResolution> = StudioList(emptyList()),
    val policy: PolicyResolution? = null,
    val native: StudioList<NativeResolution> = StudioList(emptyList()),
    val extensions: StudioList<ExtensionResolution> = StudioList(emptyList()),
)

internal data class AcceptanceRequest(
    val session: StudioSessionRecord,
    val transactionId: String,
    val targetGeneration: Long,
    val origin: AcceptanceOrigin,
    val choices: AcceptanceChoices = AcceptanceChoices(),
    val initialization: ProInitialization? = null,
    val iconPin: StudioProjectKey? = null,
)

/** Explicit first-Pro input; ordinary acceptance never infers initialization from entitlement. */
internal data class ProInitialization(
    val baseline: StudioDocument,
    val fresh: StudioFields,
)

internal data class AcceptanceContext(
    val authority: AuthorityStatus,
    val target: TargetObservation,
    val capabilities: CapabilitySnapshot,
    val font: FontObservation,
    val native: StudioList<NativeSurfaceChange> = StudioList(emptyList()),
    /** Current application-owned records, including edits not yet flushed by the draft journal. */
    val liveSessions: StudioList<StudioSessionRecord> = StudioList(emptyList()),
)

internal enum class AcceptanceBlock {
    INVALID_REQUEST,
    READ_ONLY,
    RECOVERY,
    TARGET_UNAVAILABLE,
    TARGET_REPLACED,
    ACTIVATE_TARGET,
    FONT_INSTALL,
    FONT_REINSTALL,
    FONT_SELECT,
    FONT_RESTART,
    FONT_REFRESH,
}

internal sealed interface AcceptanceReview {
    data class HiddenDraft(
        val id: DraftId,
    ) : AcceptanceReview

    data class LiveSession(
        val id: String,
    ) : AcceptanceReview

    data class Catalog(
        val item: CatalogReview,
    ) : AcceptanceReview

    data class Field(
        val conflict: FieldConflict,
    ) : AcceptanceReview

    data class Policy(
        val conflict: PolicyConflict,
    ) : AcceptanceReview

    data class Native(
        val change: NativeSurfaceChange,
    ) : AcceptanceReview

    data class Extensions(
        val conflict: ExtensionConflict,
    ) : AcceptanceReview
}

internal data class AcceptanceLease(
    val documentRevision: Long,
    val targetGeneration: Long,
    val capabilityRevision: Long,
)

internal sealed interface AcceptancePlan {
    data object Unchanged : AcceptancePlan

    data class Commit(
        val candidate: StudioDocument,
        val effects: StudioList<EffectOwner>,
        /** Null denotes automatic admission qualified by acquireRuntime, never a missing native lease. */
        val lease: AcceptanceLease?,
        val limitations: StudioList<EffectLimitation>,
        val nativeChoices: StudioList<NativeResolution>,
        val glowSelection: StudioGlowSelection,
        val rotationStep: RotationCandidate? = null,
        val themeChange: StudioThemeChange? = null,
        val integrationSelection: StudioIntegrationSelection = StudioIntegrationSelection(),
        val workspaceFields: StudioList<FieldAddress> = StudioList(emptyList()),
    ) : AcceptancePlan
}

internal sealed interface AcceptancePlanResult {
    data class Ready(
        val plan: AcceptancePlan,
    ) : AcceptancePlanResult

    data class ReviewRequired(
        val items: StudioList<AcceptanceReview>,
    ) : AcceptancePlanResult

    data class Blocked(
        val reasons: StudioList<AcceptanceBlock>,
    ) : AcceptancePlanResult
}
