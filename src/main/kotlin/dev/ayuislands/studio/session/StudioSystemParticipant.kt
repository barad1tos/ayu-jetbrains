package dev.ayuislands.studio.session

import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.application.EDT
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAppearanceProvider
import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Applies admitted native theme choices without changing stored appearance preferences. */
internal class StudioSystemParticipant(
    private val host: StudioThemeHost = StudioIdeThemeHost(),
    private val appearance: () -> Appearance? = SystemAppearanceProvider::resolve,
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioEffectParticipant {
    override val owner: EffectOwner = EffectOwner.SYSTEM

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val change = input.themeChange ?: return@withContext EmptyThemeOperation
            val before = host.observe()
            val previous = before.theme ?: return@withContext EmptyThemeOperation
            val target =
                when (change) {
                    is StudioThemeChange.Explicit ->
                        requireNotNull(host.installed().singleOrNull { it.name == change.name }) {
                            "Explicit native theme is missing or ambiguous: ${change.name}"
                        }
                    StudioThemeChange.FollowSystem ->
                        followedTheme(input, previous)
                            ?: return@withContext EmptyThemeOperation
                }
            val process = checkNotNull(runtime.native.currentProcess) { "Theme process identity is unconfirmed" }
            val selection =
                AyuVariant.fromThemeName(target.name)?.let {
                    StudioSchemeSelection.prepare(host, input, process, it, nativeContext)
                } ?: EmptyThemeOperation
            val theme =
                if (previous === target) {
                    EmptyThemeOperation
                } else {
                    val receipt =
                        StudioThemeReceipts.capture(
                            input.candidate.header.transactionId,
                            process,
                            previous,
                            target,
                        )
                    ThemeOperation(host, before, target, receipt, nativeContext)
                }
            if (theme === EmptyThemeOperation &&
                selection === EmptyThemeOperation
            ) {
                return@withContext EmptyThemeOperation
            }
            SystemOperation(theme, selection) {
                withContext(nativeContext) {
                    runtime.confirmTheme(StudioThemeTransition(before, host.observe()))
                }
            }
        }

    private fun followedTheme(
        input: StudioEffectInput,
        previous: UIThemeLookAndFeelInfo,
    ): UIThemeLookAndFeelInfo? {
        val fields = input.candidate.shared.system.records
        if (fields.single { it.key.name == "followSystemAppearance" }.value != RawValue.BooleanValue(true)) return null
        if (AyuVariant.fromThemeName(previous.name) == null) return null
        val systemAppearance = appearance() ?: return null
        val field =
            when (systemAppearance) {
                Appearance.DARK -> "lastDarkAppearanceTheme"
                Appearance.LIGHT -> "lastLightAppearanceTheme"
            }
        val name = fields.single { it.key.name == field }.value
        if (name == RawValue.Null) return null
        val targetName = requireNotNull(name as? RawValue.Text).value
        return host.installed().singleOrNull { it.name == targetName }
    }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation {
        require(receipts.all { it.identity.ownerToken == input.candidate.header.transactionId })
        val (selection, theme) = receipts.partition { it.identity.resource == StudioSelectionReceipts.RESOURCE }
        return SystemOperation(
            RecoveredThemeOperation(host, StudioList(theme), runtime.native.currentProcess, nativeContext),
            RecoveredSchemeSelection(host, StudioList(selection), runtime.native.currentProcess, nativeContext),
            null,
        )
    }
}

private object EmptyThemeOperation : StudioEffectOperation {
    override val receipts = StudioList<PortableReceipt>(emptyList())
    override val runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

    override suspend fun apply() = Unit

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>) = StudioRestoreContinuation.NONE

    override suspend fun accept() = Unit
}

private class ThemeOperation(
    private val host: StudioThemeHost,
    private val before: StudioThemeSnapshot,
    private val target: UIThemeLookAndFeelInfo,
    private var receipt: PortableReceipt,
    private val nativeContext: CoroutineContext,
) : StudioEffectOperation {
    private var applied: StudioThemeSnapshot? = null
    private var restoring: StudioThemeSnapshot? = null
    private var isAccepted = false
    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(listOf(receipt))
    override val runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

    override suspend fun apply() =
        withContext(nativeContext) {
            check(!isAccepted && receipt.status == RestoreStatus.PENDING && applied == null)
            val observed = host.observe()
            check(matchesTheme(observed, before) && observed.scheme === before.scheme) {
                "Native appearance changed after theme preparation"
            }
            try {
                host.switchTo(target)
            } finally {
                val actual = host.observe()
                if (actual.theme === target) {
                    applied = actual
                    receipt = receipt.copy(status = RestoreStatus.OWNED)
                }
            }
            val written = checkNotNull(applied) { "Native theme switch did not reach its captured target" }
            check(written.scheme === before.scheme) { "Native theme switch replaced the captured editor scheme" }
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            check(!isAccepted) { "Accepted theme selection cannot be rolled back" }
            if (StudioNativeEvidence.isTerminal(receipt.status)) return@withContext StudioRestoreContinuation.NONE
            if (StudioThemeReceipts.blockedBy(blockedBy)) {
                receipt = receipt.copy(status = RestoreStatus.UNAVAILABLE)
                return@withContext StudioRestoreContinuation.NONE
            }
            val current = host.observe()
            val written = restoring ?: applied
            if (written == null) {
                receipt = receipt.copy(status = unwrittenStatus(current))
            } else if (!matchesTheme(current, written)) {
                receipt = receipt.copy(status = RestoreStatus.FOREIGN)
            } else {
                try {
                    host.switchTo(requireNotNull(before.theme), before.lookAndFeel)
                } finally {
                    val actual = host.observe()
                    restoring = actual.takeIf { it.theme === before.theme }
                }
                val restored = checkNotNull(restoring) { "Native theme restoration did not settle" }
                check(restored.scheme === current.scheme) { "Theme restoration replaced the current editor scheme" }
                receipt = receipt.copy(status = RestoreStatus.RESTORED)
            }
            StudioRestoreContinuation.NONE
        }

    override suspend fun accept() {
        isAccepted = true
    }

    private fun unwrittenStatus(current: StudioThemeSnapshot): RestoreStatus =
        when {
            matchesTheme(current, before) -> RestoreStatus.RESTORED
            current.theme === target -> RestoreStatus.UNAVAILABLE
            else -> RestoreStatus.FOREIGN
        }
}

private class RecoveredThemeOperation(
    private val host: StudioThemeHost,
    captured: StudioList<PortableReceipt>,
    private val currentProcess: StudioProcess?,
    private val nativeContext: CoroutineContext,
) : StudioEffectOperation {
    override var receipts = captured
        private set
    override val runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

    init {
        require(captured.size <= 1) { "Repeated native theme receipt" }
        captured.forEach(StudioThemeReceipts::decode)
    }

    override suspend fun apply(): Unit = error("Recovered theme selections cannot replay application")

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            val receipt = receipts.singleOrNull() ?: return@withContext StudioRestoreContinuation.NONE
            if (StudioNativeEvidence.isTerminal(receipt.status)) return@withContext StudioRestoreContinuation.NONE
            val evidence = StudioThemeReceipts.decode(receipt)
            val status =
                if (StudioThemeReceipts.blockedBy(blockedBy) ||
                    StudioProcessPolicy.compare(evidence.process, currentProcess) != StudioProcessRelation.DIFFERENT
                ) {
                    RestoreStatus.UNAVAILABLE
                } else {
                    restoreSelection(evidence)
                }
            receipts = StudioList(listOf(receipt.copy(status = status)))
            StudioRestoreContinuation.NONE
        }

    /** The outer committed journal authorizes cleanup; accepted selection is never rolled back here. */
    override suspend fun accept() = Unit

    private fun restoreSelection(evidence: StudioThemeEvidence): RestoreStatus {
        val current = host.observe()
        val theme = current.theme ?: return RestoreStatus.FOREIGN
        if (theme.id != evidence.beforeId && theme.id != evidence.plannedId) return RestoreStatus.FOREIGN
        val installed = host.installed()
        val before = installed.singleOrNull { it.id == evidence.beforeId } ?: return RestoreStatus.UNAVAILABLE
        val planned = installed.singleOrNull { it.id == evidence.plannedId } ?: return RestoreStatus.UNAVAILABLE
        if (theme !== before && theme !== planned) return RestoreStatus.FOREIGN
        // A matching baseline may come from a restore whose refresh failed before its durable acknowledgement.
        host.switchTo(before)
        val restored = host.observe()
        check(restored.scheme === current.scheme) { "Theme recovery replaced the current editor scheme" }
        return if (restored.theme === before) RestoreStatus.RESTORED else RestoreStatus.UNAVAILABLE
    }
}

private fun matchesTheme(
    current: StudioThemeSnapshot,
    expected: StudioThemeSnapshot,
): Boolean = current.theme === expected.theme && current.lookAndFeel === expected.lookAndFeel

/** Theme and editor selection share one lease handoff and settle their independent native cells in reverse order. */
private class SystemOperation(
    private val theme: StudioEffectOperation,
    private val selection: StudioEffectOperation,
    private val confirm: (suspend () -> Unit)?,
) : StudioEffectOperation {
    private var phase = Phase.PREPARED
    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(theme.receipts + selection.receipts)
    override val runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

    override suspend fun apply() {
        check(phase == Phase.PREPARED && confirm != null) { "System selection cannot replay application" }
        phase = Phase.APPLYING
        theme.apply()
        selection.apply()
        confirm.invoke()
        phase = Phase.APPLIED
    }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) {
        selection.prepareRestore(blockedBy)
        theme.prepareRestore(blockedBy)
    }

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation {
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted system selection cannot be rolled back" }
        phase = Phase.RESTORING
        val selectionFailure = runCatchingPreservingCancellation { selection.restore(blockedBy) }.exceptionOrNull()
        val themeFailure = runCatchingPreservingCancellation { theme.restore(blockedBy) }.exceptionOrNull()
        val failure = themeFailure?.let { combineUiFailures(selectionFailure, it) } ?: selectionFailure
        if (failure != null) throw failure
        phase = Phase.RESTORED
        return StudioRestoreContinuation.NONE
    }

    override suspend fun accept() {
        if (phase == Phase.ACCEPTED) return
        check(confirm == null || phase == Phase.APPLIED || phase == Phase.ACCEPTING) {
            "System selection application has not completed"
        }
        phase = Phase.ACCEPTING
        theme.accept()
        selection.accept()
        phase = Phase.ACCEPTED
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTING, ACCEPTED }
}
