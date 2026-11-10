package dev.ayuislands.glow

import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.settings.AyuIslandsState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class GlowUpdateGateTest {
    @Test
    fun `unavailable authority gates callbacks while native owner can capture and restore absence`() {
        var accepted: GlowRuntimeInput? = null
        val focus = FocusRingManager()
        val gate = GlowUpdateGate({ false }, { accepted }, focus::holdCallbacks)
        assertFalse(gate.isOpen)
        assertFalse(gate.admit())
        val held = gate.hold()
        try {
            assertEquals(null, held.before)
            val candidate = loadGlowRuntimeInput(AyuIslandsState())
            held.select(candidate)
            assertSame(candidate, gate.current)
            assertFalse(gate.admit())
            held.select(held.before)
            held.release()
            assertFalse(gate.admit())
            accepted = candidate
            assertTrue(gate.admit())
            assertSame(candidate, gate.current)
        } finally {
            held.release()
        }
    }

    @Test
    fun `explicit selection keeps before and accent mailbox independent`() {
        val before = loadGlowRuntimeInput(AyuIslandsState())
        val candidate = before.copy(settings = before.settings.copy(enabled = !before.settings.enabled))
        val focus = FocusRingManager()
        val gate = GlowUpdateGate({ false }, { before }, focus::holdCallbacks)
        val hold = gate.hold()
        val accent = AccentHex.require("#123456")
        try {
            assertFalse(gate.admit(accent))
            assertSame(before, gate.current)
            hold.select(candidate)

            assertSame(candidate, gate.current)
            assertSame(before, hold.before)
            assertEquals(accent, hold.pendingAccent)
            assertFalse(gate.isOpen)
            assertFalse(gate.admit())

            hold.select(hold.before)
            assertSame(before, gate.current)
        } finally {
            hold.release()
        }
    }

    @Test
    fun `release returns to the canonical input reader after accepted handoff`() {
        val before = loadGlowRuntimeInput(AyuIslandsState())
        val candidate = before.copy(settings = before.settings.copy(style = GlowStyle.SHARP_NEON))
        var accepted = before
        val focus = FocusRingManager()
        val gate = GlowUpdateGate({ false }, { accepted }, focus::holdCallbacks)
        val hold = gate.hold()
        try {
            hold.select(candidate)
            assertSame(candidate, gate.current)
            assertSame(before, accepted)

            accepted = candidate
            hold.release()

            assertTrue(gate.isOpen)
            assertSame(candidate, gate.current)
            accepted = before
            assertSame(before, gate.current)
        } finally {
            hold.release()
        }
    }

    @Test
    fun `retired hold cannot change its replacement selection`() {
        val before = loadGlowRuntimeInput(AyuIslandsState())
        val candidate = before.copy(settings = before.settings.copy(shape = GlowShape.WAVEFORM))
        val focus = FocusRingManager()
        val gate = GlowUpdateGate({ false }, { before }, focus::holdCallbacks)
        val retired = gate.hold()
        retired.release()
        val current = gate.hold()
        try {
            assertFailsWith<IllegalStateException> { retired.select(candidate) }
            assertSame(before, gate.current)
            assertFalse(gate.isOpen)
        } finally {
            current.release()
        }
    }

    @Test
    fun `disposed owner cannot select or revive its held input`() {
        val before = loadGlowRuntimeInput(AyuIslandsState())
        val candidate = before.copy(settings = before.settings.copy(style = GlowStyle.SHARP_NEON))
        var isDisposed = false
        val focus = FocusRingManager()
        val gate = GlowUpdateGate({ isDisposed }, { before }, focus::holdCallbacks)
        val hold = gate.hold()
        try {
            isDisposed = true
            assertFailsWith<IllegalStateException> { hold.select(candidate) }
            assertSame(before, gate.current)
            assertFalse(gate.isOpen)
        } finally {
            hold.release()
        }
    }
}
