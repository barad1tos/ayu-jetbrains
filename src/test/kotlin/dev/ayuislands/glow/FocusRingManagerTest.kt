package dev.ayuislands.glow

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioGlowFocusAdd
import dev.ayuislands.studio.session.StudioGlowReferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.awt.Color
import java.awt.Component
import java.awt.Window
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyListener
import java.awt.image.BufferedImage
import java.time.Instant
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FocusRingManagerTest {
    @Test
    fun `held capture retains manager availability through apply and restoration`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.GRADIENT, 81)
            val border = field.border
            var isAvailable = true
            val hold = manager.holdCallbacks()
            try {
                val checkpoint = hold.capture({ isAvailable }) { actual -> actual.copy(color = Color.CYAN) }.single()
                assertSame(border, field.border)
                assertEquals(GlowStyle.GRADIENT, checkpoint.planned.style)
                assertEquals(81, checkpoint.planned.intensity)
                isAvailable = false
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertSame(border, field.border)
                isAvailable = true
                checkpoint.apply()
                val applied = field.border
                isAvailable = false
                assertFailsWith<IllegalStateException> { checkpoint.prepareRestore() }
                assertSame(applied, field.border)
                isAvailable = true
                checkpoint.prepareRestore()
                checkpoint.restore()
                assertSame(border, field.border)
            } finally {
                hold.release()
            }
        }

    @Test
    fun `released held capture cannot write through a later hold with unchanged native border`() =
        withFocusWindows { manager, windows ->
            val field = FocusField()
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val border = field.border
            val hold = manager.holdCallbacks()
            val checkpoint = hold.capture({ true }) { actual -> actual.copy(color = Color.CYAN) }.single()
            hold.release()
            val replacement = manager.holdCallbacks()
            try {
                assertSame(border, field.border)
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertFailsWith<IllegalStateException> { hold.capture({ true }) { it } }
                val observed = replacement.capture({ true }) { actual -> actual.copy(color = Color.GREEN) }.single()
                assertSame(Color.ORANGE, observed.before.color)
                assertSame(border, field.border)
            } finally {
                replacement.release()
            }
        }

    @Test
    fun `held capture rejects unavailable and released empty census before invoking transform`() =
        withFocusWindows { manager, _ ->
            val hold = manager.holdCallbacks()
            try {
                assertFailsWith<IllegalStateException> { hold.capture({ false }) { error("Unexpected transform") } }
            } finally {
                hold.release()
            }
            assertFailsWith<IllegalStateException> { hold.capture({ true }) { error("Unexpected transform") } }
        }

    @Test
    fun `unchanged focus appearance creates no checkpoint or replacement border`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val border = field.border
            val listeners = listenerBaseline(field)
            var writes = 0
            field.beforeBorderWrite = { writes++ }

            val checkpoints = manager.capture { actual -> actual.copy() }

            assertTrue(checkpoints.isEmpty())
            assertSame(border, field.border)
            assertListenerBaseline(field, listeners)
            assertEquals(0, writes)
        }

    @Test
    fun `color capture preserves each focus listeners actual style and intensity`() =
        withFocusWindows { manager, windows ->
            val first = FocusField().apply { isFocusSimulated = true }
            val firstBase = first.border
            windows += focusWindow(JPanel().apply { add(first) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val firstRing = first.border
            val second = FocusField().apply { isFocusSimulated = true }
            val secondBase = second.border
            windows += focusWindow(JPanel().apply { add(second) })
            manager.initializeFocusRingGlow(Color.GREEN, GlowStyle.GRADIENT, 81)
            val secondRing = second.border

            val checkpoints = manager.capture { actual -> actual.copy(color = Color.CYAN) }

            assertEquals(2, checkpoints.size)
            assertSame(firstRing, first.border)
            assertSame(secondRing, second.border)
            checkpoints.forEach { it.apply() }
            assertEquals(
                paintBorder(first, GlowFocusBorder(firstBase, Color.CYAN, GlowStyle.SOFT, 30)),
                paintCurrentBorder(first),
            )
            assertEquals(
                paintBorder(second, GlowFocusBorder(secondBase, Color.CYAN, GlowStyle.GRADIENT, 81)),
                paintCurrentBorder(second),
            )
            checkpoints.forEach { it.prepareRestore() }
            checkpoints.forEach { it.restore() }
            assertSame(firstRing, first.border)
            assertSame(secondRing, second.border)
        }

    @Test
    fun `new membership preparation scans nested controls while excluding all captured owners`() =
        withFocusWindows { firstManager, windows ->
            val oldField = FocusField().apply { isFocusSimulated = true }
            val panel = JPanel().apply { add(oldField) }
            windows += focusWindow(panel)
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val oldBorder = oldField.border
            val oldListeners = listenerBaseline(oldField)
            val newField = FocusField().apply { isFocusSimulated = true }
            panel.add(JPanel().apply { add(newField) })
            panel.add(JButton())
            val newBaseline = listenerBaseline(newField)
            val newBorder = newField.border
            val secondManager = FocusRingManager()
            val firstHold = firstManager.holdCallbacks()
            val secondHold = secondManager.holdCallbacks()
            try {
                val planned = GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true)
                val attachments = secondHold.prepare(planned, firstHold.components + secondHold.components) { true }

                assertSame(newField, attachments.single().component)
                assertSame(newBorder, newField.border)
                assertSame(oldBorder, oldField.border)
                assertListenerBaseline(oldField, oldListeners)
                assertListenerBaseline(newField, newBaseline)
                assertTrue(secondHold.prepare(planned.copy(enabled = false), emptyList()) { true }.isEmpty())
                attachments.single().restore()
                firstHold.release()
                secondHold.release()
                assertSame(newBorder, newField.border)
                assertListenerBaseline(newField, newBaseline)
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `prepared membership retains exact initial border and listener identity`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()
            val references = StudioGlowReferences("attempt", StudioProcess(42, Instant.EPOCH))
            val addition = StudioGlowFocusAdd(references, attachment)
            val evidence = addition.receipts
            assertEquals(2, evidence.size)
            val borderReceipt = evidence.single { it.identity.field == "glow/focus/BORDER" }
            assertEquals(references.token(field), borderReceipt.identity.resource)
            val memberReceipt = evidence.single { it.identity.field == "glow/focus/membership" }
            assertEquals(references.token(attachment.listener), memberReceipt.identity.resource)
            val prepared = attachment.border.record
            assertSame(original, prepared.before)
            assertIs<GlowFocusBorder>(prepared.planned)
            assertTrue(prepared.isFocused)
            assertEquals(GlowFieldState.PREPARED, prepared.state)
            assertSame(original, field.border)
            assertListenerBaseline(field, baseline)

            addition.apply()
            assertSame(prepared.planned, field.border)
            assertSame(attachment.listener, addedFocusListener(field, baseline))
            assertSame(field, attachment.listener.component)
            assertEquals(GlowFieldState.WRITING, attachment.border.record.state)
            val newer = borderReceipt.copy(identity = borderReceipt.identity.copy(ownerToken = "newer"))
            assertFailsWith<IllegalStateException> { addition.restore(listOf(newer)) }
            assertSame(prepared.planned, field.border)
            addition.restore(emptyList())
            assertTrue(addition.receipts.all { it.status == RestoreStatus.RESTORED })
            for (receipt in evidence) {
                assertEquals(receipt, addition.receipts.single { it.id == receipt.id }.copy(status = receipt.status))
            }
            assertSame(original, field.border)
            assertEquals(GlowFieldState.RESTORED, attachment.border.record.state)
            hold.release()
        }

    @Test
    fun `prepared membership rejects focus drift before and during registration without painting`() {
        for (duringRegistration in listOf(false, true)) {
            withFocusWindows { manager, windows ->
                val field = FocusField().apply { isFocusSimulated = true }
                val original = field.border
                val baseline = listenerBaseline(field)
                windows += focusWindow(JPanel().apply { add(field) })
                val hold = manager.holdCallbacks()
                val attachment =
                    hold
                        .prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }
                        .single()
                var writes = 0
                field.beforeBorderWrite = { writes++ }
                if (duringRegistration) {
                    field.afterHierarchyAdd = { field.isFocusSimulated = false }
                } else {
                    field.isFocusSimulated = false
                }

                assertFailsWith<IllegalStateException> { attachment.apply() }
                assertSame(original, field.border)
                assertEquals(0, writes)
                attachment.restore()
                assertListenerBaseline(field, baseline)
                hold.release()
            }
        }
    }

    @Test
    fun `unfocused attachment preserves absent border without a native write`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { border = null }
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()
            assertSame(null, attachment.border.record.before)
            assertSame(null, attachment.border.record.planned)
            assertFalse(attachment.border.record.isFocused)
            field.beforeBorderWrite = { error("Unfocused input must not receive a border") }
            attachment.apply()
            attachment.restore()
            hold.release()
            assertSame(null, field.border)
            assertEquals(GlowFieldState.RESTORED, attachment.border.record.state)
        }

    @Test
    fun `attachment border restoration settles even when the setter notification fails`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()
            attachment.apply()
            var restores = 0
            field.addPropertyChangeListener("border") { change ->
                if (change.newValue === original) {
                    restores++
                    error("After native restore")
                }
            }

            assertFailsWith<IllegalStateException> { attachment.restore() }
            assertTrue(attachment.hasPending)
            assertSame(original, field.border)
            assertEquals(GlowFieldState.RESTORED, attachment.border.record.state)
            attachment.restore()
            assertEquals(1, restores)
            assertListenerBaseline(field, baseline)
            hold.release()
        }

    @Test
    fun `foreign attachment border stays terminal across interrupted listener cleanup`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()
            val references = StudioGlowReferences("attempt", StudioProcess(42, Instant.EPOCH))
            val addition = StudioGlowFocusAdd(references, attachment)
            addition.apply()
            val planned = attachment.border.record.planned
            field.border = EmptyBorder(1, 2, 3, 4)
            field.beforeFocusRemove = { error("Before native cleanup") }
            assertFailsWith<IllegalStateException> { addition.restore(emptyList()) }
            assertEquals(GlowFieldState.FOREIGN, attachment.border.record.state)
            val pending = addition.receipts.associateBy { it.identity.field }
            assertEquals(RestoreStatus.FOREIGN, pending.getValue("glow/focus/BORDER").status)
            assertEquals(RestoreStatus.PENDING, pending.getValue("glow/focus/membership").status)
            field.border = planned
            field.beforeFocusRemove = null
            field.beforeBorderWrite = { error("A terminal foreign border must not be reacquired") }
            addition.restore(emptyList())
            hold.release()
            assertSame(planned, field.border)
            val restored = addition.receipts.associateBy { it.identity.field }
            assertEquals(RestoreStatus.FOREIGN, restored.getValue("glow/focus/BORDER").status)
            assertEquals(RestoreStatus.RESTORED, restored.getValue("glow/focus/membership").status)
            assertListenerBaseline(field, baseline)
            assertEquals(GlowFieldState.FOREIGN, attachment.border.record.state)
        }

    @Test
    fun `filtered capture retains exact listener and component identities`() =
        withFocusWindows { manager, windows ->
            val first = FocusField()
            windows += focusWindow(JPanel().apply { add(first) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val second = FocusField()
            windows += focusWindow(JPanel().apply { add(second) })
            manager.initializeFocusRingGlow(Color.CYAN, GlowStyle.GRADIENT, 70)
            val listener = second.focusListeners.filterIsInstance<GlowFocusListener>().single()
            val hold = manager.holdCallbacks()
            val checkpoint =
                hold
                    .capture({ true }) { actual ->
                        if (actual.intensity == 70) actual.copy(intensity = 80) else actual
                    }.single()
            assertSame(listener, checkpoint.listener)
            assertSame(second, checkpoint.listener.component)
            checkpoint.restore()
            hold.release()
        }

    @Test
    fun `new focused membership paints during apply and reconciles actual focus on hold release`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()

            attachment.apply()
            assertEquals(GlowCheckpointPhase.APPLIED, attachment.phase)
            assertEquals(
                paintBorder(field, GlowFocusBorder(original, Color.CYAN, GlowStyle.SOFT, 70)),
                paintCurrentBorder(field),
            )
            field.isFocusSimulated = false
            addedFocusListener(field, baseline).focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            assertIs<GlowFocusBorder>(field.border)
            attachment.accept()
            attachment.accept()
            assertFalse(attachment.hasPending)
            assertEquals(GlowCheckpointPhase.ACCEPTED, attachment.phase)
            assertFailsWith<IllegalStateException> { attachment.restore() }
            hold.release()
            assertSame(original, field.border)
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
        }

    @Test
    fun `new membership keeps partial registration until cleanup succeeds`() =
        withFocusWindows { manager, windows ->
            val field = FocusField()
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()
            assertEquals(GlowCheckpointPhase.PREPARED, attachment.phase)
            field.afterHierarchyAdd = { error("After native registration") }

            assertFailsWith<IllegalStateException> { attachment.apply() }
            assertTrue(attachment.hasPending)
            assertEquals(GlowCheckpointPhase.APPLYING, attachment.phase)
            field.beforeFocusRemove = { error("Before native cleanup") }
            assertFailsWith<IllegalStateException> { attachment.restore() }
            assertTrue(attachment.hasPending)
            assertEquals(GlowCheckpointPhase.RESTORING, attachment.phase)
            field.beforeFocusRemove = null
            attachment.restore()
            assertFalse(attachment.hasPending)
            assertEquals(GlowCheckpointPhase.RESTORED, attachment.phase)
            assertListenerBaseline(field, baseline)
            hold.release()
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `new membership rollback preserves foreign border after throwing focused write`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()
            field.addPropertyChangeListener("border") { change ->
                if (change.newValue is GlowFocusBorder) error("After native border write")
            }

            assertFailsWith<IllegalStateException> { attachment.apply() }
            assertTrue(attachment.hasPending)
            assertSame(attachment.border.record.planned, field.border)
            assertEquals(GlowFieldState.WRITING, attachment.border.record.state)
            assertFailsWith<IllegalStateException> { attachment.restore(isBlocked = true) }
            val foreign = EmptyBorder(4, 3, 2, 1)
            field.border = foreign
            attachment.restore()
            hold.release()
            assertSame(foreign, field.border)
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `new membership rejects expired hold changed host border and owner before writing`() {
        for (change in listOf("hold", "host", "border", "owner")) {
            withFocusWindows { manager, windows ->
                val field = FocusField().apply { isFocusSimulated = true }
                val baseline = listenerBaseline(field)
                windows += focusWindow(JPanel().apply { add(field) })
                val hold = manager.holdCallbacks()
                var isAvailable = true
                val attachment =
                    hold
                        .prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { isAvailable }
                        .single()
                when (change) {
                    "hold" -> hold.release()
                    "host" -> JPanel().add(field)
                    "border" -> field.border = EmptyBorder(1, 2, 3, 4)
                    "owner" -> isAvailable = false
                }
                val expectedBorder = field.border
                assertFailsWith<IllegalStateException> { attachment.apply() }
                attachment.restore()
                assertSame(expectedBorder, field.border)
                assertListenerBaseline(field, baseline)
                hold.release()
            }
        }
    }

    @Test
    fun `new membership notices detachment during the native border notification`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val hold = manager.holdCallbacks()
            val attachment =
                hold.prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) { true }.single()
            field.addPropertyChangeListener("border") { change ->
                if (change.newValue is GlowFocusBorder) manager.removeFocusListeners()
            }

            assertFailsWith<IllegalStateException> { attachment.apply() }
            attachment.restore()
            hold.release()
            assertSame(original, field.border)
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `competing prepared memberships never install a second listener on the same input`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val secondManager = FocusRingManager()
            val firstHold = firstManager.holdCallbacks()
            val secondHold = secondManager.holdCallbacks()
            try {
                val planned = GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true)
                val first = firstHold.prepare(planned, emptyList()) { true }.single()
                val second = secondHold.prepare(planned, emptyList()) { true }.single()
                first.apply()
                val installed = field.border
                val listener = addedFocusListener(field, baseline)

                assertFailsWith<IllegalStateException> { second.apply() }
                second.restore()
                assertSame(installed, field.border)
                assertSame(listener, addedFocusListener(field, baseline))
                first.restore()
                firstHold.release()
                secondHold.release()
                assertListenerBaseline(field, baseline)
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `retired attachment cannot accept or remove a replacement owner`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            val secondManager = FocusRingManager()
            val firstHold = firstManager.holdCallbacks()
            val secondHold = secondManager.holdCallbacks()
            try {
                val planned = GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true)
                val first = firstHold.prepare(planned, emptyList()) { true }.single()
                val second = secondHold.prepare(planned, emptyList()) { true }.single()
                first.apply()
                firstManager.removeFocusListeners()
                second.apply()
                val installed = field.border
                val listener = addedFocusListener(field, baseline)

                assertFailsWith<IllegalStateException> { first.accept() }
                first.restore()
                firstHold.release()
                assertSame(installed, field.border)
                assertSame(listener, addedFocusListener(field, baseline))
                second.accept()
                secondHold.release()
                assertSame(installed, field.border)
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `focus restoration preparation observes the border without writing it`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val original = field.border
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            val applied = field.border
            var writes = 0
            field.beforeBorderWrite = { writes++ }

            checkpoint.prepareRestore()

            assertEquals(0, writes)
            assertSame(applied, field.border)
            val prepared = assertNotNull(checkpoint.record.restoration)
            assertSame(applied, prepared.observed)
            assertSame(original, prepared.border)
            checkpoint.restore()
            assertEquals(1, writes)
            assertSame(original, field.border)
        }

    @Test
    fun `focus change after restoration preparation requires fresh evidence before a setter`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            val applied = field.border
            checkpoint.prepareRestore()
            var writes = 0
            field.beforeBorderWrite = { writes++ }
            field.isFocusSimulated = false

            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertEquals(0, writes)
            assertSame(applied, field.border)
            assertTrue(checkpoint.hasPending)
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertEquals(1, writes)
            assertSame(baseline, field.border)
            assertFalse(checkpoint.hasPending)
        }

    @Test
    fun `foreign border after restoration preparation is preserved`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            checkpoint.prepareRestore()
            val foreign = EmptyBorder(7, 6, 5, 4)
            field.border = foreign
            field.beforeBorderWrite = { error("Foreign border must not be overwritten") }

            checkpoint.restore()

            assertSame(foreign, field.border)
            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
            field.beforeBorderWrite = null
        }

    @Test
    fun `focus restoration without preparation never calls a native setter`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val original = field.border
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            val applied = field.border
            var writes = 0
            field.beforeBorderWrite = { writes++ }

            assertFailsWith<IllegalStateException> { checkpoint.restore() }

            assertEquals(0, writes)
            assertSame(applied, field.border)
            assertTrue(checkpoint.hasPending)
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertSame(original, field.border)
            assertEquals(1, writes)
        }

    @Test
    fun `completed focus setter retries without another native write`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val original = field.border
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            checkpoint.prepareRestore()
            var writes = 0
            field.beforeBorderWrite = { writes++ }
            field.addPropertyChangeListener("border") { change ->
                if (change.newValue === original) error("Native notification failed after restoration")
            }

            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertSame(original, field.border)
            assertTrue(checkpoint.hasPending)
            checkpoint.restore()

            assertSame(original, field.border)
            assertEquals(1, writes)
            assertFalse(checkpoint.hasPending)
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.BORDER])
        }

    @Test
    fun `preparing a removal does not claim a later external removal`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            field.isFocusSimulated = false
            checkpoint.prepareRestore()
            assertSame(baseline, assertNotNull(checkpoint.record.restoration).border)
            field.border = baseline
            field.isFocusSimulated = true
            checkpoint.prepareRestore()
            field.beforeBorderWrite = { error("An unattempted restoration does not own the external border") }

            checkpoint.restore()

            assertSame(baseline, field.border)
            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
            field.beforeBorderWrite = null
        }

    @Test
    fun `focus records retain foreign provenance across a blocked retry`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val oldRing = field.border
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            val prepared = checkpoint.record
            assertSame(oldRing, prepared.beforeBorder)
            assertSame(original, prepared.baselineBorder)
            assertTrue(prepared.fields.values.all { it == GlowFieldState.PREPARED })
            assertFailsWith<UnsupportedOperationException> {
                (prepared.fields as MutableMap)[GlowFocusField.BORDER] = GlowFieldState.FOREIGN
            }
            checkpoint.apply()
            assertSame(prepared.plannedBorder, field.border)
            field.border = EmptyBorder(1, 2, 3, 4)

            checkpoint.prepareRestore()
            assertFailsWith<IllegalStateException> { checkpoint.restore(setOf(GlowFocusField.APPEARANCE)) }

            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
            assertEquals(GlowFieldState.WRITING, checkpoint.record.fields[GlowFocusField.APPEARANCE])
            field.border = prepared.plannedBorder
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertSame(prepared.plannedBorder, field.border)
            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.APPEARANCE])
            assertEquals(GlowCheckpointPhase.PREPARED, prepared.phase)
            assertTrue(prepared.fields.values.all { it == GlowFieldState.PREPARED })
        }

    @Test
    fun `focus records distinguish attempted null border restoration from no attempt`() =
        withFocusWindows { manager, windows ->
            val field =
                FocusField().apply {
                    border = null
                    isFocusSimulated = true
                }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            assertEquals(null, checkpoint.record.restoration)
            checkpoint.apply()
            field.isFocusSimulated = false
            field.addPropertyChangeListener("border") { change ->
                if (change.newValue == null) error("Removal notification failed")
            }

            checkpoint.prepareRestore()
            val prepared = assertNotNull(checkpoint.record.restoration)
            assertEquals(null, prepared.border)
            assertFalse(prepared.isFocused)
            assertSame(checkpoint.record.plannedBorder, prepared.observed)
            assertEquals(GlowFieldState.WRITING, prepared.outcome)
            assertFailsWith<IllegalStateException> { checkpoint.restore() }

            assertSame(prepared, checkpoint.record.restoration)
            assertEquals(GlowFieldState.WRITING, checkpoint.record.fields[GlowFocusField.BORDER])
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.BORDER])
            assertEquals(null, field.border)
        }

    @Test
    fun `captured managers can update their shared field without claiming another owners border`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows.add(focusWindow(JPanel().apply { add(field) }))
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val secondManager = FocusRingManager()
            try {
                secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val original = field.border
                val firstHold = firstManager.holdCallbacks()
                val secondHold = secondManager.holdCallbacks()
                try {
                    val planned = GlowFocusAppearance(Color.MAGENTA, GlowStyle.GRADIENT, 70, enabled = true)
                    val first = firstManager.capture { planned }.single()
                    val second = secondManager.capture { planned }.single()
                    second.apply()
                    val installed = field.border

                    first.apply()
                    assertSame(installed, field.border)
                    first.prepareRestore()
                    first.restore()
                    assertSame(installed, field.border)
                    second.prepareRestore()
                    second.restore()
                    assertSame(original, field.border)
                } finally {
                    secondHold.release()
                    firstHold.release()
                }
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `shared capture admits one border writer after focus gains during the hold`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { border = null }
            val baseline = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val secondManager = FocusRingManager()
            try {
                secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val firstHold = firstManager.holdCallbacks()
                val secondHold = secondManager.holdCallbacks()
                field.isFocusSimulated = true
                val checkpoints =
                    captureGlowFocus(listOf(firstHold, secondHold)) { _, actual -> actual.copy(color = Color.MAGENTA) }
                assertEquals(2, checkpoints.size)
                assertEquals(1, checkpoints.count { GlowFocusField.BORDER in it.record.fields })
                checkpoints.forEach { it.apply() }
                checkpoints.asReversed().forEach {
                    it.prepareRestore()
                    it.restore()
                }
                assertSame(baseline, field.border)
                var writes = 0
                field.beforeBorderWrite = { writes++ }
                firstHold.release()
                assertEquals(0, writes)
                secondHold.release()
                assertEquals(1, writes)
                field.beforeBorderWrite = null
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `shared disable prepares the complete owned border chain before acceptance`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val secondManager = FocusRingManager()
            try {
                secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val original = field.border
                val firstHold = firstManager.holdCallbacks()
                val secondHold = secondManager.holdCallbacks()
                val checkpoints =
                    captureGlowFocus(listOf(firstHold, secondHold)) { _, actual -> actual.copy(enabled = false) }
                assertSame(original, field.border)
                val border = checkpoints.single { GlowFocusField.BORDER in it.record.fields }
                assertSame(baseline, border.record.plannedBorder)
                checkpoints.forEach { it.apply() }
                assertSame(baseline, field.border)
                field.beforeBorderWrite = { error("Acceptance must not restore another border") }
                checkpoints.forEach { it.accept() }
                firstHold.release()
                secondHold.release()
                assertTrue(field.focusListeners.none { it is GlowFocusListener })
                assertSame(baseline, field.border)
                field.beforeBorderWrite = null
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `shared accepted detach preserves a foreign border even when it matches an old owned layer`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val earlierLayer = field.border
            val secondManager = FocusRingManager()
            try {
                secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val firstHold = firstManager.holdCallbacks()
                val secondHold = secondManager.holdCallbacks()
                val checkpoints =
                    captureGlowFocus(listOf(firstHold, secondHold)) { _, actual -> actual.copy(enabled = false) }
                checkpoints.forEach { it.apply() }
                field.border = earlierLayer
                field.beforeBorderWrite = { error("Foreign replacement must survive acceptance") }
                checkpoints.forEach { it.accept() }
                firstHold.release()
                secondHold.release()
                assertSame(earlierLayer, field.border)
                field.beforeBorderWrite = null
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `shared rollback retains a lower listeners original ownership after it becomes the writer`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val firstListener = field.focusListeners.filterIsInstance<GlowFocusListener>().single()
            val secondManager = FocusRingManager()
            try {
                secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val original = field.border
                val firstHold = firstManager.holdCallbacks()
                val secondHold = secondManager.holdCallbacks()
                val checkpoints =
                    captureGlowFocus(listOf(firstHold, secondHold)) { listener, actual ->
                        actual.copy(color = Color.MAGENTA, enabled = listener === firstListener)
                    }
                checkpoints.forEach { it.apply() }
                checkpoints.asReversed().forEach {
                    it.prepareRestore()
                    it.restore()
                }
                assertSame(original, field.border)
                secondHold.release()
                firstHold.release()
                val nextFirst = firstManager.holdCallbacks()
                val nextSecond = secondManager.holdCallbacks()
                val disabled =
                    captureGlowFocus(listOf(nextFirst, nextSecond)) { _, actual -> actual.copy(enabled = false) }
                disabled.forEach { it.apply() }
                assertSame(baseline, field.border)
                disabled.forEach { it.accept() }
                nextFirst.release()
                nextSecond.release()
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `shared capture preserves foreign borders and rejects stale holds before preparation`() =
        withFocusWindows { firstManager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val secondManager = FocusRingManager()
            try {
                secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val foreign = EmptyBorder(7, 6, 5, 4)
                field.border = foreign
                val firstHold = firstManager.holdCallbacks()
                val secondHold = secondManager.holdCallbacks()
                val checkpoints =
                    captureGlowFocus(listOf(firstHold, secondHold)) { _, actual -> actual.copy(enabled = false) }
                assertTrue(checkpoints.all { it.record.fields.keys == setOf(GlowFocusField.APPEARANCE) })
                field.beforeBorderWrite = { error("Foreign border must not be unwrapped") }
                checkpoints.forEach { it.apply() }
                checkpoints.forEach { it.accept() }
                firstHold.release()
                secondHold.release()
                assertSame(foreign, field.border)
                assertFailsWith<IllegalStateException> {
                    captureGlowFocus(listOf(firstHold, secondHold)) { _, actual -> actual }
                }
                field.beforeBorderWrite = null
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `foreign border changes do not block an appearance only checkpoint`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows.add(focusWindow(JPanel().apply { add(field) }))
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            field.border = EmptyBorder(1, 2, 3, 4)
            val hold = manager.holdCallbacks()
            try {
                val checkpoint =
                    manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.GRADIENT, 70, enabled = true) }.single()
                assertEquals(setOf(GlowFocusField.APPEARANCE), checkpoint.record.fields.keys)
                val foreign = EmptyBorder(4, 3, 2, 1)
                field.border = foreign

                checkpoint.apply()
                assertSame(foreign, field.border)
                checkpoint.prepareRestore()
                checkpoint.restore()
                assertSame(foreign, field.border)
            } finally {
                hold.release()
            }
        }

    @Test
    fun `disposed manager cannot resume focus borders before terminal cleanup`() =
        withFocusWindows { manager, windows ->
            val field = FocusField()
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val listener = addedFocusListener(field, baseline)
            var disposed = false
            val gate =
                GlowUpdateGate(
                    isDisposed = { disposed },
                    readInput = { loadGlowRuntimeInput(AyuIslandsState()) },
                    holdFocus = manager::holdCallbacks,
                )
            val hold = gate.hold()
            field.isFocusSimulated = true
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            disposed = true

            hold.release()

            assertSame(originalBorder, field.border)
            assertFalse(gate.isOpen)
            assertFailsWith<IllegalStateException> { gate.hold() }
            manager.dispose()
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `held callbacks reconcile actual focus without replaying old events`() =
        withFocusWindows { manager, windows ->
            val field = FocusField()
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val listener = addedFocusListener(field, baseline)
            val hold = manager.holdCallbacks()

            field.isFocusSimulated = true
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            assertSame(originalBorder, field.border)
            field.isFocusSimulated = false
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            field.isFocusSimulated = true
            hold.release()

            assertEquals(
                paintBorder(field, GlowFocusBorder(originalBorder, Color.ORANGE, GlowStyle.SOFT, 30)),
                paintCurrentBorder(field),
            )
            val releasedBorder = field.border
            hold.release()
            assertSame(releasedBorder, field.border)
            field.isFocusSimulated = false
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            assertSame(originalBorder, field.border)
        }

    @Test
    fun `held focus callbacks allow checkpoint writes and preserve foreign borders on release`() {
        for (foreignReplacement in listOf(false, true)) {
            withFocusWindows { manager, windows ->
                val field = FocusField().apply { isFocusSimulated = true }
                val originalBorder = field.border
                val baseline = listenerBaseline(field)
                windows += focusWindow(JPanel().apply { add(field) })
                manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
                val listener = addedFocusListener(field, baseline)
                val hold = manager.holdCallbacks()
                val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
                checkpoint.apply()
                val plannedBorder = field.border
                field.isFocusSimulated = false
                listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
                assertSame(plannedBorder, field.border)
                val foreign = EmptyBorder(7, 6, 5, 4)
                if (foreignReplacement) field.border = foreign
                checkpoint.prepareRestore()
                checkpoint.restore()
                field.isFocusSimulated = true
                listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))

                hold.release()

                if (foreignReplacement) {
                    assertSame(foreign, field.border)
                } else {
                    assertEquals(
                        paintBorder(field, GlowFocusBorder(originalBorder, Color.ORANGE, GlowStyle.SOFT, 30)),
                        paintCurrentBorder(field),
                    )
                }
            }
        }
    }

    @Test
    fun `failed focus release retains admission and explicit detach never resurrects listeners`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val listener = addedFocusListener(field, baseline)
            val oldRing = field.border
            val hold = manager.holdCallbacks()
            field.isFocusSimulated = false
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            assertSame(oldRing, field.border)
            field.beforeBorderWrite = { error("Native border rejected the write") }

            assertFailsWith<IllegalStateException> { hold.release() }

            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            assertSame(oldRing, field.border)
            field.beforeBorderWrite = null
            manager.removeFocusListeners()
            hold.release()
            assertSame(originalBorder, field.border)
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `text fields, combo boxes, and named search fields are eligible`() =
        onFocusEdt {
            val manager = FocusRingManager()
            assertTrue(manager.isTextInputComponent(JTextField()))
            assertTrue(manager.isTextInputComponent(JComboBox<String>()))
            assertTrue(manager.isTextInputComponent(SearchTextFieldFixture()))
        }

    @Test
    fun `checkpoint restyles the retained listener and restores the exact installed border`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val oldRing = field.border
            val listeners = field.focusListeners.toList()
            val hierarchy = field.hierarchyListeners.toList()
            val checkpoint =
                manager
                    .capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SHARP_NEON, 100, true) }
                    .single()
            assertSame(oldRing, field.border)

            checkpoint.apply()

            assertTrue(checkpoint.hasPending)
            assertEquals(
                paintBorder(field, GlowFocusBorder(originalBorder, Color.CYAN, GlowStyle.SHARP_NEON, 100)),
                paintCurrentBorder(field),
            )
            assertEquals(listeners, field.focusListeners.toList())
            assertEquals(hierarchy, field.hierarchyListeners.toList())

            checkpoint.prepareRestore()
            checkpoint.restore()

            assertSame(oldRing, field.border)
            assertFalse(checkpoint.hasPending)
            assertEquals(listeners, field.focusListeners.toList())
            assertEquals(hierarchy, field.hierarchyListeners.toList())
            manager.removeFocusListeners()
            assertSame(originalBorder, field.border)
        }

    @Test
    fun `checkpoint disable retains listeners until acceptance and rollback restores focus appearance`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val oldRing = field.border
            val listener = addedFocusListener(field, baseline)
            val planned = GlowFocusAppearance(Color.CYAN, GlowStyle.SHARP_NEON, 100, false)
            val checkpoint = manager.capture { planned }.single()

            checkpoint.apply()

            assertSame(originalBorder, field.border)
            assertSame(listener, addedFocusListener(field, baseline))
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            assertSame(originalBorder, field.border)
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertSame(oldRing, field.border)

            val accepted = manager.capture { planned }.single()
            accepted.apply()
            accepted.accept()
            assertListenerBaseline(field, baseline)
            assertSame(originalBorder, field.border)
            assertFailsWith<IllegalStateException> { accepted.restore() }
        }

    @Test
    fun `checkpoint rollback keeps foreign border and follows actual focus`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val listener = addedFocusListener(field, baseline)
            val planned = GlowFocusAppearance(Color.CYAN, GlowStyle.SHARP_NEON, 100, true)
            val checkpoint = manager.capture { planned }.single()
            checkpoint.apply()
            field.isFocusSimulated = false

            checkpoint.prepareRestore()
            checkpoint.restore()

            assertSame(originalBorder, field.border)
            field.isFocusSimulated = true
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            assertEquals(
                paintBorder(field, GlowFocusBorder(originalBorder, Color.ORANGE, GlowStyle.SOFT, 30)),
                paintCurrentBorder(field),
            )
            val foreign = EmptyBorder(7, 6, 5, 4)
            val next = manager.capture { planned }.single()
            next.apply()
            field.border = foreign
            next.prepareRestore()
            next.restore()
            assertSame(foreign, field.border)
            manager.removeFocusListeners()
            assertSame(foreign, field.border)
        }

    @Test
    fun `focus checkpoint retains failed writes before and after native mutation`() {
        for (failAfterMutation in listOf(false, true)) {
            withFocusWindows { manager, windows ->
                val field = FocusField().apply { isFocusSimulated = true }
                windows += focusWindow(JPanel().apply { add(field) })
                manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
                val oldRing = field.border
                val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
                var shouldFail = true
                if (failAfterMutation) {
                    field.addPropertyChangeListener("border") {
                        if (shouldFail) {
                            shouldFail = false
                            error("Native border changed before notification failed")
                        }
                    }
                } else {
                    field.beforeBorderWrite = {
                        field.beforeBorderWrite = null
                        error("Native border rejected the write")
                    }
                }

                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertTrue(checkpoint.hasPending)
                checkpoint.prepareRestore()
                checkpoint.restore()

                assertSame(oldRing, field.border)
                assertFalse(checkpoint.hasPending)
            }
        }
    }

    @Test
    fun `focus restoration retries a completed setter and respects a later loss of focus`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val oldRing = field.border
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            var restoreWrites = 0
            field.addPropertyChangeListener("border") { change ->
                if (change.newValue === oldRing) {
                    restoreWrites++
                    error("Restored ring before notification failed")
                }
            }

            checkpoint.prepareRestore()
            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertSame(oldRing, field.border)
            assertTrue(checkpoint.hasPending)
            field.isFocusSimulated = false
            checkpoint.prepareRestore()
            checkpoint.restore()

            assertSame(original, field.border)
            assertEquals(1, restoreWrites)
            assertFalse(checkpoint.hasPending)
        }

    @Test
    fun `newer focus receipt blocks even an equal baseline border`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val oldRing = field.border
            val checkpoint = manager.capture { GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true) }.single()
            checkpoint.apply()
            field.border = oldRing

            checkpoint.prepareRestore()
            assertFailsWith<IllegalStateException> { checkpoint.restore(setOf(GlowFocusField.BORDER)) }

            assertSame(oldRing, field.border)
            assertTrue(checkpoint.hasPending)
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertFalse(checkpoint.hasPending)
        }

    @Test
    fun `focus checkpoint rejects foreign preapply changes and never resurrects detached listeners`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val planned = GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true)
            val before = manager.capture { planned }.single()
            val foreign = EmptyBorder(4, 3, 2, 1)
            field.border = foreign
            assertFailsWith<IllegalStateException> { before.apply() }
            before.restore()
            assertSame(foreign, field.border)

            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30, true)
            val checkpoint = manager.capture { planned }.single()
            checkpoint.apply()
            manager.removeFocusListeners()

            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertTrue(checkpoint.hasPending)
            assertSame(foreign, field.border)
            assertNotEquals(original, field.border)
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `panels, labels, and buttons are ineligible`() =
        onFocusEdt {
            val manager = FocusRingManager()
            assertFalse(manager.isTextInputComponent(JPanel()))
            assertFalse(manager.isTextInputComponent(JLabel()))
            assertFalse(manager.isTextInputComponent(JButton()))
        }

    @Test
    fun `initialization attaches one listener pair only to nested eligible controls`() =
        withFocusWindows { manager, windows ->
            val field = JTextField()
            val combo = JComboBox<String>()
            val search = SearchTextFieldFixture()
            val button = JButton()
            val panel = JPanel().apply { add(JPanel().apply { add(field) }) }
            panel.add(combo)
            panel.add(search)
            panel.add(button)
            val baselines = listOf(field, combo, search, button).associateWith(::listenerBaseline)
            windows += focusWindow(panel)

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)

            listOf(field, combo, search).forEach { component ->
                assertEquals(baselines.getValue(component).focusCount + 1, component.focusListeners.size)
                assertEquals(baselines.getValue(component).hierarchyCount + 1, component.hierarchyListeners.size)
            }
            assertEquals(baselines.getValue(button).focusCount, button.focusListeners.size)
            assertEquals(baselines.getValue(button).hierarchyCount, button.hierarchyListeners.size)
        }

    @Test
    fun `repeated initialization neither duplicates listeners nor wraps an existing ring`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val installedBorder = assertIs<GlowFocusBorder>(field.border)
            manager.initializeFocusRingGlow(Color.RED, GlowStyle.SHARP_NEON, 90)

            assertSame(installedBorder, field.border)
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
            assertEquals(baseline.hierarchyCount + 1, field.hierarchyListeners.size)
            manager.removeFocusListeners()
            assertSame(originalBorder, field.border)
        }

    @Test
    fun `later window discovery installs new controls without changing processed controls`() =
        withFocusWindows { manager, windows ->
            val first = JTextField()
            val second = JTextField()
            val firstBaseline = listenerBaseline(first)
            val secondBaseline = listenerBaseline(second)
            windows += focusWindow(JPanel().apply { add(first) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val firstOwnedListener = addedFocusListener(first, firstBaseline)
            windows += focusWindow(JPanel().apply { add(second) })

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)

            assertSame(firstOwnedListener, addedFocusListener(first, firstBaseline))
            assertEquals(firstBaseline.focusCount + 1, first.focusListeners.size)
            assertEquals(secondBaseline.focusCount + 1, second.focusListeners.size)
        }

    @Test
    fun `focus events preserve the original border and ignore duplicate gains`() =
        withFocusWindows { manager, windows ->
            val originalBorder = EmptyBorder(2, 3, 4, 5)
            val field = JTextField().apply { border = originalBorder }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val listener = addedFocusListener(field, baseline)

            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            val ring = assertIs<GlowFocusBorder>(field.border)
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            assertSame(ring, field.border)
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))

            assertSame(originalBorder, field.border)
        }

    @Test
    fun `enabled update changes focused output while retaining listener and external border`() =
        withFocusWindows { manager, windows ->
            val originalBorder = EmptyBorder(5, 4, 3, 2)
            val field =
                FocusField().apply {
                    isFocusSimulated = true
                    border = originalBorder
                }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val firstPixels = paintCurrentBorder(field)
            val replacement = EmptyBorder(5, 4, 3, 2)
            field.border = replacement

            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 100, enabled = true)

            assertIs<GlowFocusBorder>(field.border)
            val updatedPixels = paintCurrentBorder(field)
            val expectedPixels = paintBorder(field, GlowFocusBorder(replacement, Color.CYAN, GlowStyle.SHARP_NEON, 100))
            val wrongColor = paintBorder(field, GlowFocusBorder(replacement, Color.ORANGE, GlowStyle.SHARP_NEON, 100))
            val wrongStyle = paintBorder(field, GlowFocusBorder(replacement, Color.CYAN, GlowStyle.SOFT, 100))
            val wrongIntensity = paintBorder(field, GlowFocusBorder(replacement, Color.CYAN, GlowStyle.SHARP_NEON, 30))
            assertNotEquals(firstPixels, updatedPixels)
            assertEquals(expectedPixels, updatedPixels)
            assertNotEquals(wrongColor, updatedPixels)
            assertNotEquals(wrongStyle, updatedPixels)
            assertNotEquals(wrongIntensity, updatedPixels)
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
            assertEquals(baseline.hierarchyCount + 1, field.hierarchyListeners.size)
            manager.removeFocusListeners()
            assertSame(replacement, field.border)
        }

    @Test
    fun `focus lifecycle preserves nested component order`() =
        withFocusWindows { manager, windows ->
            val nested =
                JPanel().apply {
                    add(JTextField())
                    add(JButton())
                    add(JComboBox<String>())
                }
            val root =
                JPanel().apply {
                    add(JLabel("Before"))
                    add(nested)
                    add(JLabel("After"))
                }
            val rootOrder = root.components.toList()
            val nestedOrder = nested.components.toList()
            windows += focusWindow(root)

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 80, enabled = true)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 80, enabled = false)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 80, enabled = true)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.removeFocusListeners()
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.dispose()
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
        }

    @Test
    fun `disable and reenable restore exact state and reproduce focused output`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val initialPixels = paintCurrentBorder(field)

            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = false)
            assertSame(originalBorder, field.border)
            assertListenerBaseline(field, baseline)
            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = true)

            assertEquals(initialPixels, paintCurrentBorder(field))
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
            assertEquals(baseline.hierarchyCount + 1, field.hierarchyListeners.size)
        }

    @Test
    fun `repeated cleanup preserves third party state and permits initialization again`() =
        withFocusWindows { manager, windows ->
            val externalListener =
                object : FocusListener {
                    override fun focusGained(event: FocusEvent) = Unit

                    override fun focusLost(event: FocusEvent) = Unit
                }

            val externalHierarchyListener = HierarchyListener { }
            val field =
                FocusField().apply {
                    isFocusSimulated = true
                    addFocusListener(externalListener)
                    addHierarchyListener(externalHierarchyListener)
                }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)

            manager.removeFocusListeners()
            manager.removeFocusListeners()
            manager.dispose()
            manager.dispose()

            assertSame(originalBorder, field.border)
            assertListenerBaseline(field, baseline)
            assertTrue(field.focusListeners.contains(externalListener))
            assertTrue(field.hierarchyListeners.contains(externalHierarchyListener))
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            assertIs<GlowFocusBorder>(field.border)
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
        }

    @Test
    fun `cleanup retries a border setter that failed before restoring the original`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val ring = assertIs<GlowFocusBorder>(field.border)
            field.beforeBorderWrite = {
                field.beforeBorderWrite = null
                error("Border restore failed before mutation")
            }

            assertFailsWith<IllegalStateException> { manager.removeFocusListeners() }
            assertSame(ring, field.border)
            manager.removeFocusListeners()

            assertSame(original, field.border)
            assertListenerBaseline(field, baseline)
            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = true)
            assertIs<GlowFocusBorder>(field.border)
            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = false)
            assertSame(original, field.border)
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `focus loss retries restoration of an originally absent border`() =
        withFocusWindows { manager, windows ->
            val field =
                FocusField().apply {
                    isFocusSimulated = true
                    border = null
                }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val listener = addedFocusListener(field, baseline)
            field.beforeBorderWrite = {
                field.beforeBorderWrite = null
                error("Focus loss failed before restoration")
            }

            assertFailsWith<IllegalStateException> {
                listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            }
            assertIs<GlowFocusBorder>(field.border)
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))

            assertSame(null, field.border)
            manager.removeFocusListeners()
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `cleanup retry preserves a foreign border installed after failed restoration`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            field.beforeBorderWrite = {
                field.beforeBorderWrite = null
                error("Border restore failed before mutation")
            }

            assertFailsWith<IllegalStateException> { manager.removeFocusListeners() }
            val foreign = EmptyBorder(7, 8, 9, 10)
            field.border = foreign
            manager.removeFocusListeners()
            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 30, enabled = true)
            manager.removeFocusListeners()

            assertSame(foreign, field.border)
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `cleanup settles a mutation followed by a property listener failure without replay`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val original = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            var restores = 0
            field.addPropertyChangeListener("border") { change ->
                if (change.newValue === original) {
                    restores++
                    error("Border was restored before notification failed")
                }
            }

            assertFailsWith<IllegalStateException> { manager.removeFocusListeners() }
            assertSame(original, field.border)
            manager.removeFocusListeners()

            assertEquals(1, restores)
            assertSame(original, field.border)
            assertListenerBaseline(field, baseline)
        }
}

private data class ListenerBaseline(
    val focusListeners: List<FocusListener>,
    val hierarchyListeners: List<HierarchyListener>,
) {
    val focusCount: Int = focusListeners.size
    val hierarchyCount: Int = hierarchyListeners.size
}

private class SearchTextFieldFixture : JPanel()

private class FocusField : JTextField() {
    var isFocusSimulated = false

    var beforeBorderWrite: (() -> Unit)? = null
    var afterHierarchyAdd: (() -> Unit)? = null
    var beforeFocusRemove: (() -> Unit)? = null

    override fun hasFocus(): Boolean = isFocusSimulated

    override fun setBorder(border: Border?) {
        beforeBorderWrite?.invoke()
        super.setBorder(border)
    }

    override fun addHierarchyListener(listener: HierarchyListener) {
        super.addHierarchyListener(listener)
        afterHierarchyAdd?.invoke()
    }

    override fun removeFocusListener(listener: FocusListener) {
        beforeFocusRemove?.invoke()
        super.removeFocusListener(listener)
    }
}

private fun withFocusWindows(test: (FocusRingManager, MutableList<Window>) -> Unit) {
    onFocusEdt {
        val windows = mutableListOf<Window>()
        mockkStatic(Window::class)
        try {
            every { Window.getWindows() } answers { windows.toTypedArray() }
            val manager = FocusRingManager()
            try {
                test(manager, windows)
            } finally {
                manager.dispose()
            }
        } finally {
            unmockkStatic(Window::class)
        }
    }
}

private fun focusWindow(content: JPanel): Window = mockk { every { components } returns arrayOf(content) }

private fun listenerBaseline(component: JComponent): ListenerBaseline =
    ListenerBaseline(component.focusListeners.toList(), component.hierarchyListeners.toList())

private fun addedFocusListener(
    field: JTextField,
    baseline: ListenerBaseline,
): FocusListener = field.focusListeners.single { it !in baseline.focusListeners }

private fun assertListenerBaseline(
    field: JTextField,
    baseline: ListenerBaseline,
) {
    assertEquals(baseline.focusListeners, field.focusListeners.toList())
    assertEquals(baseline.hierarchyListeners, field.hierarchyListeners.toList())
}

private fun assertComponentOrder(
    root: JPanel,
    rootOrder: List<Component>,
    nested: JPanel,
    nestedOrder: List<Component>,
) {
    assertEquals(rootOrder.size, root.componentCount)
    assertEquals(nestedOrder.size, nested.componentCount)
    root.components.forEachIndexed { index, component -> assertSame(rootOrder[index], component) }
    nested.components.forEachIndexed { index, component -> assertSame(nestedOrder[index], component) }
}

private fun paintCurrentBorder(field: JTextField): List<Int> = paintBorder(field, field.border)

private fun paintBorder(
    field: JTextField,
    border: Border,
): List<Int> {
    val image = BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        border.paintBorder(field, graphics, 0, 0, image.width, image.height)
    } finally {
        graphics.dispose()
    }
    return image.getRGB(0, 0, image.width, image.height, null, 0, image.width).toList()
}

private fun onFocusEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}
