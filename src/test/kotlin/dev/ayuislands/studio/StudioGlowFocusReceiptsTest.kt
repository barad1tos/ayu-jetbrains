package dev.ayuislands.studio

import dev.ayuislands.glow.GlowBorderRestore
import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowFocusField
import dev.ayuislands.glow.GlowFocusRecord
import dev.ayuislands.glow.GlowInitialBorderRecord
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.GlowBorderTokens
import dev.ayuislands.studio.session.GlowFocusTokens
import dev.ayuislands.studio.session.GlowReceiptIdentity
import dev.ayuislands.studio.session.StudioGlowFocusReceipts
import java.awt.Color
import java.time.Instant
import javax.swing.border.EmptyBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioGlowFocusReceiptsTest {
    private val identity = GlowReceiptIdentity("glow/runtime/listener", "attempt", StudioProcess(42, Instant.EPOCH))
    private val component = "glow/runtime/component"
    private val originalBorder = EmptyBorder(1, 2, 3, 4)
    private val plannedBorder = EmptyBorder(2, 3, 4, 5)
    private val baseline = EmptyBorder(0, 0, 0, 0)
    private val appearance = GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true)
    private val tokens = GlowFocusTokens("color-a", "color-b", "border-a", "border-b", "baseline")

    @Test
    fun `initial border shares the existing component field and preserves terminal evidence`() {
        val captured = GlowInitialBorderRecord(originalBorder, plannedBorder, true, GlowFieldState.PREPARED)
        val receipt =
            assertNotNull(
                StudioGlowFocusReceipts.encodeInitial(
                    identity.copy(resource = component),
                    captured,
                    GlowBorderTokens("border-a", "border-b"),
                ),
            )
        val existing = StudioGlowFocusReceipts.encode(identity, component, record(), tokens).last()
        assertEquals(existing.identity, receipt.identity)
        assertEquals(RestoreStatus.PENDING, receipt.status)
        assertEquals(RawValue.Text("border-b"), value(receipt.lastWritten))
        val owned =
            StudioGlowFocusReceipts.refreshInitial(
                receipt,
                captured.copy(state = GlowFieldState.WRITING),
                GlowCheckpointPhase.APPLIED,
            )
        assertEquals(RestoreStatus.OWNED, owned.status)
        assertEquals(receipt, owned.copy(status = receipt.status))
        val foreign = owned.copy(status = RestoreStatus.FOREIGN)
        val settled =
            StudioGlowFocusReceipts.refreshInitial(
                foreign,
                captured.copy(state = GlowFieldState.RESTORED),
                GlowCheckpointPhase.RESTORED,
            )
        assertEquals(foreign, settled)
    }

    @Test
    fun `unchanged initial border has no field receipt including absent borders`() {
        for (unchanged in listOf(null, originalBorder)) {
            assertNull(
                StudioGlowFocusReceipts.encodeInitial(
                    identity.copy(resource = component),
                    GlowInitialBorderRecord(unchanged, unchanged, false, GlowFieldState.PREPARED),
                    GlowBorderTokens(null, null),
                ),
            )
        }
    }

    @Test
    fun `different listeners share only the component border identity across owners`() {
        val first = StudioGlowFocusReceipts.encode(identity, component, record(), tokens)
        val other = identity.copy(resource = "glow/runtime/other-listener", ownerToken = "newer")
        val second = StudioGlowFocusReceipts.encode(other, component, record(), tokens)

        assertEquals(identity.resource, first.first().identity.resource)
        assertEquals(component, first.last().identity.resource)
        assertEquals(component, second.last().identity.resource)
        assertEquals(
            setOf(GlowFocusField.BORDER),
            StudioGlowFocusReceipts.blockedFields(identity.resource, component, second),
        )
        assertTrue(
            StudioGlowFocusReceipts
                .blockedFields(
                    identity.resource,
                    component,
                    second.map { it.copy(status = RestoreStatus.FOREIGN) },
                ).isEmpty(),
        )
    }

    @Test
    fun `prepared restore classification remains pending until native border settlement`() {
        val snapshot = record().copy(phase = GlowCheckpointPhase.APPLIED)
        val originals = StudioGlowFocusReceipts.encode(identity, component, snapshot, tokens)
        for (outcome in listOf(GlowFieldState.WRITING, GlowFieldState.RESTORED, GlowFieldState.FOREIGN)) {
            val prepared = GlowBorderRestore(plannedBorder, originalBorder, true, outcome)
            val restore =
                StudioGlowFocusReceipts.encodeRestore(
                    identity.copy(resource = component),
                    0,
                    prepared,
                    GlowBorderTokens("border-b", "border-a"),
                )
            val refreshed = StudioGlowFocusReceipts.refresh(originals + restore, snapshot)
            assertEquals(RestoreStatus.PENDING, restore.status)
            assertEquals(RestoreStatus.PENDING, refreshed.last().status)
            assertEquals(RestoreStatus.OWNED, refreshed.first().status)
            assertEquals(originals.last().id + "/restore/0", restore.id)
        }
    }

    @Test
    fun `successive restore preparations retain earlier evidence and exact baseline destination`() {
        val snapshot = record().copy(phase = GlowCheckpointPhase.APPLIED)
        val originals = StudioGlowFocusReceipts.encode(identity, component, snapshot, tokens)
        val first =
            StudioGlowFocusReceipts.encodeRestore(
                identity.copy(resource = component),
                0,
                GlowBorderRestore(plannedBorder, originalBorder, true, GlowFieldState.WRITING),
                GlowBorderTokens("border-b", "border-a"),
            )
        val next =
            StudioGlowFocusReceipts.encodeRestore(
                identity.copy(resource = component),
                1,
                GlowBorderRestore(originalBorder, baseline, false, GlowFieldState.WRITING),
                GlowBorderTokens("border-a", "baseline"),
            )
        val history =
            buildList {
                addAll(originals)
                add(first)
                add(next)
            }
        val settled =
            snapshot.copy(
                phase = GlowCheckpointPhase.RESTORING,
                fields =
                    mapOf(
                        GlowFocusField.APPEARANCE to GlowFieldState.WRITING,
                        GlowFocusField.BORDER to GlowFieldState.RESTORED,
                    ),
            )
        val result = StudioGlowFocusReceipts.refresh(history, settled)

        assertEquals(RawValue.Text("border-a"), value(first.lastWritten))
        assertEquals(RawValue.Text("baseline"), value(next.lastWritten))
        assertEquals(RestoreStatus.PENDING, result.first().status)
        assertTrue(result.drop(1).all { it.status == RestoreStatus.RESTORED })
        history.zip(result).forEach { (before, after) -> assertEquals(before, after.copy(status = before.status)) }
    }

    @Test
    fun `null border is explicit evidence and foreign border stays terminal independently of appearance`() {
        val snapshot = record().copy(plannedBorder = null)
        val originals =
            StudioGlowFocusReceipts.encode(identity, component, snapshot, tokens.copy(plannedBorder = null))
        val restore =
            StudioGlowFocusReceipts.encodeRestore(
                identity.copy(resource = component),
                0,
                GlowBorderRestore(null, null, false, GlowFieldState.RESTORED),
                GlowBorderTokens(null, null),
            )
        assertEquals(RawValue.Null, value(originals.last().lastWritten))
        assertEquals(RawValue.Null, value(restore.lastWritten))
        val foreign =
            (originals + restore).map {
                if (it.identity.field == "glow/focus/BORDER") it.copy(status = RestoreStatus.FOREIGN) else it
            }
        val result =
            StudioGlowFocusReceipts.refresh(
                foreign,
                snapshot.copy(
                    phase = GlowCheckpointPhase.RESTORED,
                    fields = GlowFocusField.entries.associateWith { GlowFieldState.RESTORED },
                ),
            )
        assertEquals(RestoreStatus.RESTORED, result.first().status)
        assertTrue(result.drop(1).all { it.status == RestoreStatus.FOREIGN })
    }

    private fun record(): GlowFocusRecord =
        GlowFocusRecord(
            appearance,
            appearance.copy(color = Color.ORANGE),
            originalBorder,
            plannedBorder,
            baseline,
            GlowCheckpointPhase.PREPARED,
            GlowFocusField.entries.associateWith { GlowFieldState.WRITING },
            null,
        )

    private fun value(native: NativeValue): RawValue =
        ((native as NativeValue.Present).value as RawValue.OrderedMap).entries.single { it.key == "value" }.value
}
