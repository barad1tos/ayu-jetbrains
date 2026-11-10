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
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioAccentReferences
import dev.ayuislands.studio.session.StudioChromeCapture
import dev.ayuislands.studio.session.StudioChromeInput
import dev.ayuislands.studio.session.StudioChromeOrphans
import dev.ayuislands.studio.session.StudioChromeSurface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.awt.Window
import java.time.Instant
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioChromeOrphansTest {
    @Test
    fun `native switch gives direct tint one writer while only exact old orphan peers clear`() =
        withNativeSwitch { fixture ->
            val references = references()
            val direct =
                StudioChromeCapture(
                    references,
                ).capture(input(AccentContext.External), Color.GREEN, listOf(Element(scoped = true)))
            val before = fixture.peers.map { it.writes }
            val orphans = StudioChromeOrphans.capture(references, listOf(Element()), direct.elements.receipts)

            assertEquals(before, fixture.peers.map { it.writes })
            assertEquals(1, orphans.size)
            assertTrue(orphans.flatMap { it.receipts }.all { it.status == RestoreStatus.PENDING })
            assertNull(raw())
            assertSame(Color.GRAY, UIManager.getColor(BACKGROUND))
            assertSame(fixture.oldTint, cache(ChromeCacheSlot.TINT))
            assertNull(cache(ChromeCacheSlot.CURRENT))

            direct.membershipBarrier.apply()
            direct.elements.apply()
            val finalTint = fixture.direct.background
            assertTrue(finalTint !== fixture.oldTint)
            orphans.forEach { it.apply() }

            assertSame(finalTint, fixture.direct.background)
            assertFalse(fixture.orphan.isBackgroundSet)
            assertSame(fixture.foreignColor, fixture.foreign.background)
            assertSame(fixture.oldTint, fixture.outside.background)
            assertEquals(
                before.zip(listOf(1, 1, 0)).map { (count, delta) ->
                    count + delta
                },
                fixture.peers.map { it.writes },
            )
            assertSame(finalTint, raw())
            assertSame(finalTint, cache(ChromeCacheSlot.TINT))
            orphans.forEach { it.accept() }
            direct.elements.accept()
            direct.membershipBarrier.accept()
        }

    @Test
    fun `inactive cleanup releases Chrome defaults tint and peers and restores their exact before images`() =
        withNativeSwitch { fixture ->
            UIManager.put(BACKGROUND, fixture.oldTint)
            val references = references()
            val direct = StudioChromeCapture(references).capture(input(null), null, listOf(Element()))
            val orphans = StudioChromeOrphans.capture(references, listOf(Element()), direct.elements.receipts)
            assertEquals(0, orphans.size)
            assertSame(fixture.oldTint, raw())
            assertSame(fixture.oldTint, cache(ChromeCacheSlot.TINT))
            assertSame(fixture.foreignColor, fixture.foreign.background)

            direct.membershipBarrier.apply()
            direct.elements.apply()
            orphans.forEach { it.apply() }

            assertFalse(fixture.direct.isBackgroundSet)
            assertFalse(fixture.orphan.isBackgroundSet)
            assertFalse(fixture.foreign.isBackgroundSet)
            assertSame(fixture.oldTint, fixture.outside.background)
            assertNull(raw())
            assertSame(Color.GRAY, UIManager.getColor(BACKGROUND))
            assertNull(cache(ChromeCacheSlot.TINT))
            assertNull(cache(ChromeCacheSlot.CURRENT))
            direct.elements.restore(emptyList())
            direct.membershipBarrier.restore(direct.elements.receipts)
            assertSame(fixture.oldTint, raw())
            assertSame(fixture.oldTint, cache(ChromeCacheSlot.TINT))
            assertSame(fixture.oldTint, fixture.direct.background)
            assertSame(fixture.oldTint, fixture.orphan.background)
            assertSame(fixture.foreignColor, fixture.foreign.background)
        }

    @Test
    fun `direct clear receipt excludes its peer from orphan cleanup`() =
        withNativeSwitch { fixture ->
            val references = references()
            val direct = StudioChromeSurface.clear(references, Element(scoped = true))
            val orphans = StudioChromeOrphans.capture(references, listOf(Element()), direct.receipts)
            val before = fixture.peers.map { it.writes }
            assertEquals(1, orphans.size)

            direct.apply()
            orphans.forEach { it.apply() }
            assertFalse(fixture.direct.isBackgroundSet)
            assertFalse(fixture.orphan.isBackgroundSet)
            assertEquals(before[0] + 1, fixture.direct.writes)
            assertEquals(before[1] + 1, fixture.orphan.writes)

            orphans.asReversed().forEach { it.restore(emptyList()) }
            direct.restore(orphans.flatMap { it.receipts })
            assertSame(fixture.oldTint, fixture.direct.background)
            assertSame(fixture.oldTint, fixture.orphan.background)
        }

    @Test
    fun `abort restores exact post switch peer defaults and cache before images`() =
        withNativeSwitch { fixture ->
            val references = references()
            val direct =
                StudioChromeCapture(
                    references,
                ).capture(input(AccentContext.External), Color.GREEN, listOf(Element(scoped = true)))
            val orphans = StudioChromeOrphans.capture(references, listOf(Element()), direct.elements.receipts)
            val caches = ChromeCacheSlot.entries.associateWith(::cache)
            val evidence = orphans.flatMap { it.receipts }.map { it.identity to it.before }
            direct.membershipBarrier.apply()
            direct.elements.apply()
            orphans.forEach { it.apply() }

            orphans.asReversed().forEach { it.restore(emptyList()) }
            direct.elements.restore(orphans.flatMap { it.receipts })
            direct.membershipBarrier.restore(direct.elements.receipts + orphans.flatMap { it.receipts })

            assertSame(fixture.oldTint, fixture.direct.background)
            assertSame(fixture.oldTint, fixture.orphan.background)
            assertSame(fixture.foreignColor, fixture.foreign.background)
            assertNull(raw())
            assertSame(Color.GRAY, UIManager.getColor(BACKGROUND))
            caches.forEach { (slot, color) -> assertSame(color, cache(slot)) }
            assertFalse(ExternalChromeOwnership.isOwned(AccentElementId.NAV_BAR))
            assertEquals(evidence, orphans.flatMap { it.receipts }.map { it.identity to it.before })
            assertTrue(orphans.flatMap { it.receipts }.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `overlapping old targets never give one physical orphan two writes`() =
        withNativeSwitch { fixture ->
            val orphans =
                StudioChromeOrphans.capture(
                    references(),
                    listOf(Element(), Element(scoped = true, id = AccentElementId.PANEL_BORDER)),
                    emptyList(),
                )
            val before = fixture.peers.map { it.writes }
            assertEquals(2, orphans.size)
            orphans.forEach { it.apply() }
            assertEquals(before[0] + 1, fixture.direct.writes)
            assertEquals(before[1] + 1, fixture.orphan.writes)
            assertEquals(before[2], fixture.foreign.writes)
            orphans.asReversed().forEach { it.restore(emptyList()) }
        }

    @Test
    fun `unavailable tint has no direct peer receipt and cannot hide old peers`() =
        withNativeSwitch { fixture ->
            val references = references()
            val direct =
                StudioChromeCapture(
                    references,
                ).capture(input(AccentContext.External), Color.GREEN, listOf(Element(available = false)))
            val orphans = StudioChromeOrphans.capture(references, listOf(Element()), direct.elements.receipts)
            assertEquals(2, orphans.size)
            orphans.forEach { it.apply() }
            assertFalse(fixture.direct.isBackgroundSet)
            assertFalse(fixture.orphan.isBackgroundSet)
            orphans.asReversed().forEach { it.restore(emptyList()) }
            direct.elements.restore(orphans.flatMap { it.receipts })
            direct.membershipBarrier.restore(direct.elements.receipts)
        }

    @Test
    fun `missing new stock has no direct peer receipt and cannot hide old peers`() =
        withNativeSwitch { fixture ->
            UIManager.getLookAndFeelDefaults()[BACKGROUND] = null
            val references = references()
            val direct =
                StudioChromeCapture(
                    references,
                ).capture(input(AccentContext.External), Color.GREEN, listOf(Element()))
            val orphans = StudioChromeOrphans.capture(references, listOf(Element()), direct.elements.receipts)
            assertEquals(2, orphans.size)
            orphans.forEach { it.apply() }
            assertFalse(fixture.direct.isBackgroundSet)
            assertFalse(fixture.orphan.isBackgroundSet)
            assertNull(raw())
            assertSame(fixture.oldTint, cache(ChromeCacheSlot.TINT))
            orphans.asReversed().forEach { it.restore(emptyList()) }
            direct.elements.restore(orphans.flatMap { it.receipts })
            direct.membershipBarrier.restore(direct.elements.receipts)
        }

    @Test
    fun `foreign raw replacement after orphan capture survives apply and abort`() =
        withNativeSwitch { fixture ->
            val cell = StudioChromeOrphans.capture(references(), listOf(Element(scoped = true)), emptyList()).single()
            val foreign = Color(fixture.oldTint.rgb)
            fixture.direct.background = foreign
            val before = fixture.direct.writes

            assertFailsWith<IllegalStateException> { cell.apply() }
            cell.restore(emptyList())
            assertEquals(before, fixture.direct.writes)
            assertSame(foreign, fixture.direct.background)
            assertEquals(RestoreStatus.FOREIGN, cell.receipts.single().status)
        }

    @Test
    fun `attachment change after orphan capture rejects the retained setter`() =
        withNativeSwitch { fixture ->
            val cell = StudioChromeOrphans.capture(references(), listOf(Element(scoped = true)), emptyList()).single()
            JPanel().add(fixture.direct)
            val before = fixture.direct.writes

            assertFailsWith<IllegalStateException> { cell.apply() }
            cell.restore(emptyList())
            assertEquals(before, fixture.direct.writes)
            assertSame(fixture.oldTint, fixture.direct.background)
        }

    @Test
    fun `native LAF replacement after orphan capture rejects the retained setter`() =
        withNativeSwitch { fixture ->
            val cell = StudioChromeOrphans.capture(references(), listOf(Element(scoped = true)), emptyList()).single()
            UIManager.setLookAndFeel(MetalLookAndFeel())
            val before = fixture.direct.writes

            assertFailsWith<IllegalStateException> { cell.apply() }
            cell.restore(emptyList())
            assertEquals(before, fixture.direct.writes)
            assertSame(fixture.oldTint, fixture.direct.background)
        }

    private fun withNativeSwitch(action: (Fixture) -> Unit) {
        SwingUtilities.invokeAndWait {
            val previousLaf = UIManager.getLookAndFeel()
            val previousRaw = raw()
            val previousCaches = ChromeCacheSlot.entries.associateWith(::cache)
            try {
                ExternalChromeOwnership.resetForTests()
                ChromeCacheSlot.entries.forEach { setCache(it, null) }
                UIManager.put(BACKGROUND, Color.BLUE)
                val direct = Peer().apply { background = null }
                val orphan = Peer().apply { background = null }
                val foreign = Peer().apply { background = null }
                val outside = JPanel()
                val root =
                    JPanel().apply {
                        add(TargetHost().apply { add(direct) })
                        add(orphan)
                        add(foreign)
                        add(outside)
                    }
                val window =
                    mockk<Window> {
                        every { isShowing } returns true
                        every { components } returns arrayOf(root)
                    }
                mockkStatic(Window::class)
                mockkObject(ConflictRegistry)
                every { Window.getWindows() } returns arrayOf(window)
                every { ConflictRegistry.getConflictFor(any()) } returns null
                val accepted =
                    StudioChromeCapture(
                        references(),
                    ).capture(input(AccentContext.Ayu(AyuVariant.DARK)), Color.ORANGE, listOf(Element()))
                accepted.membershipBarrier.apply()
                accepted.elements.apply()
                accepted.elements.accept()
                accepted.membershipBarrier.accept()
                val oldTint = direct.background
                assertSame(oldTint, orphan.background)
                assertSame(oldTint, cache(ChromeCacheSlot.TINT))

                UIManager.setLookAndFeel(MetalLookAndFeel())
                ChromeBaseColors.refresh()
                UIManager.put(BACKGROUND, null)
                UIManager.getLookAndFeelDefaults()[BACKGROUND] = Color.GRAY
                val foreignColor = Color(oldTint.rgb)
                foreign.background = foreignColor
                outside.background = oldTint
                action(Fixture(direct, orphan, foreign, outside, oldTint, foreignColor))
            } finally {
                unmockkAll()
                ExternalChromeOwnership.resetForTests()
                UIManager.setLookAndFeel(previousLaf)
                UIManager.put(BACKGROUND, previousRaw)
                previousCaches.forEach { (slot, color) -> setCache(slot, color) }
            }
        }
    }

    private fun references(): StudioAccentReferences =
        StudioAccentReferences("orphans", StudioProcess(42, Instant.EPOCH))

    private fun input(context: AccentContext?): StudioChromeInput =
        StudioChromeInput(
            context,
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

    private fun raw(): Any? = readRawUiDefault(UIManager.getDefaults(), BACKGROUND)

    private fun cache(slot: ChromeCacheSlot): Color? = ChromeBaseColors.prepare(slot, BACKGROUND, null).before

    private fun setCache(
        slot: ChromeCacheSlot,
        color: Color?,
    ) {
        val checkpoint = ChromeBaseColors.prepare(slot, BACKGROUND, color)
        assertTrue(checkpoint.apply())
        checkpoint.commit()
    }

    private class Fixture(
        val direct: Peer,
        val orphan: Peer,
        val foreign: Peer,
        val outside: JPanel,
        val oldTint: Color,
        val foreignColor: Color,
    ) {
        val peers: List<Peer> = listOf(direct, orphan, foreign)
    }

    private class Element(
        scoped: Boolean = false,
        available: Boolean = true,
        override val id: AccentElementId = AccentElementId.NAV_BAR,
    ) : AbstractChromeElement() {
        override val displayName: String = "Orphan Chrome fixture"
        override val isEnabled: Boolean = available
        override val backgroundKeys: List<String> = listOf(BACKGROUND)
        override val foregroundKeys: List<String> = emptyList()
        override val foregroundTextTarget: WcagForeground.TextTarget = WcagForeground.TextTarget.PRIMARY_TEXT
        override val peerTarget: ChromeTarget =
            if (scoped) {
                ChromeTarget.ByClassNameInside(
                    target = ClassFqn.require(Peer::class.java.name),
                    ancestor = ClassFqn.require(TargetHost::class.java.name),
                )
            } else {
                ChromeTarget.ByClassName(ClassFqn.require(Peer::class.java.name))
            }
    }

    private class TargetHost : JPanel()

    private class Peer : JPanel() {
        var writes: Int = 0

        override fun setBackground(color: Color?) {
            super.setBackground(color)
            writes++
        }
    }

    companion object {
        private const val BACKGROUND = "Ayu.test.studio.chrome.orphans"
    }
}
