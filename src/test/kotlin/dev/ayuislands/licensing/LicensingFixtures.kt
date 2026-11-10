package dev.ayuislands.licensing

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.fields.StudioMetadataPolicy
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import kotlin.test.assertIs

/** Exercises the admitted metadata decision before the immutable legacy reader observes its result. */
internal fun recordLicenseObservation(state: AyuIslandsState): Boolean {
    val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(state))).document
    val accepted =
        requireNotNull(
            StudioMetadataPolicy.apply(
                document,
                StudioMetadataCommand.Entitlement(LicenseChecker.isLicensed(), LicenseChecker.nowMsSupplier()),
            ),
        )
    val observed = requireNotNull(StudioLegacyProjection.project(accepted, StudioTarget.Global)).settings
    state.lastKnownLicensedMs = observed.lastKnownLicensedMs
    state.lastConfirmedEntitlement = observed.lastConfirmedEntitlement
    return LicenseChecker.isLicensedOrGrace()
}

/** Returns the actual accepted projection; callers assert their non-default preferences on that output. */
internal fun confirmUnlicensed(
    state: AyuIslandsState,
    syntax: dev.ayuislands.syntax.SyntaxIntensityBaseState = dev.ayuislands.syntax.SyntaxIntensityBaseState(),
): dev.ayuislands.studio.persistence.StudioLegacyView {
    val capture = StudioFixtures.capture(state).copy(fields = StudioFixtures.fields(state, syntax))
    val before = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document
    val after =
        requireNotNull(StudioMetadataPolicy.apply(before, StudioMetadataCommand.Entitlement(false, Long.MAX_VALUE)))
    kotlin.test.assertEquals(before.currents, after.currents)
    kotlin.test.assertEquals(before.shared, after.shared)
    kotlin.test.assertEquals(before.catalog, after.catalog)
    kotlin.test.assertEquals(before.auxiliary, after.auxiliary)
    return requireNotNull(StudioLegacyProjection.project(after, StudioTarget.Global))
}
