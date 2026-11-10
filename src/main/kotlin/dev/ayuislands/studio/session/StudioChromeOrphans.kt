package dev.ayuislands.studio.session

import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromePeerOverrides
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.elements.AbstractChromeElement
import dev.ayuislands.studio.persistence.PortableReceipt
import java.awt.Color
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Captures old tint peers left outside the new native writes; the caller owns their retained lifetime. */
internal object StudioChromeOrphans {
    fun capture(
        references: StudioAccentReferences,
        elements: List<AbstractChromeElement>,
        assigned: List<PortableReceipt>,
    ): List<StudioAccentCell> {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome orphan capture must run on EDT" }
        val writes = mutableListOf<StudioAccentCell>()
        for (element in elements) {
            val target = element.planClear().peer ?: continue
            val tints = element.stockKeys.mapNotNull { ChromeBaseColors.observeStock(it).previousTint }
            if (tints.isNotEmpty()) {
                for (component in LiveChromeRefresher.collect(target)) {
                    preparePeer(references, component, tints, assigned, writes)?.let(writes::add)
                }
            }
        }
        return writes.toList()
    }

    private fun preparePeer(
        references: StudioAccentReferences,
        component: JComponent,
        tints: List<Color>,
        assigned: List<PortableReceipt>,
        writes: List<StudioAccentCell>,
    ): StudioAccentCell? {
        val raw = if (component.isBackgroundSet) component.background else null
        if (tints.none { it === raw }) return null
        val native = ChromePeerOverrides.prepare(component, null)
        if (tints.none { it === native.before }) return null
        val write = StudioAccentCell.peer(references, native)
        val identity = write.receipts.single().identity
        if (StudioAccentReceipts.isBlocked(identity.resource, identity.field, assigned)) return null
        if (writes.any { StudioAccentReceipts.isBlocked(identity.resource, identity.field, it.receipts) }) return null
        return write
    }
}
