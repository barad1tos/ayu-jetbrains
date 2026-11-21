package dev.ayuislands.glow

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.awt.Color
import java.awt.Component
import java.awt.Window
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FocusRingManagerTest {
    @Test
    fun `text fields, combo boxes, and named search fields are eligible`() =
        onFocusEdt {
            val manager = FocusRingManager()
            assertTrue(manager.isTextInputComponent(JTextField()))
            assertTrue(manager.isTextInputComponent(JComboBox<String>()))
            assertTrue(manager.isTextInputComponent(SearchTextFieldFixture()))
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
    fun `held focus changes leave borders intact until reconciliation`() =
        withFocusWindows { manager, windows ->
            val first = FocusField().apply { isFocusSimulated = true }
            val second = FocusField()
            val firstBorder = first.border
            val secondBorder = second.border
            val firstBaseline = listenerBaseline(first)
            val secondBaseline = listenerBaseline(second)
            val panel =
                JPanel().apply {
                    add(first)
                    add(second)
                }
            windows += focusWindow(panel)
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val firstRing = assertIs<GlowFocusBorder>(first.border)
            val firstListener = addedFocusListener(first, firstBaseline)
            val secondListener = addedFocusListener(second, secondBaseline)
            val hold = manager.holdCallbacks()

            first.isFocusSimulated = false
            second.isFocusSimulated = true
            firstListener.focusLost(FocusEvent(first, FocusEvent.FOCUS_LOST))
            secondListener.focusGained(FocusEvent(second, FocusEvent.FOCUS_GAINED))
            manager.updateFocusRingGlow(Color.RED, GlowStyle.SHARP_NEON, 100, enabled = false)

            assertSame(firstRing, first.border)
            assertSame(secondBorder, second.border)
            assertSame(firstListener, addedFocusListener(first, firstBaseline))
            hold.release()

            assertSame(firstBorder, first.border)
            assertIs<GlowFocusBorder>(second.border)
            secondListener.focusLost(FocusEvent(second, FocusEvent.FOCUS_LOST))
            assertSame(secondBorder, second.border)
        }

    @Test
    fun `settling a focus checkpoint reconciles the actual focus without replacing listeners`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val withoutRing = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val listener = addedFocusListener(field, baseline)
            assertIs<GlowFocusBorder>(field.border)
            val originalPixels = paintCurrentBorder(field)
            val hold = manager.holdCallbacks()

            val checkpoint = hold.captureAppearance { it.copy(enabled = false) }.single()
            assertTrue(hold.captureAppearance { it }.isEmpty())
            checkpoint.apply()
            field.isFocusSimulated = false
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertSame(withoutRing, field.border)

            field.isFocusSimulated = true
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            assertSame(withoutRing, field.border)
            hold.release()

            assertSame(listener, addedFocusListener(field, baseline))
            assertEquals(originalPixels, paintCurrentBorder(field))
            assertFailsWith<IllegalStateException> { hold.captureAppearance { it } }
        }

    @Test
    fun `release preserves the original border when another writer restores it during Apply`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val hold = manager.holdCallbacks()
            val checkpoint = hold.captureAppearance { it.copy(color = Color.CYAN) }.single()

            checkpoint.apply()
            field.border = originalBorder
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
            hold.release()

            assertSame(originalBorder, field.border)
        }

    @Test
    fun `an unfocused field draws the accepted appearance on its next focus gain`() =
        withFocusWindows { manager, windows ->
            val field = FocusField()
            val nativeBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val listener = addedFocusListener(field, baseline)
            val hold = manager.holdCallbacks()
            val checkpoint = hold.captureAppearance { it.copy(color = Color.CYAN) }.single()

            assertEquals(setOf(GlowFocusField.APPEARANCE), checkpoint.record.fields.keys)
            checkpoint.apply()
            checkpoint.accept()
            checkpoint.accept()
            hold.release()
            assertSame(nativeBorder, field.border)
            assertEquals(GlowCheckpointPhase.ACCEPTED, checkpoint.record.phase)

            field.isFocusSimulated = true
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            val expected = GlowFocusBorder(nativeBorder, Color.CYAN, GlowStyle.SOFT, 50)
            val previous = GlowFocusBorder(nativeBorder, Color.ORANGE, GlowStyle.SOFT, 50)
            val pixels = paintCurrentBorder(field)
            assertEquals(paintBorder(field, expected), pixels)
            assertNotEquals(paintBorder(field, previous), pixels)
        }

    @Test
    fun `a border already replaced before capture stays outside Apply and release`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val listener = addedFocusListener(field, baseline)
            val foreign = EmptyBorder(7, 6, 5, 4)
            field.border = foreign
            val hold = manager.holdCallbacks()
            val checkpoint = hold.captureAppearance { it.copy(color = Color.CYAN) }.single()

            assertEquals(setOf(GlowFocusField.APPEARANCE), checkpoint.record.fields.keys)
            checkpoint.apply()
            assertSame(foreign, field.border)
            checkpoint.prepareRestore()
            checkpoint.restore()
            hold.release()

            assertSame(foreign, field.border)
            assertSame(listener, addedFocusListener(field, baseline))
        }

    @Test
    fun `release does not wrap a glow border already owned by another manager`() =
        withFocusWindows { manager, windows ->
            val field = FocusField()
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            field.isFocusSimulated = true
            val otherManager = FocusRingManager()
            try {
                otherManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val foreign = assertIs<GlowFocusBorder>(field.border)

                manager.holdCallbacks().release()

                assertSame(foreign, field.border)
            } finally {
                otherManager.dispose()
            }
        }

    @Test
    fun `shared apply writes one border and retains independent appearances and listener order`() =
        withSharedField(focused = true) { field, first, second, baseline ->
            val original = field.border
            val listeners = field.focusListeners.toList()
            val checkpoints =
                captureGlowFocus(listOf(second, first)) { hold, actual ->
                    actual.copy(color = if (hold === first) Color.RED else Color.BLUE)
                }

            assertEquals(2, checkpoints.size)
            val writer = checkpoints.single { GlowFocusField.BORDER in it.record.fields }
            assertEquals(Color.CYAN, writer.record.before.color)
            assertEquals(Color.BLUE, writer.record.planned.color)
            assertSame(baseline, writer.record.baselineBorder)
            assertSame(original, field.border)
            var writes = 0
            field.addPropertyChangeListener("border") { writes++ }
            checkpoints.forEach { it.apply() }
            assertEquals(1, writes)
            assertSame(writer.record.plannedBorder, field.border)
            val expected = GlowFocusBorder(baseline, Color.BLUE, GlowStyle.SOFT, 60)
            assertEquals(paintBorder(field, expected), paintCurrentBorder(field))

            restoreSharedFocus(checkpoints)
            first.release()
            second.release()
            assertSame(original, field.border)
            assertEquals(listeners, field.focusListeners.toList())
        }

    @Test
    fun `disabling the visible writer transfers the border without a false appearance receipt`() =
        withSharedField(focused = true) { field, first, second, baseline ->
            val original = field.border
            val checkpoints =
                captureGlowFocus(listOf(first, second)) { hold, actual ->
                    if (hold === second) actual.copy(enabled = false) else actual
                }
            val writer = checkpoints.single { GlowFocusField.BORDER in it.record.fields }
            assertEquals(Color.ORANGE, writer.record.planned.color)
            assertEquals(setOf(GlowFocusField.BORDER), writer.record.fields.keys)

            checkpoints.forEach { it.apply() }
            val expected = GlowFocusBorder(baseline, Color.ORANGE, GlowStyle.SOFT, 50)
            assertEquals(paintBorder(field, expected), paintCurrentBorder(field))
            restoreSharedFocus(checkpoints)
            second.release()
            first.release()

            assertSame(original, field.border)
        }

    @Test
    fun `late focus reconciles through a single writer after all managers release their holds`() =
        withSharedField(focused = false) { field, first, second, baseline ->
            val checkpoints =
                captureGlowFocus(listOf(second, first)) { hold, actual ->
                    actual.copy(color = if (hold === first) Color.RED else Color.BLUE)
                }
            assertTrue(checkpoints.none { GlowFocusField.BORDER in it.record.fields })
            checkpoints.forEach { it.apply() }
            checkpoints.forEach { it.accept() }
            field.isFocusSimulated = true
            field.focusListeners.filterIsInstance<GlowFocusListener>().forEach {
                it.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            }
            var writes = 0
            field.addPropertyChangeListener("border") { writes++ }

            second.release()
            field.focusListeners.filterIsInstance<GlowFocusListener>().forEach {
                it.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            }
            assertSame(baseline, field.border)
            assertEquals(0, writes)
            first.release()

            assertEquals(1, writes)
            val expected = GlowFocusBorder(baseline, Color.BLUE, GlowStyle.SOFT, 60)
            assertEquals(paintBorder(field, expected), paintCurrentBorder(field))
        }

    @Test
    fun `unshared focus callbacks finish before shared holds are released`() =
        withFocusWindows { firstManager, windows ->
            val shared = FocusField()
            val other = FocusField()
            val root = JPanel().apply { add(shared) }
            windows += focusWindow(root)
            firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val secondManager = FocusRingManager()
            try {
                root.add(other)
                secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
                val first = firstManager.holdCallbacks()
                val second = secondManager.holdCallbacks()
                val checkpoints =
                    captureGlowFocus(listOf(first, second)) { hold, actual ->
                        if (hold === first) actual.copy(color = Color.RED) else actual
                    }
                checkpoints.forEach { it.apply() }
                checkpoints.forEach { it.accept() }
                shared.isFocusSimulated = true
                other.isFocusSimulated = true
                var callbacks = 0
                other.addPropertyChangeListener("border") {
                    callbacks++
                    shared.focusListeners.filterIsInstance<GlowFocusListener>().forEach { listener ->
                        listener.focusGained(FocusEvent(shared, FocusEvent.FOCUS_GAINED))
                    }
                }
                var writes = 0
                shared.addPropertyChangeListener("border") { writes++ }

                first.release()
                second.release()

                assertEquals(1, callbacks)
                assertIs<GlowFocusBorder>(other.border)
                assertEquals(1, writes)
            } finally {
                secondManager.dispose()
            }
        }

    @Test
    fun `late focus still reconciles when an overlapping manager closes during Apply`() =
        withSharedManagers(focused = false) { field, firstManager, secondManager, baseline ->
            val first = firstManager.holdCallbacks()
            val second = secondManager.holdCallbacks()
            val checkpoints =
                captureGlowFocus(listOf(first, second)) { hold, actual ->
                    actual.copy(color = if (hold === first) Color.RED else Color.BLUE)
                }
            checkpoints.forEach { it.apply() }
            checkpoints.forEach { it.accept() }
            field.isFocusSimulated = true
            field.focusListeners.filterIsInstance<GlowFocusListener>().forEach {
                it.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            }
            firstManager.dispose()
            assertSame(baseline, field.border)

            var writes = 0
            val counter = PropertyChangeListener { writes++ }
            field.addPropertyChangeListener("border", counter)
            try {
                second.release()
                assertEquals(1, writes)
                val expected = GlowFocusBorder(baseline, Color.BLUE, GlowStyle.SOFT, 60)
                assertEquals(paintBorder(field, expected), paintCurrentBorder(field))
            } finally {
                field.removePropertyChangeListener("border", counter)
            }
        }

    @Test
    fun `disposing the visible writer keeps the surviving ring before or after the holds release`() {
        for (releaseBeforeDispose in listOf(false, true)) {
            withSharedManagers(focused = true) { field, firstManager, secondManager, baseline ->
                val firstListener = field.focusListeners.filterIsInstance<GlowFocusListener>().first()
                val first = firstManager.holdCallbacks()
                val second = secondManager.holdCallbacks()
                val checkpoints =
                    captureGlowFocus(listOf(first, second)) { hold, actual ->
                        actual.copy(color = if (hold === first) Color.RED else Color.BLUE)
                    }
                checkpoints.forEach { it.apply() }
                checkpoints.forEach { it.accept() }
                if (releaseBeforeDispose) {
                    first.release()
                    second.release()
                }

                secondManager.dispose()
                if (!releaseBeforeDispose) {
                    assertSame(baseline, field.border)
                    first.release()
                }
                val expected = GlowFocusBorder(baseline, Color.RED, GlowStyle.SOFT, 50)
                assertEquals(paintBorder(field, expected), paintCurrentBorder(field))
                assertSame(firstListener, field.focusListeners.filterIsInstance<GlowFocusListener>().single())
            }
        }
    }

    @Test
    fun `focusing an overlapping field installs one border and retains the original on close`() =
        withSharedManagers(focused = false) { field, firstManager, secondManager, baseline ->
            field.isFocusSimulated = true
            var writes = 0
            field.addPropertyChangeListener("border") { writes++ }
            field.focusListeners.filterIsInstance<GlowFocusListener>().forEach {
                it.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            }

            assertEquals(1, writes)
            val installed = field.border
            val expected = GlowFocusBorder(baseline, Color.CYAN, GlowStyle.SOFT, 60)
            assertEquals(paintBorder(field, expected), paintCurrentBorder(field))
            firstManager.dispose()
            assertSame(installed, field.border)
            secondManager.dispose()
            assertSame(baseline, field.border)
        }

    @Test
    fun `partial holds preserve the other ring and reconcile focus loss`() {
        for (startsFocused in listOf(false, true)) {
            withSharedManagers(focused = startsFocused) { field, firstManager, secondManager, baseline ->
                val hold = firstManager.holdCallbacks()
                val checkpoint = hold.captureAppearance { it.copy(color = Color.RED) }.single()
                assertEquals(setOf(GlowFocusField.APPEARANCE), checkpoint.record.fields.keys)
                checkpoint.apply()
                field.isFocusSimulated = true
                field.focusListeners.filterIsInstance<GlowFocusListener>().forEach {
                    it.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
                }
                val installed = field.border

                checkpoint.accept()
                hold.release()

                assertSame(installed, field.border)
                val expected = GlowFocusBorder(baseline, Color.CYAN, GlowStyle.SOFT, 60)
                assertEquals(paintBorder(field, expected), paintCurrentBorder(field))

                val visibleHold = secondManager.holdCallbacks()
                val visibleEdit = visibleHold.captureAppearance { it.copy(color = Color.BLUE) }.single()
                visibleEdit.apply()
                visibleEdit.accept()
                field.isFocusSimulated = false
                field.focusListeners.filterIsInstance<GlowFocusListener>().forEach {
                    it.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
                }
                assertSame(installed, field.border)
                visibleHold.release()
                assertSame(baseline, field.border)
            }
        }
    }

    @Test
    fun `focusing overlapping listeners preserves an unowned Glow border`() =
        withSharedManagers(focused = false) { field, firstManager, _, baseline ->
            val foreign = GlowFocusBorder(baseline, Color.GREEN, GlowStyle.SOFT, 65)
            field.border = foreign
            val hold = firstManager.holdCallbacks()
            field.isFocusSimulated = true
            field.focusListeners.filterIsInstance<GlowFocusListener>().forEach {
                it.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            }

            hold.release()

            assertSame(foreign, field.border)
        }

    @Test
    fun `closing the visible owner hands back the exact border when the survivor releases`() {
        for (replaceBorder in listOf(false, true)) {
            withSharedManagers(focused = true) { field, firstManager, secondManager, baseline ->
                val appliedHolds = listOf(firstManager.holdCallbacks(), secondManager.holdCallbacks())
                val checkpoints = captureGlowFocus(appliedHolds) { _, actual -> actual.copy(intensity = 55) }
                checkpoints.forEach { it.apply() }
                checkpoints.forEach { it.accept() }
                appliedHolds.forEach { it.release() }

                val unrelatedHold = firstManager.holdCallbacks()
                secondManager.dispose()
                assertSame(baseline, field.border)
                val foreign = EmptyBorder(6, 5, 4, 3)
                if (replaceBorder) field.border = foreign
                unrelatedHold.release()

                if (replaceBorder) {
                    assertSame(foreign, field.border)
                } else {
                    val expected = GlowFocusBorder(baseline, Color.ORANGE, GlowStyle.SOFT, 55)
                    assertEquals(paintBorder(field, expected), paintCurrentBorder(field))
                }
            }
        }
    }

    @Test
    fun `shared disable removes just the complete owned chain and leaves both listeners registered`() =
        withSharedField(focused = true) { field, first, second, baseline ->
            val listeners = field.focusListeners.toList()
            val checkpoints = captureGlowFocus(listOf(first, second)) { _, actual -> actual.copy(enabled = false) }
            assertEquals(1, checkpoints.count { GlowFocusField.BORDER in it.record.fields })

            checkpoints.forEach { it.apply() }
            checkpoints.forEach { it.accept() }
            first.release()
            second.release()

            assertSame(baseline, field.border)
            assertEquals(listeners, field.focusListeners.toList())
        }

    @Test
    fun `reenabling after shared disable draws immediately with the same listeners`() =
        withSharedManagers(focused = true) { field, firstManager, secondManager, baseline ->
            val listeners = field.focusListeners.toList()
            val firstDisable = firstManager.holdCallbacks()
            val secondDisable = secondManager.holdCallbacks()
            val disableHolds = listOf(firstDisable, secondDisable)
            val disable = captureGlowFocus(disableHolds) { _, actual -> actual.copy(enabled = false) }
            disable.forEach { it.apply() }
            disable.forEach { it.accept() }
            firstDisable.release()
            secondDisable.release()
            assertSame(baseline, field.border)

            val firstEnable = firstManager.holdCallbacks()
            val secondEnable = secondManager.holdCallbacks()
            val enableHolds = listOf(firstEnable, secondEnable)
            val enable = captureGlowFocus(enableHolds) { _, actual -> actual.copy(enabled = true) }
            enable.forEach { it.apply() }
            val expected = GlowFocusBorder(baseline, Color.CYAN, GlowStyle.SOFT, 60)
            val applied = field.border
            assertEquals(paintBorder(field, expected), paintCurrentBorder(field))

            enable.forEach { it.accept() }
            firstEnable.release()
            secondEnable.release()

            assertSame(applied, field.border)
            assertEquals(listeners, field.focusListeners.toList())
        }

    @Test
    fun `shared capture leaves a foreign visible border untouched`() =
        withSharedField(focused = true) { field, first, second, _ ->
            val foreign = EmptyBorder(6, 5, 4, 3)
            field.border = foreign
            val checkpoints = captureGlowFocus(listOf(first, second)) { _, actual -> actual.copy(color = Color.RED) }
            assertEquals(2, checkpoints.size)
            assertTrue(checkpoints.none { GlowFocusField.BORDER in it.record.fields })

            var writes = 0
            field.addPropertyChangeListener("border") { writes++ }
            checkpoints.forEach { it.apply() }
            assertEquals(0, writes)
            restoreSharedFocus(checkpoints)
            first.release()
            second.release()

            assertEquals(0, writes)
            assertSame(foreign, field.border)
        }

    @Test
    fun `release preserves a border installed by another writer during the hold`() =
        withFocusWindows { manager, windows ->
            val field = FocusField()
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val hold = manager.holdCallbacks()
            val replacement = EmptyBorder(7, 6, 5, 4)
            field.isFocusSimulated = true
            addedFocusListener(field, baseline).focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            field.border = replacement

            hold.release()

            assertSame(replacement, field.border)
        }

    @Test
    fun `release does not reinstall a listener removed during the hold`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val border = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val hold = manager.holdCallbacks()
            val removed =
                HierarchyEvent(
                    field,
                    HierarchyEvent.HIERARCHY_CHANGED,
                    field,
                    field.parent,
                    HierarchyEvent.DISPLAYABILITY_CHANGED.toLong(),
                )
            field.hierarchyListeners.forEach { it.hierarchyChanged(removed) }
            assertSame(border, field.border)

            hold.release()

            assertSame(border, field.border)
            assertListenerBaseline(field, baseline)
        }

    @Test
    fun `a Swing border listener failure leaves later focus callbacks working`() =
        withFocusWindows { manager, windows ->
            val first = FocusField().apply { isFocusSimulated = true }
            val second = FocusField()
            val panel =
                JPanel().apply {
                    add(first)
                    add(second)
                }
            windows += focusWindow(panel)
            val firstBorder = first.border
            val secondBorder = second.border
            val secondBaseline = listenerBaseline(second)
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val hold = manager.holdCallbacks()
            val failure = IllegalStateException("Border listener failed")
            first.addPropertyChangeListener("border") { throw failure }
            first.isFocusSimulated = false
            second.isFocusSimulated = true

            assertSame(failure, assertFailsWith<IllegalStateException> { hold.release() })

            assertSame(firstBorder, first.border)
            assertIs<GlowFocusBorder>(second.border)
            addedFocusListener(second, secondBaseline).focusLost(FocusEvent(second, FocusEvent.FOCUS_LOST))
            assertSame(secondBorder, second.border)
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
}

private fun withSharedField(
    focused: Boolean,
    test: (FocusField, FocusRingManager.CallbackHold, FocusRingManager.CallbackHold, Border) -> Unit,
) = withSharedManagers(focused) { field, firstManager, secondManager, baseline ->
    val first = firstManager.holdCallbacks()
    val second = secondManager.holdCallbacks()
    try {
        test(field, first, second, baseline)
    } finally {
        first.release()
        second.release()
    }
}

private fun withSharedManagers(
    focused: Boolean,
    test: (FocusField, FocusRingManager, FocusRingManager, Border) -> Unit,
) = withFocusWindows { firstManager, windows ->
    val field = FocusField().apply { isFocusSimulated = focused }
    val baseline = checkNotNull(field.border)
    windows += focusWindow(JPanel().apply { add(field) })
    firstManager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
    val secondManager = FocusRingManager()
    try {
        secondManager.initializeFocusRingGlow(Color.CYAN, GlowStyle.SOFT, 60)
        test(field, firstManager, secondManager, baseline)
    } finally {
        secondManager.dispose()
    }
}

private fun restoreSharedFocus(checkpoints: List<GlowFocusListener.Checkpoint>) {
    for (checkpoint in checkpoints.asReversed()) {
        checkpoint.prepareRestore()
        checkpoint.restore()
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

    override fun hasFocus(): Boolean = isFocusSimulated
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
