package dev.ayuislands.studio.fields

import dev.ayuislands.font.FontCatalog
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.licensing.resolveEntitlement
import dev.ayuislands.settings.SettingsBadgeState
import dev.ayuislands.settings.SettingsBadges
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.LegacyValueType
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.profiles.StudioProjectState
import dev.ayuislands.studio.session.StudioTargets

/** Null means an invalid or stale command; untouched raw fields retain their values, origins, and order. */
internal object StudioMetadataPolicy {
    fun apply(
        document: StudioDocument,
        command: StudioMetadataCommand,
    ): StudioDocument? =
        when (command) {
            is StudioMetadataCommand.ReleaseSeen -> releaseSeen(document, command)
            is StudioMetadataCommand.BadgesSeen -> updateBadges(document) { SettingsBadges.markSeen(it, command.ids) }
            is StudioMetadataCommand.UiChoice -> chooseUi(document, command)
            is StudioMetadataCommand.UiEdit -> editUi(document, command)
            is StudioMetadataCommand.FontInstalled -> installFont(document, command)
            is StudioMetadataCommand.FontRemoved -> removeFont(document, command)
            is StudioMetadataCommand.SeedFonts -> seedFonts(document, command)
            is StudioMetadataCommand.Entitlement -> recordEntitlement(document, command)
            is StudioMetadataCommand.InitializeProject -> initializeProject(document, command)
        }

    private fun releaseSeen(
        document: StudioDocument,
        command: StudioMetadataCommand.ReleaseSeen,
    ): StudioDocument? {
        if (command.version.isBlank()) return null
        return updateBadges(document) { badges ->
            val firstInstall = badges.lastSeenVersion == null
            badges.lastSeenVersion = command.version
            if (firstInstall) {
                SettingsBadges.seedAllAcknowledged(badges)
            } else {
                SettingsBadges.expireIfDue(badges, command.nowMs)
                SettingsBadges.armExpiry(badges, command.nowMs)
            }
        }
    }

    private fun updateBadges(
        document: StudioDocument,
        update: (SettingsBadgeState) -> Unit,
    ): StudioDocument {
        val view = checkNotNull(StudioLegacyProjection.project(document, StudioTarget.Global))
        val badges = SettingsBadgeState.from(view.settings).also(update)
        val values =
            mapOf(
                "lastSeenVersion" to (badges.lastSeenVersion?.let(RawValue::Text) ?: RawValue.Null),
                "acknowledgedSettingsBadges" to
                    RawValue.OrderedSet(StudioList(badges.acknowledgedSettingsBadges.toList())),
                "settingsBadgeDeadlines" to
                    RawValue.OrderedMap(
                        StudioList(
                            badges.settingsBadgeDeadlines.map { (key, value) ->
                                RawValue.Entry(key, RawValue.Text(value))
                            },
                        ),
                    ),
                "settingsBadgesExpireAtMs" to RawValue.LongValue(badges.settingsBadgesExpireAtMs),
            )
        val fields =
            document.auxiliary.ui.copy(
                records =
                    StudioList(
                        document.auxiliary.ui.records.map { field ->
                            values[field.key.name]?.let {
                                StudioFieldEdits.edit(
                                    field,
                                    FieldPart.Value,
                                    FieldValue.Present(it),
                                )
                            }
                                ?: field
                        },
                    ),
            )
        return document.copy(auxiliary = document.auxiliary.copy(ui = fields))
    }

    private fun seedFonts(
        document: StudioDocument,
        command: StudioMetadataCommand.SeedFonts,
    ): StudioDocument {
        var resources = document.auxiliary.resources
        val marker = resources.records.single { it.key.name == "installedFontsSeeded" }
        if (marker.value == RawValue.BooleanValue(true)) return document
        val removed =
            resources.records
                .single {
                    it.key.name == "explicitlyUninstalledFonts"
                }.value as RawValue.OrderedSet
        val available = command.availableFamilies.orEmpty().toSet()
        for (entry in FontCatalog.entries) {
            if (entry.familyName in available && entry.familyName !in removed.values) {
                resources = setMembership(resources, "installedFonts", entry.familyName, shouldInclude = true)
            }
        }
        resources =
            replace(
                resources,
                StudioFieldEdits.edit(
                    marker,
                    FieldPart.Value,
                    FieldValue.Present(RawValue.BooleanValue(true)),
                ),
            )
        return document.copy(auxiliary = document.auxiliary.copy(resources = resources))
    }

    private fun chooseUi(
        document: StudioDocument,
        command: StudioMetadataCommand.UiChoice,
    ): StudioDocument? {
        val field = uiField(document, command.address) ?: return null
        val before = StudioFieldValues.value(field, command.address.part)
        return editUi(document, StudioMetadataCommand.UiEdit(command.address, before, command.after))
    }

    private fun editUi(
        document: StudioDocument,
        command: StudioMetadataCommand.UiEdit,
    ): StudioDocument? {
        val field = uiField(document, command.address) ?: return null
        val current = StudioFieldValues.value(field, command.address.part)
        if (current == command.after) return document
        if (current != command.before) return null
        val replacement = StudioFieldEdits.edit(field, command.address.part, command.after)
        return document.copy(auxiliary = document.auxiliary.copy(ui = replace(document.auxiliary.ui, replacement)))
    }

    private fun uiField(
        document: StudioDocument,
        address: FieldAddress,
    ): StudioField? {
        val field =
            document.auxiliary.ui.records
                .singleOrNull { it.key == address.field } ?: return null
        val definition =
            StudioFieldCatalog.fields.singleOrNull {
                it.source == field.key.source && it.name == field.key.name
            } ?: return null
        if (definition.owner != StudioFieldOwner.UI) return null
        if (address.part != FieldPart.Value && definition.type != LegacyValueType.STRING_MAP) return null
        return field
    }

    private fun installFont(
        document: StudioDocument,
        command: StudioMetadataCommand.FontInstalled,
    ): StudioDocument? {
        val family = command.familyName
        if (family.isBlank() || command.job.action == FontAction.UNINSTALL) return null
        var resources = document.auxiliary.resources
        resources = setMembership(resources, "installedFonts", family, shouldInclude = true)
        resources = setMembership(resources, "explicitlyUninstalledFonts", family, shouldInclude = false)
        val paths = resources.records.single { it.key.name == "installedFontFiles" }
        resources =
            replace(
                resources,
                StudioFieldEdits.edit(
                    paths,
                    FieldPart.MapEntry(family),
                    FieldValue.Present(RawValue.Text(command.encodedPaths)),
                ),
            )
        return document.copy(auxiliary = document.auxiliary.copy(resources = resources))
    }

    private fun removeFont(
        document: StudioDocument,
        command: StudioMetadataCommand.FontRemoved,
    ): StudioDocument? {
        val family = command.familyName
        if (family.isBlank() || command.job.action != FontAction.UNINSTALL) return null
        var resources = document.auxiliary.resources
        resources = setMembership(resources, "installedFonts", family, shouldInclude = false)
        resources = setMembership(resources, "explicitlyUninstalledFonts", family, shouldInclude = true)
        val paths = resources.records.single { it.key.name == "installedFontFiles" }
        resources = replace(resources, StudioFieldEdits.edit(paths, FieldPart.MapEntry(family), FieldValue.Absent))
        return document.copy(auxiliary = document.auxiliary.copy(resources = resources))
    }

    private fun setMembership(
        fields: StudioFields,
        name: String,
        member: String,
        shouldInclude: Boolean,
    ): StudioFields {
        val field = fields.records.single { it.key.name == name }
        val previous = (field.value as RawValue.OrderedSet).values
        val values =
            when {
                shouldInclude && member !in previous -> previous + member
                !shouldInclude -> previous.filterNot { it == member }
                else -> previous
            }
        return replace(
            fields,
            StudioFieldEdits.edit(field, FieldPart.Value, FieldValue.Present(RawValue.OrderedSet(StudioList(values)))),
        )
    }

    private fun recordEntitlement(
        document: StudioDocument,
        command: StudioMetadataCommand.Entitlement,
    ): StudioDocument {
        val fields = document.runtime.legacy.records
        val stamp = fields.single { it.key.name == "lastKnownLicensedMs" }
        val result = resolveEntitlement(command.rawLicense, (stamp.value as RawValue.LongValue).value, command.nowMs)
        if (result.entitlement == LicenseEntitlement.UNKNOWN) return document
        val confirmed = fields.single { it.key.name == "lastConfirmedEntitlement" }
        val changes =
            listOf(
                change(stamp, RawValue.LongValue(result.lastLicensedMs)),
                change(confirmed, RawValue.Text(result.entitlement.name)),
            )
        val accepted = document.copy(runtime = StudioRuntimeFields.merge(document.runtime, changes))
        if (confirmed.value != RawValue.Text(LicenseEntitlement.UNLICENSED.name) ||
            result.entitlement != LicenseEntitlement.LICENSED
        ) {
            return accepted
        }
        val marker =
            accepted.auxiliary.ui.records
                .single { it.key.name == "premiumOnboardingShown" }
        val rearmed = StudioFieldEdits.edit(marker, FieldPart.Value, FieldValue.Present(RawValue.BooleanValue(false)))
        return accepted.copy(auxiliary = accepted.auxiliary.copy(ui = replace(accepted.auxiliary.ui, rearmed)))
    }

    private fun initializeProject(
        document: StudioDocument,
        command: StudioMetadataCommand.InitializeProject,
    ): StudioDocument? {
        if (command.key.value.isBlank()) return null
        if (document.currents.projects.any { it.key == command.key }) return document
        val accepted = StudioTargets.current(document, command.sourceTarget) ?: return null
        return StudioProjectState.initialize(document, command.key, command.displayName, accepted)
    }

    private fun change(
        field: StudioField,
        after: RawValue,
    ) = RuntimeFieldChange(
        FieldAddress(field.key),
        FieldValue.Present(field.value),
        FieldValue.Present(after),
    )

    private fun replace(
        fields: StudioFields,
        replacement: StudioField,
    ): StudioFields =
        fields.copy(
            records = StudioList(fields.records.map { if (it.key == replacement.key) replacement else it }),
        )
}
