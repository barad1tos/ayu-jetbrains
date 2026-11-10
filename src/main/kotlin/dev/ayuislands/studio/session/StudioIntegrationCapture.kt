package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.CgpAccess
import dev.ayuislands.accent.CgpAccessProvider
import dev.ayuislands.accent.CgpCheckpoint
import dev.ayuislands.accent.CgpPart
import dev.ayuislands.accent.CgpPreparation
import dev.ayuislands.accent.CgpViewport
import dev.ayuislands.accent.IntegrationReceipts
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.indent.CurrentIrPalette
import dev.ayuislands.indent.IndentPalette
import dev.ayuislands.indent.IrAccessProvider
import dev.ayuislands.indent.IrCheckpoint
import dev.ayuislands.indent.IrPalette
import dev.ayuislands.indent.IrPart
import dev.ayuislands.indent.IrPreparation
import dev.ayuislands.indent.ResolvedIrState
import dev.ayuislands.integration.captureFailure
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Captures the two optional Accent surfaces without enrolling a child legacy Apply scope. */
internal class StudioIntegrationCapture(
    private val cgp: CgpAccessProvider?,
    private val ir: IrAccessProvider?,
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) {
    suspend fun prepare(
        input: StudioEffectInput,
        context: AccentContext?,
        accentHex: String?,
        facts: StudioNativeFacts,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val process =
                checkNotNull(facts.currentProcess) { "Native integration process is unconfirmed" }
            val projection = IntegrationProjection(input)
            val state = projection.state
            prepareExplicitEnable(state, input.integrationSelection, facts.isLicensed)
            val identity =
                AccentReceiptIdentity("integrations", input.candidate.header.transactionId, process)
            val writes =
                buildList {
                    ir?.resolve()?.let { access ->
                        IrPreparation.migrate(state)
                        val requested = planIndent(state, context, accentHex, facts.isLicensed)
                        val plan = IrPreparation.capture(state, access.readPalette().palette, requested)
                        val records =
                            plan.after
                                ?.let {
                                    StudioIntegrationReceipts.capture(
                                        identity,
                                        IntegrationSurface.INDENT,
                                        irValues(plan.before),
                                        irValues(it),
                                    )
                                }.orEmpty()
                        add(IndentWrite(state, access, plan, records))
                    }
                    cgp?.resolve()?.let { access ->
                        CgpPreparation.migrate(state)
                        val requested =
                            if (
                                context != null &&
                                facts.isLicensed &&
                                CgpPreparation.isEnabled(state, context)
                            ) {
                                CgpPreparation.target(checkNotNull(accentHex))
                            } else {
                                null
                            }
                        val plan = CgpPreparation.capture(state, access.readViewport(), requested)
                        val records =
                            plan.after
                                ?.let {
                                    StudioIntegrationReceipts.capture(
                                        identity,
                                        IntegrationSurface.CODE_GLANCE,
                                        cgpValues(plan.before),
                                        cgpValues(it),
                                    )
                                }.orEmpty()
                        add(CodeGlanceWrite(state, access, plan, records))
                    }
                }
            IntegrationOperation(
                projection,
                nativeContext,
                writes,
                writes.flatMap { it.records },
                false,
            )
        }

    suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        facts: StudioNativeFacts,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            require(
                receipts.all {
                    it.owner == EffectOwner.ACCENT &&
                        it.identity.ownerToken == input.candidate.header.transactionId
                },
            )
            require(
                receipts.all { receipt ->
                    IntegrationSurface.entries.any { it.resource == receipt.identity.resource }
                },
            )
            val projection = IntegrationProjection(input)
            val state = projection.state
            val writes =
                buildList {
                    for (surface in IntegrationSurface.entries) {
                        val records = receipts.filter { it.identity.resource == surface.resource }
                        if (records.isEmpty()) continue
                        StudioIntegrationReceipts.values(records, surface, true)
                        StudioIntegrationReceipts.values(records, surface, false)
                        val origins = records.map { StudioNativeEvidence.readProcess(it.before) }.distinct()
                        require(origins.size == 1)
                        if (
                            StudioProcessPolicy.compare(origins.single(), facts.currentProcess) !=
                            StudioProcessRelation.DIFFERENT
                        ) {
                            continue
                        }
                        recoverWrite(surface, state, records)?.let { add(it) }
                    }
                }
            IntegrationOperation(projection, nativeContext, writes, receipts, true)
        }

    private fun planIndent(
        state: AyuIslandsState,
        context: AccentContext?,
        accentHex: String?,
        isLicensed: Boolean,
    ): IrPalette? {
        if (context == null || !isLicensed || !IrPreparation.isEnabled(state, context)) return null
        val palette =
            when (context) {
                is AccentContext.Ayu -> IndentPalette.forAccent(checkNotNull(accentHex), context.ayuVariant)
                AccentContext.External -> IndentPalette.forExternalAccent(checkNotNull(accentHex))
            }
        return IrPreparation.target(state, palette)
    }

    private fun recoverWrite(
        surface: IntegrationSurface,
        state: AyuIslandsState,
        records: List<PortableReceipt>,
    ): IntegrationWrite? =
        when (surface) {
            IntegrationSurface.INDENT -> ir?.resolve()?.let { IndentWrite(state, it, null, records) }
            IntegrationSurface.CODE_GLANCE -> cgp?.resolve()?.let { CodeGlanceWrite(state, it, null, records) }
        }

    private fun prepareExplicitEnable(
        state: AyuIslandsState,
        selection: StudioIntegrationSelection,
        isLicensed: Boolean,
    ) {
        if (!isLicensed) return
        if (selection.enablesCodeGlance) CgpPreparation.prepareExplicitEnable(state)
        if (selection.enablesIndentRainbow) IrPreparation.prepareExplicitEnable(state)
    }
}

/** One bounded compound; the coordinator alone acknowledges receipts and decides acceptance. */
private class IntegrationOperation(
    private val projection: IntegrationProjection,
    private val context: CoroutineContext,
    private val writes: List<IntegrationWrite>,
    records: List<PortableReceipt>,
    private val isRecovery: Boolean,
) : StudioEffectOperation {
    private var current = records.toList()
    private var phase = StudioAccentPhase.PREPARED
    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(current)

    override val runtimeChanges: StudioList<RuntimeFieldChange>
        get() =
            if (phase == StudioAccentPhase.APPLIED || phase == StudioAccentPhase.ACCEPTED) {
                projection.changes()
            } else {
                StudioList(emptyList())
            }

    override suspend fun apply() =
        withContext(context) {
            check(!isRecovery && phase == StudioAccentPhase.PREPARED) {
                "Integration recovery cannot replay Apply"
            }
            phase = StudioAccentPhase.APPLYING
            writes.forEach { execute(it, it::apply) }
            phase = StudioAccentPhase.APPLIED
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(context) {
            check(phase != StudioAccentPhase.ACCEPTED) { "Accepted integrations cannot roll back" }
            if (phase == StudioAccentPhase.RESTORED) return@withContext StudioRestoreContinuation.NONE
            phase = StudioAccentPhase.RESTORING
            var failure: Throwable? = null
            for (write in writes.asReversed()) {
                val error = captureFailure { execute(write) { write.restore(blockedBy) } }
                if (error != null) failure = combineUiFailures(failure, error)
            }
            val available = writes.map { it.surface.resource }.toSet()
            current =
                current.map {
                    if (it.identity.resource in available) it else StudioIntegrationReceipts.refresh(it, null)
                }
            if (current.all { StudioNativeEvidence.isTerminal(it.status) }) {
                phase = StudioAccentPhase.RESTORED
            }
            failure?.let { throw it }
            StudioRestoreContinuation.NONE
        }

    override suspend fun accept() =
        withContext(context) {
            check(
                isRecovery || phase == StudioAccentPhase.APPLIED || phase == StudioAccentPhase.ACCEPTED,
            )
            writes.forEach(IntegrationWrite::accept)
            phase = StudioAccentPhase.ACCEPTED
        }

    private fun execute(
        write: IntegrationWrite,
        action: () -> Unit,
    ) {
        val failure = captureFailure(action)
        val observation =
            captureFailure {
                current =
                    current.map { receipt ->
                        if (
                            receipt.identity.resource != write.surface.resource ||
                            StudioNativeEvidence.isTerminal(receipt.status)
                        ) {
                            receipt
                        } else {
                            val part =
                                write.surface.parts.single { write.surface.field(it) == receipt.identity.field }
                            StudioIntegrationReceipts.refresh(receipt, write.state(part))
                        }
                    }
            }
        val combined = observation?.let { combineUiFailures(failure, it) } ?: failure
        combined?.let { throw it }
    }
}

private sealed interface IntegrationWrite {
    val surface: IntegrationSurface
    val records: List<PortableReceipt>

    fun apply()

    fun restore(blockedBy: List<PortableReceipt>)

    fun state(part: String): AccentWriteState?

    fun accept()
}

private class CodeGlanceWrite(
    private val settings: AyuIslandsState,
    private val access: CgpAccess,
    private val plan: CgpPreparation?,
    override val records: List<PortableReceipt>,
) : IntegrationWrite {
    override val surface = IntegrationSurface.CODE_GLANCE
    private val checkpoint =
        IntegrationReceipts.prepare {
            CgpCheckpoint(settings, { true }, CgpAccess::isCurrent, {})
        }

    init {
        val before =
            plan?.before ?: cgpViewport(StudioIntegrationReceipts.values(records, surface, true))
        val expected =
            if (plan == null) {
                cgpViewport(StudioIntegrationReceipts.values(records, surface, false))
            } else {
                before
            }
        checkpoint.capture(access, before, expected)
        checkpoint.relinquish(
            records
                .filter { StudioNativeEvidence.isTerminal(it.status) }
                .mapTo(mutableSetOf()) { record ->
                    CgpPart.entries.single { surface.field(it.name) == record.identity.field }
                },
        )
        access.checkpoint = checkpoint
    }

    override fun apply() {
        val prepared = checkNotNull(plan)
        check(access.isCurrent()) { "CodeGlance Pro target changed before Apply" }
        access.checkpoint = checkpoint
        prepared.after?.let {
            access.writeViewport(it)
            check(access.readViewport() == it) { "CodeGlance Pro changed during Apply" }
        }
        prepared.update(settings)
        checkpoint.captureMetadata()
    }

    override fun restore(blockedBy: List<PortableReceipt>) {
        access.checkpoint = checkpoint
        checkpoint.blocked =
            StudioIntegrationReceipts
                .blocked(surface, blockedBy)
                .mapTo(mutableSetOf(), CgpPart::valueOf)
        checkpoint.restore()
    }

    override fun state(part: String): AccentWriteState? {
        val component = CgpPart.valueOf(part)
        return if (!checkpoint.isAvailable || component in checkpoint.blocked) {
            null
        } else {
            checkpoint.writeState(component)
        }
    }

    override fun accept() {
        checkpoint.commit()
        if (access.checkpoint === checkpoint) access.checkpoint = null
    }
}

private class IndentWrite(
    private val settings: AyuIslandsState,
    private val access: ResolvedIrState,
    private val plan: IrPreparation?,
    override val records: List<PortableReceipt>,
) : IntegrationWrite {
    override val surface = IntegrationSurface.INDENT
    private val checkpoint =
        IntegrationReceipts.prepare {
            IrCheckpoint(settings, { true }, ResolvedIrState::isCurrent, {})
        }

    init {
        val before = plan?.before ?: irPalette(StudioIntegrationReceipts.values(records, surface, true))
        val expected =
            if (plan == null) {
                irPalette(StudioIntegrationReceipts.values(records, surface, false))
            } else {
                before
            }
        plan?.after?.let(::nativePalette)
        checkpoint.capture(access, nativePalette(before), nativePalette(expected))
        checkpoint.relinquish(
            records
                .filter { StudioNativeEvidence.isTerminal(it.status) }
                .mapTo(mutableSetOf()) { record ->
                    IrPart.entries.single { surface.field(it.name) == record.identity.field }
                },
        )
        access.checkpoint = checkpoint
    }

    override fun apply() {
        val prepared = checkNotNull(plan)
        check(access.isCurrent()) { "Indent Rainbow target changed before Apply" }
        access.checkpoint = checkpoint
        prepared.after?.let {
            val native = nativePalette(it)
            access.writePalette(native.palette, native.typeValue)
            check(access.readPalette().palette == it) { "Indent Rainbow changed during Apply" }
        }
        prepared.update(settings)
        checkpoint.captureMetadata()
    }

    override fun restore(blockedBy: List<PortableReceipt>) {
        access.checkpoint = checkpoint
        checkpoint.blocked =
            StudioIntegrationReceipts.blocked(surface, blockedBy).mapTo(mutableSetOf(), IrPart::valueOf)
        checkpoint.restore()
    }

    override fun state(part: String): AccentWriteState? {
        val component = IrPart.valueOf(part)
        return if (!checkpoint.isAvailable || component in checkpoint.blocked) {
            null
        } else {
            checkpoint.writeState(component)
        }
    }

    override fun accept() {
        checkpoint.commit()
        if (access.checkpoint === checkpoint) access.checkpoint = null
    }

    private fun nativePalette(palette: IrPalette): CurrentIrPalette {
        checkNotNull(palette.palette) { "Native Indent Rainbow palette is unavailable" }
        val type =
            checkNotNull(access.paletteTypes[palette.type]) {
                "Native Indent Rainbow palette type is unavailable"
            }
        return CurrentIrPalette(palette, type)
    }
}

private fun cgpValues(viewport: CgpViewport): List<RawValue> =
    listOf(
        RawValue.Text(viewport.color),
        RawValue.Text(viewport.border),
        RawValue.Integer(viewport.thickness),
    )

private fun irValues(palette: IrPalette): List<RawValue> =
    listOf(
        RawValue.Text(palette.type),
        palette.palette?.let(RawValue::Text) ?: RawValue.Null,
        RawValue.Integer(palette.colorCount),
    )

private fun cgpViewport(values: List<RawValue>): CgpViewport =
    CgpViewport(
        requireNotNull(values[0] as? RawValue.Text).value,
        requireNotNull(values[1] as? RawValue.Text).value,
        requireNotNull(values[2] as? RawValue.Integer).value,
    )

private fun irPalette(values: List<RawValue>): IrPalette =
    IrPalette(
        requireNotNull(values[0] as? RawValue.Text).value,
        if (values[1] == RawValue.Null) null else requireNotNull(values[1] as? RawValue.Text).value,
        requireNotNull(values[2] as? RawValue.Integer).value,
    )
