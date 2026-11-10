package dev.ayuislands.studio.model

/** Non-appearance facts submitted by the application's admitted producers. */
internal sealed interface StudioMetadataCommand {
    data class ReleaseSeen(
        val version: String,
        val nowMs: Long,
    ) : StudioMetadataCommand

    data class BadgesSeen(
        val ids: StudioList<String>,
    ) : StudioMetadataCommand

    /** An explicit user choice is ordered by admission and applies against the latest durable UI field. */
    data class UiChoice(
        val address: FieldAddress,
        val after: FieldValue,
    ) : StudioMetadataCommand

    data class UiEdit(
        val address: FieldAddress,
        val before: FieldValue,
        val after: FieldValue,
    ) : StudioMetadataCommand

    /** The application resource owner qualifies the completed job independently of its originating view. */
    data class FontInstalled(
        val job: FontJob,
        val familyName: String,
        val encodedPaths: String,
    ) : StudioMetadataCommand

    /** Successful and partial native removal share the existing explicit-uninstall inventory semantics. */
    data class FontRemoved(
        val job: FontJob,
        val familyName: String,
    ) : StudioMetadataCommand

    /** Null records an unavailable native probe; only the one-time attempt marker may change. */
    data class SeedFonts(
        val availableFamilies: StudioList<String>?,
    ) : StudioMetadataCommand

    data class Entitlement(
        val rawLicense: Boolean?,
        val nowMs: Long,
    ) : StudioMetadataCommand

    /** Liveness and source context are qualified by application admission before entering the store queue. */
    data class InitializeProject(
        val key: StudioProjectKey,
        val displayName: String,
        val sourceTarget: StudioTarget,
    ) : StudioMetadataCommand
}
