package dev.ayuislands.studio

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeCacheSlot
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.ChromeTintSnapshot
import dev.ayuislands.accent.ClassFqn
import dev.ayuislands.accent.ExternalChromeOwnership
import dev.ayuislands.accent.TintIntensity
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.elements.AbstractChromeElement
import dev.ayuislands.accent.elements.ProgressBarElement
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioAccentDefaults
import dev.ayuislands.studio.session.StudioAccentReferences
import dev.ayuislands.studio.session.StudioChromeCapture
import dev.ayuislands.studio.session.StudioChromeInput
import dev.ayuislands.studio.session.StudioChromeSurface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.awt.Window
import java.beans.PropertyChangeListener
import java.time.Instant
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioChromeSurfaceTest {
    @Test
    fun `external membership precedes native writes and cancellation restores everything`() =
        withSurface { peer ->
            val batch = StudioChromeCapture(references()).capture(input(), Color.ORANGE, listOf(Element()))
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertFalse(peer.isBackgroundSet)
            val listener =
                PropertyChangeListener {
                    if (it.propertyName ==
                        BACKGROUND
                    ) {
                        assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
                    }
                }
            UIManager.getDefaults().addPropertyChangeListener(listener)
            try {
                batch.membershipBarrier.apply()
                batch.elements.apply()
            } finally {
                UIManager.getDefaults().removePropertyChangeListener(listener)
            }
            assertTrue(batch.receipts.all { it.status == RestoreStatus.OWNED })
            batch.elements.restore(emptyList())
            batch.membershipBarrier.restore(batch.elements.receipts)
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertFalse(peer.isBackgroundSet)
            assertTrue(batch.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `failed peer recovery retains membership while an independent earlier surface restores`() =
        withSurface { peer ->
            val batch =
                StudioChromeCapture(
                    references(),
                ).capture(input(), Color.ORANGE, listOf(OtherElement(), Element()))
            batch.membershipBarrier.apply()
            batch.elements.apply()
            peer.failPaint = true
            assertFailsWith<IllegalStateException> {
                batch.elements.restore(emptyList())
                batch.membershipBarrier.restore(batch.elements.receipts)
            }
            assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.PANEL_BORDER))
            assertSame(Color.GRAY, raw(SECOND_BACKGROUND))
            assertFalse(peer.isBackgroundSet)
            assertTrue(batch.receipts.any { it.status == RestoreStatus.OWNED })
            batch.elements.restore(emptyList())
            batch.membershipBarrier.restore(batch.elements.receipts)
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertTrue(batch.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `Ayu retires all external memberships before the first element setter`() =
        withSurface { _ ->
            for (id in listOf(AccentElementId.NAV_BAR, AccentElementId.PANEL_BORDER)) {
                val native = requireNotNull(ExternalChromeOwnership.prepare(id, true))
                assertTrue(native.apply())
                native.commit()
            }
            val candidate = input().copy(context = AccentContext.Ayu(AyuVariant.MIRAGE))
            val batch =
                StudioChromeCapture(
                    references(),
                ).capture(candidate, Color.ORANGE, listOf(Element(), OtherElement()))
            val listener =
                PropertyChangeListener {
                    if (it.propertyName == BACKGROUND) {
                        assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
                        assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.PANEL_BORDER))
                    }
                }
            UIManager.getDefaults().addPropertyChangeListener(listener)
            try {
                batch.membershipBarrier.apply()
                batch.elements.apply()
            } finally {
                UIManager.getDefaults().removePropertyChangeListener(listener)
            }
            batch.elements.restore(emptyList())
            batch.membershipBarrier.restore(batch.elements.receipts)
            assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.PANEL_BORDER))
        }

    @Test
    fun `accepted tint keeps cleanup ownership and disable preserves granular choices`() =
        withSurface { peer ->
            val candidate = input()
            val tint = StudioChromeCapture(references()).capture(candidate, Color.ORANGE, listOf(Element()))
            tint.membershipBarrier.apply()
            tint.elements.apply()
            val receipts = tint.receipts
            val color = peer.background
            tint.elements.accept()
            tint.membershipBarrier.accept()
            tint.elements.accept()
            tint.membershipBarrier.accept()
            assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertEquals(receipts, tint.receipts)
            assertFailsWith<IllegalStateException> {
                tint.elements.restore(emptyList())
                tint.membershipBarrier.restore(tint.elements.receipts)
            }
            val disabled = candidate.copy(settings = candidate.settings.copy(chromeTintingEnabled = false))
            val clear =
                StudioChromeCapture(
                    references(),
                ).capture(disabled, Color.ORANGE, listOf(Element(available = false)))
            clear.membershipBarrier.apply()
            clear.elements.apply()
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertFalse(peer.isBackgroundSet)
            assertNull(raw(BACKGROUND))
            clear.elements.restore(emptyList())
            clear.membershipBarrier.restore(clear.elements.receipts)
            assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertSame(color, peer.background)
            assertTrue(disabled.settings.chromeNavBar)
        }

    @Test
    fun `unowned surfaces stay untouched and unsupported tint cannot claim ownership`() =
        withSurface { peer ->
            for (candidate in listOf(input().copy(externalAllowed = false), input())) {
                val batch =
                    StudioChromeCapture(
                        references(),
                    ).capture(candidate, Color.ORANGE, listOf(Element(available = false)))
                assertTrue(batch.receipts.isEmpty())
                batch.membershipBarrier.apply()
                batch.elements.apply()
                batch.elements.accept()
                batch.membershipBarrier.accept()
                assertSame(Color.BLUE, raw(BACKGROUND))
                assertFalse(peer.isBackgroundSet)
                assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            }
        }

    @Test
    fun `duplicate IDs and overlapping fields fail capture before writes`() =
        withSurface { _ ->
            for (elements in listOf(listOf(Element(), Element()), listOf(Element(), AliasElement()))) {
                assertFailsWith<IllegalArgumentException> {
                    StudioChromeCapture(references()).capture(input(), Color.ORANGE, elements)
                }
                assertSame(Color.BLUE, raw(BACKGROUND))
                assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
                ChromeCacheSlot.entries.forEach { assertNull(cache(it)) }
            }
        }

    @Test
    fun `foreign equal valued peer survives aggregate rollback without leaving an Ayu claim`() =
        withSurface { peer ->
            val batch = StudioChromeCapture(references()).capture(input(), Color.ORANGE, listOf(Element()))
            batch.membershipBarrier.apply()
            batch.elements.apply()
            val foreign = Color(peer.background.rgb)
            peer.background = foreign
            batch.elements.restore(emptyList())
            batch.membershipBarrier.restore(batch.elements.receipts)
            assertSame(foreign, peer.background)
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertEquals(1, batch.receipts.count { it.status == RestoreStatus.FOREIGN })
        }

    @Test
    fun `stock invalidation after capture cannot strand a newly applied membership`() =
        withSurface { peer ->
            val batch = StudioChromeCapture(references()).capture(input(), Color.ORANGE, listOf(Element()))
            ChromeBaseColors.refresh()
            assertFailsWith<IllegalStateException> {
                batch.membershipBarrier.apply()
                batch.elements.apply()
            }
            assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertFalse(peer.isBackgroundSet)
            assertFailsWith<IllegalStateException> { batch.elements.apply() }
            batch.elements.restore(emptyList())
            batch.membershipBarrier.restore(batch.elements.receipts)
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertTrue(batch.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `capture leaves native state untouched and cancellation restores defaults caches and inherited peer`() =
        withSurface { peer ->
            var reads = 0
            UIManager.put(
                BACKGROUND,
                UIDefaults.LazyValue {
                    reads++
                    Color.BLUE
                },
            )
            val paints = peer.paints
            val surface = prepare()
            assertEquals(1, reads)
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertNull(cache(ChromeCacheSlot.CURRENT))
            assertNull(cache(ChromeCacheSlot.STOCK))
            assertFalse(peer.isBackgroundSet)
            assertEquals(paints, peer.paints)
            assertTrue(surface.receipts.all { it.status == RestoreStatus.PENDING })

            surface.apply()
            assertSame(Color.BLUE, cache(ChromeCacheSlot.STOCK))
            assertSame(Color.BLUE, cache(ChromeCacheSlot.CURRENT))
            assertSame(raw(BACKGROUND), cache(ChromeCacheSlot.TINT))
            assertSame(raw(BACKGROUND), peer.background)
            assertNull(raw(OVERLAY))
            assertTrue(surface.receipts.all { it.status == RestoreStatus.OWNED })
            surface.restore(emptyList())
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertSame(Color.GREEN, raw(FOREGROUND))
            assertSame(Color.PINK, raw(OVERLAY))
            ChromeCacheSlot.entries.forEach { assertNull(cache(it)) }
            assertFalse(peer.isBackgroundSet)
            assertEquals(1, reads)
            assertTrue(surface.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `mutation then throw retains all earlier cache publications for rollback`() =
        withSurface { peer ->
            val surface = prepare()
            val listener = PropertyChangeListener { if (it.propertyName == BACKGROUND) error("After native write") }
            UIManager.getDefaults().addPropertyChangeListener(listener)
            try {
                assertFailsWith<IllegalStateException> { surface.apply() }
            } finally {
                UIManager.getDefaults().removePropertyChangeListener(listener)
            }
            assertSame(Color.BLUE, cache(ChromeCacheSlot.CURRENT))
            assertFalse(peer.isBackgroundSet)
            surface.restore(emptyList())
            assertSame(Color.BLUE, raw(BACKGROUND))
            ChromeCacheSlot.entries.forEach { assertNull(cache(it)) }
            assertTrue(surface.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `peer repaint failure is reversible after all fields were written`() =
        withSurface { peer ->
            val surface = prepare()
            peer.failPaint = true
            assertFailsWith<IllegalStateException> { surface.apply() }
            assertTrue(peer.isBackgroundSet)
            surface.restore(emptyList())
            assertFalse(peer.isBackgroundSet)
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertSame(Color.GREEN, raw(FOREGROUND))
            assertSame(Color.PINK, raw(OVERLAY))
            ChromeCacheSlot.entries.forEach { assertNull(cache(it)) }
        }

    @Test
    fun `foreign peer change survives while the remaining surface restores`() =
        withSurface { peer ->
            val surface = prepare()
            surface.apply()
            val foreign = Color(peer.background.rgb)
            peer.background = foreign
            surface.restore(emptyList())
            assertSame(foreign, peer.background)
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertEquals(1, surface.receipts.count { it.status == RestoreStatus.FOREIGN })
        }

    @Test
    fun `cache refresh after capture rejects application before the first default or peer write`() =
        withSurface { peer ->
            val surface = prepare()
            ChromeBaseColors.refresh()
            assertFailsWith<IllegalStateException> { surface.apply() }
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertFalse(peer.isBackgroundSet)
            surface.restore(emptyList())
            assertSame(Color.BLUE, raw(BACKGROUND))
        }

    @Test
    fun `a later census callback cannot make an earlier stock observation stale`() =
        withSurface { _ ->
            every { Window.getWindows() } answers {
                UIManager.put(BACKGROUND, Color.CYAN)
                emptyArray()
            }
            assertFailsWith<IllegalStateException> { prepare() }
            assertSame(Color.CYAN, raw(BACKGROUND))
            ChromeCacheSlot.entries.forEach { assertNull(cache(it)) }
        }

    @Test
    fun `overlapping default fields are rejected before receipt preparation`() =
        withSurface { _ ->
            assertFailsWith<IllegalArgumentException> {
                StudioChromeSurface.tint(
                    references(),
                    OverlappingElement(),
                    Color.ORANGE,
                    TintIntensity.of(20),
                    canHandleEmpty = true,
                )
            }
            assertSame(Color.BLUE, raw(BACKGROUND))
            ChromeCacheSlot.entries.forEach { assertNull(cache(it)) }
        }

    @Test
    fun `unsupported apply capability does not read stock or clean an existing surface`() =
        withSurface { peer ->
            UIManager.put(BACKGROUND, UIDefaults.ActiveValue { error("Unsupported surface must not read stock") })
            val before = raw(BACKGROUND)
            val surface =
                StudioChromeSurface.tint(
                    references(),
                    Element(available = false),
                    Color.ORANGE,
                    TintIntensity.of(20),
                    canHandleEmpty = true,
                )
            assertTrue(surface.receipts.isEmpty())
            surface.apply()
            surface.accept()
            assertSame(before, raw(BACKGROUND))
            assertFalse(peer.isBackgroundSet)
        }

    @Test
    fun `accept keeps native values and frozen evidence and prohibits rollback`() =
        withSurface { peer ->
            val surface = prepare()
            surface.apply()
            val receipts = surface.receipts
            val color = peer.background
            surface.accept()
            surface.accept()
            assertSame(color, peer.background)
            assertSame(color, raw(BACKGROUND))
            assertEquals(receipts, surface.receipts)
            assertFailsWith<IllegalStateException> { surface.restore(emptyList()) }
        }

    @Test
    fun `clear restores the preceding tint when cancelled and writes overlays after peers`() =
        withSurface { peer ->
            val tint = prepare()
            tint.apply()
            tint.accept()
            val tinted = raw(BACKGROUND)
            val foreground = raw(FOREGROUND)
            UIManager.put(OVERLAY, Color.PINK)
            peer.afterPaint = { assertSame(Color.PINK, raw(OVERLAY)) }
            val clear = StudioChromeSurface.clear(references(), Element(available = false))
            clear.apply()
            peer.afterPaint = null
            assertNull(raw(BACKGROUND))
            assertNull(raw(FOREGROUND))
            assertNull(raw(OVERLAY))
            assertNull(cache(ChromeCacheSlot.TINT))
            assertFalse(peer.isBackgroundSet)
            clear.restore(emptyList())
            assertSame(tinted, raw(BACKGROUND))
            assertSame(foreground, raw(FOREGROUND))
            assertSame(Color.PINK, raw(OVERLAY))
            assertSame(tinted, peer.background)
            assertSame(tinted, cache(ChromeCacheSlot.TINT))
        }
}

class StudioChromeBarrierTest {
    @Test
    fun `Ayu membership barrier precedes non Chrome callbacks without reordering native elements`() =
        withSurface { _ ->
            val ids = listOf(AccentElementId.NAV_BAR, AccentElementId.PANEL_BORDER, AccentElementId.STATUS_BAR)
            seedMemberships(ids)
            val nativeReferences = references()
            val progress = ProgressBarElement()
            val batch =
                StudioChromeCapture(nativeReferences).capture(
                    input().copy(context = AccentContext.Ayu(AyuVariant.MIRAGE)),
                    Color.ORANGE,
                    listOf(progress, Element(), OtherElement()),
                )
            val nonChrome =
                StudioAccentDefaults.prepareRaw(
                    nativeReferences,
                    PROGRESS,
                    progress.uiColors(Color.ORANGE).getValue(PROGRESS),
                )
            val order = mutableListOf<String>()
            val listener =
                PropertyChangeListener {
                    if (it.propertyName in listOf(PROGRESS, BACKGROUND, SECOND_BACKGROUND)) {
                        ids.forEach { id -> assertFalse(ExternalChromeOwnership.isOwned(id), id.name) }
                        order += it.propertyName
                    }
                }
            UIManager.getDefaults().addPropertyChangeListener(listener)
            try {
                batch.membershipBarrier.apply()
                assertTrue(order.isEmpty())
                assertSame(Color.BLUE, raw(BACKGROUND))
                assertSame(Color.GRAY, raw(SECOND_BACKGROUND))
                nonChrome.apply()
                batch.elements.apply()
                assertEquals(listOf(PROGRESS, BACKGROUND, SECOND_BACKGROUND), order)
            } finally {
                UIManager.getDefaults().removePropertyChangeListener(listener)
                batch.elements.restore(emptyList())
                nonChrome.restore(batch.elements.receipts)
                batch.membershipBarrier.restore(batch.elements.receipts + nonChrome.receipts)
            }
            ids.forEach { assertTrue(ExternalChromeOwnership.isOwned(it), it.name) }
            assertSame(Color.BLUE, raw(PROGRESS))
        }

    @Test
    fun `failure between barrier and Chrome retains evidence and restores memberships last`() =
        withSurface { _ ->
            val ids = listOf(AccentElementId.NAV_BAR, AccentElementId.PANEL_BORDER)
            seedMemberships(ids)
            val nativeReferences = references()
            val batch =
                StudioChromeCapture(nativeReferences).capture(
                    input().copy(context = AccentContext.Ayu(AyuVariant.MIRAGE)),
                    Color.ORANGE,
                    listOf(Element(), OtherElement()),
                )
            val nonChrome = StudioAccentDefaults.prepareRaw(nativeReferences, PROGRESS, Color.ORANGE)
            val captured = batch.receipts + nonChrome.receipts
            val listener =
                PropertyChangeListener {
                    if (it.propertyName == PROGRESS) {
                        ids.forEach { id -> assertFalse(ExternalChromeOwnership.isOwned(id), id.name) }
                        if (it.newValue === Color.ORANGE) error("Non Chrome callback failed")
                    }
                }
            UIManager.getDefaults().addPropertyChangeListener(listener)
            try {
                batch.membershipBarrier.apply()
                assertFailsWith<IllegalStateException> { nonChrome.apply() }
                assertSame(Color.ORANGE, raw(PROGRESS))
                assertSame(Color.BLUE, raw(BACKGROUND))
                assertSame(Color.GRAY, raw(SECOND_BACKGROUND))
                assertTrue(batch.membershipBarrier.receipts.all { it.status == RestoreStatus.OWNED })
                assertTrue(batch.elements.receipts.all { it.status == RestoreStatus.PENDING })
                assertEquals(
                    captured,
                    (batch.receipts + nonChrome.receipts).map { it.copy(status = RestoreStatus.PENDING) },
                )
                batch.elements.restore(emptyList())
                nonChrome.restore(batch.elements.receipts)
                batch.membershipBarrier.restore(batch.elements.receipts + nonChrome.receipts)
            } finally {
                UIManager.getDefaults().removePropertyChangeListener(listener)
            }
            ids.forEach { assertTrue(ExternalChromeOwnership.isOwned(it), it.name) }
            assertSame(Color.BLUE, raw(PROGRESS))
            assertTrue((batch.receipts + nonChrome.receipts).all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `failed Chrome rollback retains only its barrier receipt while independent phases restore`() =
        withSurface { peer ->
            val ids = listOf(AccentElementId.NAV_BAR, AccentElementId.PANEL_BORDER)
            seedMemberships(ids)
            val nativeReferences = references()
            val batch =
                StudioChromeCapture(nativeReferences).capture(
                    input().copy(context = AccentContext.Ayu(AyuVariant.MIRAGE)),
                    Color.ORANGE,
                    listOf(OtherElement(), Element()),
                )
            val nonChrome = StudioAccentDefaults.prepareRaw(nativeReferences, PROGRESS, Color.ORANGE)
            val captured = batch.receipts + nonChrome.receipts
            batch.membershipBarrier.apply()
            nonChrome.apply()
            batch.elements.apply()
            peer.failPaint = true

            assertFailsWith<IllegalStateException> { batch.elements.restore(emptyList()) }
            nonChrome.restore(batch.elements.receipts)
            assertFailsWith<IllegalStateException> {
                batch.membershipBarrier.restore(batch.elements.receipts + nonChrome.receipts)
            }
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertTrue(ExternalChromeOwnership.isOwned(AccentElementId.PANEL_BORDER))
            assertSame(Color.GRAY, raw(SECOND_BACKGROUND))
            assertSame(Color.BLUE, raw(PROGRESS))
            assertTrue(batch.elements.receipts.any { it.status == RestoreStatus.OWNED })
            assertEquals(
                captured,
                (batch.receipts + nonChrome.receipts).map { it.copy(status = RestoreStatus.PENDING) },
            )

            batch.elements.restore(emptyList())
            batch.membershipBarrier.restore(batch.elements.receipts + nonChrome.receipts)
            ids.forEach { assertTrue(ExternalChromeOwnership.isOwned(it), it.name) }
            assertSame(Color.BLUE, raw(BACKGROUND))
            assertTrue((batch.receipts + nonChrome.receipts).all { it.status == RestoreStatus.RESTORED })
        }
}

private fun seedMemberships(ids: List<AccentElementId>) {
    ids.forEach { id ->
        val membership = requireNotNull(ExternalChromeOwnership.prepare(id, true))
        assertTrue(membership.apply())
        membership.commit()
    }
}

private fun prepare(): StudioChromeSurface =
    StudioChromeSurface.tint(references(), Element(), Color.ORANGE, TintIntensity.of(20), canHandleEmpty = true)

private fun references(): StudioAccentReferences = StudioAccentReferences("surface", StudioProcess(42, Instant.EPOCH))

private fun input(): StudioChromeInput =
    StudioChromeInput(
        context = AccentContext.External,
        settings =
            ChromeTintSnapshot(
                chromeStatusBar = true,
                chromeMainToolbar = true,
                chromeToolWindowStripe = true,
                chromeNavBar = true,
                chromePanelBorder = true,
                intensity = TintIntensity.of(20),
            ),
        externalAllowed = true,
        forceOverrides = emptyList(),
        entitlement = LicenseEntitlement.LICENSED,
    )

private fun raw(key: String): Any? = readRawUiDefault(UIManager.getDefaults(), key)

private fun cache(
    slot: ChromeCacheSlot,
    key: String = BACKGROUND,
): Color? = ChromeBaseColors.prepare(slot, key, null).before

private fun withSurface(action: (Peer) -> Unit) {
    SwingUtilities.invokeAndWait {
        val defaults = listOf(BACKGROUND, FOREGROUND, OVERLAY, SECOND_BACKGROUND, PROGRESS).associateWith(::raw)
        val cells =
            listOf(BACKGROUND, SECOND_BACKGROUND).flatMap { key ->
                ChromeCacheSlot.entries.map { it to key }
            }
        val caches = cells.associateWith { (slot, key) -> cache(slot, key) }
        try {
            ExternalChromeOwnership.resetForTests()
            cells.forEach { (slot, key) -> setCache(slot, null, key) }
            UIManager.put(BACKGROUND, Color.BLUE)
            UIManager.put(FOREGROUND, Color.GREEN)
            UIManager.put(OVERLAY, Color.PINK)
            UIManager.put(SECOND_BACKGROUND, Color.GRAY)
            UIManager.put(PROGRESS, Color.BLUE)
            val peer = Peer().apply { background = null }
            JPanel().apply {
                background = Color.BLACK
                add(peer)
            }
            val window =
                mockk<Window> {
                    every { isShowing } returns true
                    every { components } returns arrayOf(peer)
                }
            mockkStatic(Window::class)
            mockkObject(ConflictRegistry)
            every { ConflictRegistry.getConflictFor(any()) } returns null
            every { Window.getWindows() } returns arrayOf(window)
            action(peer)
        } finally {
            unmockkAll()
            ExternalChromeOwnership.resetForTests()
            defaults.forEach { (key, value) -> UIManager.put(key, value) }
            caches.forEach { (cell, value) -> setCache(cell.first, value, cell.second) }
        }
    }
}

private fun setCache(
    slot: ChromeCacheSlot,
    color: Color?,
    key: String = BACKGROUND,
) {
    val cell = ChromeBaseColors.prepare(slot, key, color)
    assertTrue(cell.apply())
    cell.commit()
}

private open class Element(
    available: Boolean = true,
) : AbstractChromeElement() {
    override val isEnabled: Boolean = available
    override val id: AccentElementId = AccentElementId.NAV_BAR
    override val displayName: String = "Test Chrome"
    override val backgroundKeys: List<String> = listOf(BACKGROUND)
    override val foregroundKeys: List<String> = listOf(FOREGROUND)
    override val overlayBackgroundKeys: List<String> = listOf(OVERLAY)
    override val foregroundTextTarget: WcagForeground.TextTarget = WcagForeground.TextTarget.PRIMARY_TEXT
    override val peerTarget: ChromeTarget = ChromeTarget.ByClassName(ClassFqn.require(Peer::class.java.name))
}

private class OverlappingElement : Element() {
    override val foregroundKeys: List<String> = listOf(BACKGROUND)
}

private class AliasElement : Element() {
    override val id: AccentElementId = AccentElementId.PANEL_BORDER
}

private class OtherElement : AbstractChromeElement() {
    override val id: AccentElementId = AccentElementId.PANEL_BORDER
    override val displayName: String = "Other surface"
    override val backgroundKeys: List<String> = listOf(SECOND_BACKGROUND)
    override val foregroundKeys: List<String> = emptyList()
    override val foregroundTextTarget: WcagForeground.TextTarget = WcagForeground.TextTarget.PRIMARY_TEXT
    override val peerTarget: ChromeTarget? = null
}

private class Peer : JPanel() {
    var paints: Int = 0
    var failPaint: Boolean = false
    var afterPaint: (() -> Unit)? = null

    override fun repaint() {
        paints++
        if (failPaint) {
            failPaint = false
            error("Peer repaint failed")
        }
        afterPaint?.invoke()
    }
}

private const val PROGRESS = "ProgressBar.foreground"
private const val BACKGROUND = "Ayu.test.studio.chrome.background"
private const val FOREGROUND = "Ayu.test.studio.chrome.foreground"
private const val OVERLAY = "Ayu.test.studio.chrome.overlay"
private const val SECOND_BACKGROUND = "Ayu.test.studio.chrome.other"
