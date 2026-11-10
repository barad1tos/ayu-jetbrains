package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.CgpPart
import dev.ayuislands.indent.IrPart
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

internal enum class IntegrationSurface(
    val resource: String,
    val parts: List<String>,
) {
    INDENT("indent-rainbow.indent-rainbow", IrPart.entries.map { it.name }),
    CODE_GLANCE("com.nasller.CodeGlancePro", CgpPart.entries.map { it.name }),
    ;

    fun field(part: String): String {
        require(part in parts)
        return "integration/$part"
    }
}

/** Persistent native scalars; process loss never settles these values as ephemeral effects. */
internal object StudioIntegrationReceipts {
    fun capture(
        identity: AccentReceiptIdentity,
        surface: IntegrationSurface,
        before: List<RawValue>,
        planned: List<RawValue>,
    ): List<PortableReceipt> {
        require(before.size == surface.parts.size && planned.size == before.size)
        return surface.parts.mapIndexed { index, part ->
            val field = NativeFieldIdentity(surface.resource, surface.field(part), identity.ownerToken)
            PortableReceipt(
                "${identity.ownerToken}/${surface.resource}/${field.field}",
                EffectOwner.ACCENT,
                field,
                StudioNativeEvidence.envelope(identity.process, before[index]),
                StudioNativeEvidence.envelope(identity.process, planned[index]),
                RestoreStatus.PENDING,
            )
        }
    }

    fun values(
        records: List<PortableReceipt>,
        surface: IntegrationSurface,
        before: Boolean,
    ): List<RawValue> {
        require(records.size == surface.parts.size)
        return surface.parts.map { part ->
            val receipt = records.single { it.identity.field == surface.field(part) }
            require(receipt.owner == EffectOwner.ACCENT && receipt.identity.resource == surface.resource)
            require(
                StudioNativeEvidence.readProcess(receipt.before) ==
                    StudioNativeEvidence.readProcess(receipt.lastWritten),
            )
            val native = if (before) receipt.before else receipt.lastWritten
            StudioNativeEvidence
                .fields(requireNotNull(native as? NativeValue.Present).value)
                .getValue("value")
        }
    }

    fun refresh(
        receipt: PortableReceipt,
        state: AccentWriteState?,
    ): PortableReceipt {
        if (StudioNativeEvidence.isTerminal(receipt.status)) return receipt
        val status =
            when (state) {
                null -> RestoreStatus.UNAVAILABLE
                AccentWriteState.PREPARED -> RestoreStatus.PENDING
                AccentWriteState.OWNED -> RestoreStatus.OWNED
                AccentWriteState.RELINQUISHED -> RestoreStatus.FOREIGN
                AccentWriteState.RESTORED -> RestoreStatus.RESTORED
            }
        return receipt.copy(status = status)
    }

    fun blocked(
        surface: IntegrationSurface,
        newer: List<PortableReceipt>,
    ): Set<String> =
        buildSet {
            for (receipt in newer) {
                if (
                    receipt.identity.resource != surface.resource ||
                    StudioNativeEvidence.isTerminal(receipt.status)
                ) {
                    continue
                }
                val part = surface.parts.singleOrNull { surface.field(it) == receipt.identity.field }
                if (part == null) addAll(surface.parts) else add(part)
            }
        }
}

/**
 * Only existing integration runtime fields are projected back; raw candidate records never
 * round-trip through a bean.
 */
internal class IntegrationProjection(
    input: StudioEffectInput,
) {
    private val runtime = input.candidate.runtime
    val state: AyuIslandsState = createState(input)

    private fun createState(input: StudioEffectInput): AyuIslandsState {
        val settings = StudioPluginsEditing.settings(input.candidate.shared.plugins)
        val raw =
            input.candidate.runtime.legacy.records
                .associate { it.key.name to it.value }
        return AyuIslandsState().apply {
            cgpIntegrationEnabled = settings.integrations.codeGlanceEnabled
            irIntegrationEnabled = settings.integrations.indentEnabled
            indentPresetName = settings.integrations.indentPreset
            indentCustomAlpha = settings.integrations.indentAlpha
            irErrorHighlightEnabled = settings.integrations.highlightErrors
            externalThemeEnhancementsEnabled = settings.external.enabled
            externalThemeCodeGlanceProEnabled = settings.external.features.codeGlance
            externalThemeIndentRainbowEnabled = settings.external.features.indentRainbow
            isCgpOwnershipMigrated = boolean(raw.getValue("isCgpOwnershipMigrated"))
            cgpOwnership = text(raw.getValue("cgpOwnership"))
            cgpBaseColor = text(raw.getValue("cgpBaseColor"))
            cgpBaseBorder = text(raw.getValue("cgpBaseBorder"))
            cgpBaseThickness = number(raw.getValue("cgpBaseThickness"))
            cgpAppliedColor = text(raw.getValue("cgpAppliedColor"))
            cgpAppliedBorder = text(raw.getValue("cgpAppliedBorder"))
            cgpAppliedThickness = number(raw.getValue("cgpAppliedThickness"))
            isIrOwnershipMigrated = boolean(raw.getValue("isIrOwnershipMigrated"))
            irOwnership = text(raw.getValue("irOwnership"))
            irBaseType = text(raw.getValue("irBaseType"))
            irBasePalette = text(raw.getValue("irBasePalette"))
            irBaseColorCount = number(raw.getValue("irBaseColorCount"))
            irAppliedType = text(raw.getValue("irAppliedType"))
            irAppliedPalette = text(raw.getValue("irAppliedPalette"))
            irAppliedColorCount = number(raw.getValue("irAppliedColorCount"))
        }
    }

    fun changes(): StudioList<RuntimeFieldChange> {
        val output = values(state)
        return StudioList(
            runtime.legacy.records.mapNotNull { field ->
                val after = output[field.key.name] ?: return@mapNotNull null
                if (after == field.value) {
                    null
                } else {
                    RuntimeFieldChange(
                        FieldAddress(field.key),
                        FieldValue.Present(field.value),
                        FieldValue.Present(after),
                    )
                }
            },
        )
    }

    private fun values(state: AyuIslandsState): Map<String, RawValue> =
        mapOf(
            "isCgpOwnershipMigrated" to RawValue.BooleanValue(state.isCgpOwnershipMigrated),
            "cgpOwnership" to rawText(state.cgpOwnership),
            "cgpBaseColor" to rawText(state.cgpBaseColor),
            "cgpBaseBorder" to rawText(state.cgpBaseBorder),
            "cgpBaseThickness" to RawValue.Integer(state.cgpBaseThickness),
            "cgpAppliedColor" to rawText(state.cgpAppliedColor),
            "cgpAppliedBorder" to rawText(state.cgpAppliedBorder),
            "cgpAppliedThickness" to RawValue.Integer(state.cgpAppliedThickness),
            "isIrOwnershipMigrated" to RawValue.BooleanValue(state.isIrOwnershipMigrated),
            "irOwnership" to rawText(state.irOwnership),
            "irBaseType" to rawText(state.irBaseType),
            "irBasePalette" to rawText(state.irBasePalette),
            "irBaseColorCount" to RawValue.Integer(state.irBaseColorCount),
            "irAppliedType" to rawText(state.irAppliedType),
            "irAppliedPalette" to rawText(state.irAppliedPalette),
            "irAppliedColorCount" to RawValue.Integer(state.irAppliedColorCount),
        )

    private fun rawText(value: String?): RawValue = value?.let(RawValue::Text) ?: RawValue.Null

    private fun text(value: RawValue): String? =
        if (value == RawValue.Null) null else requireNotNull(value as? RawValue.Text).value

    private fun number(value: RawValue): Int = requireNotNull(value as? RawValue.Integer).value

    private fun boolean(value: RawValue): Boolean = requireNotNull(value as? RawValue.BooleanValue).value
}
