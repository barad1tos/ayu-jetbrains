package dev.ayuislands.accent

import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioAccentCell
import dev.ayuislands.studio.session.StudioAccentDefaults
import dev.ayuislands.studio.session.StudioAccentReferences
import java.awt.Color
import java.time.Instant
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExternalChromeOwnershipTest {
    @Test
    fun `prepared membership leaves existing authority untouched until acknowledged application`() =
        onEdt {
            val prepared = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
            assertFalse(ExternalChromeOwnership.isOwned(STATUS))
            assertEquals(AccentWriteState.PREPARED, prepared.state)
            assertFalse(prepared.hasPending)
            assertTrue(prepared.apply())
            assertTrue(ExternalChromeOwnership.isOwned(STATUS))
            assertTrue(prepared.hasPending)
            prepared.restore()
            assertFalse(ExternalChromeOwnership.isOwned(STATUS))
            assertEquals(AccentWriteState.RESTORED, prepared.state)
        }

    @Test
    fun `independent prepared memberships do not invalidate each other`() =
        onEdt {
            val status = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
            val border = assertNotNull(ExternalChromeOwnership.prepare(BORDER, true))
            assertTrue(status.apply())
            assertTrue(border.apply())
            status.restore()
            assertFalse(ExternalChromeOwnership.isOwned(STATUS))
            assertTrue(ExternalChromeOwnership.isOwned(BORDER))
            border.commit()
            assertTrue(ExternalChromeOwnership.isOwned(BORDER))
            assertNull(ExternalChromeOwnership.prepare(BORDER, true))
            assertNull(ExternalChromeOwnership.prepare(STATUS, false))
        }

    @Test
    fun `stale preparation never adopts an equal boolean from a newer accepted write`() =
        onEdt {
            val stale = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
            val newer = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
            assertTrue(newer.apply())
            newer.commit()
            assertFalse(stale.apply())
            assertEquals(AccentWriteState.RELINQUISHED, stale.state)
            stale.restore()
            assertTrue(ExternalChromeOwnership.isOwned(STATUS))
        }

    @Test
    fun `rollback preserves newer disable and reenable even when membership has the same value`() =
        onEdt {
            val original = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
            assertTrue(original.apply())
            val disable = assertNotNull(ExternalChromeOwnership.prepare(STATUS, false))
            assertTrue(disable.apply())
            disable.commit()
            val enable = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
            assertTrue(enable.apply())
            enable.commit()
            original.restore()
            assertTrue(ExternalChromeOwnership.isOwned(STATUS))
            assertEquals(AccentWriteState.RELINQUISHED, original.state)
        }

    @Test
    fun `unresolved native surface keeps membership pending and capture cannot run recovery`() =
        onEdt {
            val segment = AccentApplyScope.Segment()
            val membership = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true, segment))
            val peer = FailingPeer().apply { background = Color.BLUE }
            val surface = ChromePeerOverrides.prepare(peer, Color.GREEN)
            segment.record(surface)
            assertTrue(membership.apply())
            assertTrue(surface.apply())
            peer.failRestore = true
            assertFailsWith<IllegalStateException> { surface.restore() }
            assertFailsWith<IllegalStateException> { membership.restore(listOf(surface)) }
            membership.retain()
            assertTrue(ExternalChromeOwnership.isOwned(STATUS))
            assertFailsWith<IllegalStateException> { ExternalChromeOwnership.prepare(STATUS, false) }
            assertTrue(ExternalChromeOwnership.isOwned(STATUS))
            peer.failRestore = false
            membership.restore()
            assertEquals(Color.BLUE, peer.background)
            assertFalse(ExternalChromeOwnership.isOwned(STATUS))
            assertEquals(AccentWriteState.RESTORED, membership.state)
        }

    @Test
    fun `theme replacement does not let an old receipt erase new membership`() =
        onEdt {
            val previousLookAndFeel = UIManager.getLookAndFeel()
            try {
                val old = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
                assertTrue(old.apply())
                UIManager.setLookAndFeel(MetalLookAndFeel())
                assertFalse(ExternalChromeOwnership.isOwned(STATUS))
                val current = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
                assertFalse(ExternalChromeOwnership.isOwned(STATUS))
                assertTrue(current.apply())
                current.commit()
                old.restore()
                assertTrue(ExternalChromeOwnership.isOwned(STATUS))
                assertEquals(AccentWriteState.RELINQUISHED, old.state)
            } finally {
                UIManager.setLookAndFeel(previousLookAndFeel)
            }
        }

    @Test
    fun `prepared retirement acknowledges a stale record without touching its former surface`() =
        onEdt {
            val saved = UIManager.getLookAndFeel()
            try {
                val original = StableLookAndFeel()
                UIManager.setLookAndFeel(original)
                val owned = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
                assertTrue(owned.apply())
                owned.commit()
                UIManager.setLookAndFeel(MetalLookAndFeel())
                val retirement = assertNotNull(ExternalChromeOwnership.prepare(STATUS, false))
                assertTrue(retirement.apply())
                retirement.commit()
                UIManager.setLookAndFeel(original)
                assertFalse(ExternalChromeOwnership.isOwned(STATUS))
            } finally {
                UIManager.setLookAndFeel(saved)
            }
        }

    @Test
    fun `Studio membership waits for journal blockers on its native surface fields`() =
        onEdt {
            val key = "Ayu.test.chrome.membership"
            val before = readRawUiDefault(UIManager.getDefaults(), key)
            UIManager.put(key, Color.BLUE)
            try {
                val references = StudioAccentReferences("membership", StudioProcess(42, Instant.EPOCH))
                val surface = StudioAccentDefaults.prepareRaw(references, key, Color.ORANGE)
                val native = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
                val membership = StudioAccentCell.membership(references, native, surface.receipts)
                membership.apply()
                surface.apply()
                val newer = StudioAccentDefaults.prepareRaw(references, key, Color.GREEN)
                newer.apply()
                assertFailsWith<IllegalStateException> { surface.restore(newer.receipts) }
                assertFailsWith<IllegalStateException> { membership.restore(newer.receipts) }
                assertTrue(ExternalChromeOwnership.isOwned(STATUS))
                assertSame(Color.GREEN, UIManager.get(key))
                newer.restore(emptyList())
                surface.restore(emptyList())
                membership.restore(emptyList())
                assertSame(Color.BLUE, UIManager.get(key))
                assertFalse(ExternalChromeOwnership.isOwned(STATUS))
                assertEquals(RestoreStatus.RESTORED, membership.receipts.single().status)
            } finally {
                UIManager.put(key, before)
            }
        }

    @Test
    fun `Studio acceptance keeps external membership while freezing its original evidence`() =
        onEdt {
            val references = StudioAccentReferences("accepted-membership", StudioProcess(42, Instant.EPOCH))
            val native = assertNotNull(ExternalChromeOwnership.prepare(STATUS, true))
            val membership = StudioAccentCell.membership(references, native, emptyList())
            membership.apply()
            val receipts = membership.receipts
            membership.accept()
            assertEquals(RestoreStatus.OWNED, membership.receipts.single().status)
            assertEquals(receipts, membership.receipts)
            assertTrue(ExternalChromeOwnership.isOwned(STATUS))
            assertFailsWith<IllegalStateException> { membership.restore(emptyList()) }
        }

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait {
            ExternalChromeOwnership.resetForTests()
            try {
                action()
            } finally {
                ExternalChromeOwnership.resetForTests()
            }
        }
    }

    private class FailingPeer : JPanel() {
        var failRestore: Boolean = false

        override fun setBackground(color: Color?) {
            if (failRestore && color === Color.BLUE) error("Restore interrupted")
            super.setBackground(color)
        }
    }

    private class StableLookAndFeel : MetalLookAndFeel() {
        private var savedDefaults: UIDefaults? = null

        override fun getDefaults(): UIDefaults = savedDefaults ?: super.getDefaults().also { savedDefaults = it }
    }

    companion object {
        private val STATUS = AccentElementId.STATUS_BAR
        private val BORDER = AccentElementId.PANEL_BORDER
    }
}
