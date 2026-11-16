package dev.ayuislands.studio

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
import dev.ayuislands.studio.session.StudioWorkspaceReceipts
import dev.ayuislands.studio.session.WorkspaceNativeField
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioWorkspaceReceiptsTest {
    private val origin = StudioProcess(41, Instant.ofEpochSecond(100))
    private val restarted = StudioProcess(42, Instant.ofEpochSecond(200))

    @Test
    fun `only proven process loss allows the approved width exception`() {
        val receipt = receipt(WorkspaceNativeField.WIDTH_BOUNDS)
        for (current in listOf(origin, null, StudioProcess(origin.pid, null))) {
            assertEquals(
                RestoreStatus.UNAVAILABLE,
                StudioWorkspaceReceipts.recoverLost(listOf(receipt), current).single().status,
            )
        }
        val retained = StudioWorkspaceReceipts.recoverLost(listOf(receipt), restarted).single()
        assertEquals(RestoreStatus.KEPT_AFTER_RESTART, retained.status)
        assertFalse(StudioReceiptPolicy.isTerminal(retained.status))
        assertTrue(StudioReceiptPolicy.isTerminal(retained))
        assertEquals(receipt, retained.copy(status = receipt.status))
    }

    @Test
    fun `process loss retires only ephemeral fields and never persistent values`() {
        val ephemeral = receipt(WorkspaceNativeField.EDITOR_SIZE)
        assertEquals(
            RestoreStatus.RESTORED,
            StudioWorkspaceReceipts.recoverLost(listOf(ephemeral), restarted).single().status,
        )
        for (field in listOf(WorkspaceNativeField.ROOT_PATH, WorkspaceNativeField.GIT_PROPERTY)) {
            val pending = StudioWorkspaceReceipts.recoverLost(listOf(receipt(field)), restarted).single()
            assertEquals(RestoreStatus.UNAVAILABLE, pending.status)
            assertFalse(StudioReceiptPolicy.isTerminal(pending.status))
        }
    }

    @Test
    fun `unknown fields and uncertain provenance cannot be relabeled as completed recovery`() {
        val receipt = receipt(WorkspaceNativeField.WIDTH_FIRST_SIZE)
        val unknown = receipt.copy(identity = receipt.identity.copy(field = "future-native-field"))
        assertEquals(
            RestoreStatus.UNAVAILABLE,
            StudioWorkspaceReceipts.recoverLost(listOf(unknown), restarted).single().status,
        )
        val different = receipt(WorkspaceNativeField.WIDTH_FIRST_SIZE, restarted)
        assertFailsWith<IllegalArgumentException> {
            StudioWorkspaceReceipts.recoverLost(listOf(receipt.copy(lastWritten = different.lastWritten)), restarted)
        }
        assertFailsWith<IllegalArgumentException> {
            StudioWorkspaceReceipts.recoverLost(listOf(receipt.copy(owner = EffectOwner.GLOW)), restarted)
        }
    }

    @Test
    fun `restart classification covers every native workspace field explicitly`() {
        val ephemeral =
            setOf(
                WorkspaceNativeField.EDITOR_SIZE,
                WorkspaceNativeField.EDITOR_BASELINE,
                WorkspaceNativeField.PROJECT_SCROLLBAR_POLICY,
                WorkspaceNativeField.PROJECT_SCROLLBAR_BASELINE,
                WorkspaceNativeField.PROJECT_RENDERER,
                WorkspaceNativeField.COMMIT_RENDERER,
                WorkspaceNativeField.WIDTH_LISTENER_ATTACHMENT,
                WorkspaceNativeField.WIDTH_LISTENER_ENABLED,
                WorkspaceNativeField.GIT_PROPORTION,
                WorkspaceNativeField.GIT_LISTENER,
            )
        val geometry =
            setOf(
                WorkspaceNativeField.WIDTH_FIRST_SIZE,
                WorkspaceNativeField.WIDTH_LAST_SIZE,
                WorkspaceNativeField.WIDTH_BOUNDS,
            )
        val persistent = setOf(WorkspaceNativeField.ROOT_PATH, WorkspaceNativeField.GIT_PROPERTY)
        val classifiedFields =
            buildSet {
                addAll(ephemeral)
                addAll(geometry)
                addAll(persistent)
            }
        assertEquals(WorkspaceNativeField.entries.toSet(), classifiedFields)
        val expected =
            ephemeral.associateWith { RestoreStatus.RESTORED } +
                geometry.associateWith { RestoreStatus.KEPT_AFTER_RESTART } +
                persistent.associateWith { RestoreStatus.UNAVAILABLE }
        for ((field, status) in expected) {
            assertEquals(status, StudioWorkspaceReceipts.recoverLost(listOf(receipt(field)), restarted).single().status)
        }
    }

    @Test
    fun `native overlap ignores operation ownership but respects resource field and settlement`() {
        val original = receipt(WorkspaceNativeField.WIDTH_BOUNDS)
        val newer = original.copy(identity = original.identity.copy(ownerToken = "workspace/newer/0"))
        val native = original.identity
        for (status in RestoreStatus.entries) {
            val blocker = newer.copy(status = status)
            assertEquals(
                !StudioReceiptPolicy.isTerminal(blocker),
                StudioWorkspaceReceipts.isBlocked(native.resource, native.field, listOf(blocker)),
            )
        }
        assertFalse(StudioWorkspaceReceipts.isBlocked("other-window", native.field, listOf(newer)))
        assertFalse(StudioWorkspaceReceipts.isBlocked(native.resource, "other-field", listOf(newer)))
    }

    @Test
    fun `settled receipts remain byte exact on repeated recovery`() {
        for (status in listOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN, RestoreStatus.KEPT_AFTER_RESTART)) {
            val settled = receipt(WorkspaceNativeField.WIDTH_LAST_SIZE).copy(status = status)
            assertEquals(listOf(settled), StudioWorkspaceReceipts.recoverLost(listOf(settled), null))
        }
    }

    @Test
    fun `retained geometry status cannot settle persistent or unknown fields`() {
        val persistent = receipt(WorkspaceNativeField.ROOT_PATH).copy(status = RestoreStatus.KEPT_AFTER_RESTART)
        val unknown = persistent.copy(identity = persistent.identity.copy(field = "future-native-field"))
        for (invalid in listOf(persistent, unknown)) {
            assertFalse(StudioReceiptPolicy.isTerminal(invalid))
            assertTrue(
                StudioWorkspaceReceipts.isBlocked(invalid.identity.resource, invalid.identity.field, listOf(invalid)),
            )
            assertFailsWith<IllegalArgumentException> {
                StudioWorkspaceReceipts.recoverLost(listOf(invalid), restarted)
            }
        }
    }

    @Test
    fun `reused process id needs a different known start time to prove restart`() {
        val reused = StudioProcess(origin.pid, restarted.startedAt)
        val original = receipt(WorkspaceNativeField.WIDTH_LAST_SIZE)
        assertEquals(
            RestoreStatus.KEPT_AFTER_RESTART,
            StudioWorkspaceReceipts.recoverLost(listOf(original), reused).single().status,
        )
    }

    private fun receipt(
        field: WorkspaceNativeField,
        process: StudioProcess = origin,
    ) = StudioWorkspaceReceipts.create(
        NativeFieldIdentity("native-target", field.name, "workspace/operation/0"),
        process,
        RawValue.Integer(400),
        RawValue.Integer(300),
    )
}
