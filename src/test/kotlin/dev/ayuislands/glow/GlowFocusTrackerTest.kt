package dev.ayuislands.glow

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class GlowFocusTrackerTest {
    @AfterTest
    fun tearDown() {
        unmockkStatic(KeyboardFocusManager::class)
    }

    @Test
    fun `retry after native add failure preserves listener identity and foreign order`() {
        val manager = FailingFocusManager()
        val foreign = PropertyChangeListener { }
        manager.addPropertyChangeListener("permanentFocusOwner", foreign)
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns manager
        val tracker = GlowFocusTracker { }
        manager.failAdd = true

        assertFailsWith<IllegalStateException> { tracker.register() }
        val captured = manager.getPropertyChangeListeners("permanentFocusOwner").toList()
        assertEquals(2, captured.size)
        tracker.register()
        tracker.register()

        val retried = manager.getPropertyChangeListeners("permanentFocusOwner").toList()
        assertEquals(2, retried.size)
        captured.zip(retried).forEach { (before, after) -> assertSame(before, after) }
        tracker.dispose()
        assertSame(foreign, manager.getPropertyChangeListeners("permanentFocusOwner").single())
    }

    @Test
    fun `disposal uses original focus manager and retries mutation then throw without touching replacement`() {
        val original = FailingFocusManager()
        val replacement = DefaultKeyboardFocusManager()
        val foreign = PropertyChangeListener { }
        replacement.addPropertyChangeListener("permanentFocusOwner", foreign)
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns original
        val tracker = GlowFocusTracker { }
        tracker.register()
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns replacement
        original.failRemove = true

        assertFailsWith<IllegalStateException> { tracker.dispose() }
        tracker.dispose()
        tracker.dispose()

        assertEquals(0, original.getPropertyChangeListeners("permanentFocusOwner").size)
        assertSame(foreign, replacement.getPropertyChangeListeners("permanentFocusOwner").single())
        assertFailsWith<IllegalStateException> { tracker.register() }
    }
}

private class FailingFocusManager : DefaultKeyboardFocusManager() {
    var failAdd = false
    var failRemove = false

    override fun addPropertyChangeListener(
        propertyName: String?,
        listener: PropertyChangeListener?,
    ) {
        super.addPropertyChangeListener(propertyName, listener)
        if (failAdd) {
            failAdd = false
            error("Added then failed")
        }
    }

    override fun removePropertyChangeListener(
        propertyName: String?,
        listener: PropertyChangeListener?,
    ) {
        super.removePropertyChangeListener(propertyName, listener)
        if (failRemove) {
            failRemove = false
            error("Removed then failed")
        }
    }
}
