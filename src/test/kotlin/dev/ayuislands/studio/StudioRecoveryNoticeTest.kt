package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RecoveryNotice
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioEffectParticipant
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioWorkspaceReceipts
import dev.ayuislands.studio.session.WorkspaceNativeField
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioRecoveryNoticeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `approved retained geometry settles rollback with an explicit user notice`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val effects = fixture.effects.filterNot { it.owner == EffectOwner.WORKSPACE } + RetainedGeometry()
                val coordinator = StudioApplyCoordinator(fixture.store, fixture, effects, fixture.mutex)

                val result = assertIs<AcceptanceResult.NotAccepted>(coordinator.submit(fixture.allOwnersRequest()))

                assertEquals(StudioList(listOf(RecoveryNotice.PANEL_GEOMETRY_KEPT)), result.notices)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(fixture.initial.shared, fixture.document().shared)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    private class RetainedGeometry : StudioEffectParticipant {
        override val owner = EffectOwner.WORKSPACE

        override suspend fun prepare(
            input: StudioEffectInput,
            runtime: StudioEffectRuntime,
        ): StudioEffectOperation =
            operation(
                StudioList(
                    listOf(
                        StudioWorkspaceReceipts.create(
                            NativeFieldIdentity(
                                "tool-window",
                                WorkspaceNativeField.WIDTH_FIRST_SIZE.name,
                                "retained-geometry",
                            ),
                            StudioProcess(10, Instant.EPOCH),
                            RawValue.Integer(250),
                            RawValue.Integer(400),
                        ),
                    ),
                ),
            )

        override suspend fun recover(
            input: StudioEffectInput,
            receipts: StudioList<PortableReceipt>,
            runtime: StudioEffectRuntime,
        ): StudioEffectOperation = operation(receipts)

        private fun operation(initial: StudioList<PortableReceipt>): StudioEffectOperation =
            object : StudioEffectOperation {
                override var receipts = initial
                override val runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

                override suspend fun apply(): Unit = error("Interrupted native operation")

                override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

                override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation {
                    receipts = StudioWorkspaceReceipts.recoverLost(receipts, StudioProcess(11, Instant.EPOCH))
                    return StudioRestoreContinuation.NONE
                }

                override suspend fun accept(): Unit = error("Interrupted operation cannot accept")
            }
    }
}
