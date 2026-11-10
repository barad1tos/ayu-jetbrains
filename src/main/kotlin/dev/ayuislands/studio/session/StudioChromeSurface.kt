package dev.ayuislands.studio.session

import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeCacheSlot
import dev.ayuislands.accent.ChromePeerOverrides
import dev.ayuislands.accent.ChromeStockObservation
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.TintIntensity
import dev.ayuislands.accent.elements.AbstractChromeElement
import dev.ayuislands.studio.persistence.PortableReceipt
import java.awt.Color
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * One native Chrome surface retains defaults, cache cells, and exact peers together.
 * The compound Accent owner supplies preference/conflict gates, external membership, and lifecycle callbacks.
 */
internal class StudioChromeSurface private constructor(
    private val stock: List<ChromeStockObservation>,
    private val fields: List<StudioAccentWrite>,
    private val peers: List<Peer>,
    private val overlays: List<StudioAccentWrite>,
    val hasTint: Boolean,
) {
    private var phase = Phase.PREPARED

    val receipts: List<PortableReceipt>
        get() =
            buildList {
                fields.forEach { addAll(it.receipts) }
                peers.forEach { addAll(it.write.receipts) }
                overlays.forEach { addAll(it.receipts) }
            }

    fun apply() {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome application must run on EDT" }
        check(phase == Phase.PREPARED) { "Chrome application was already started" }
        phase = Phase.APPLYING
        check(stock.all(ChromeBaseColors::isStockCurrent)) { "Chrome stock changed before application" }
        fields.forEach { it.apply() }
        peers.forEach {
            it.write.apply()
            it.component.repaint()
        }
        overlays.forEach { it.apply() }
        phase = Phase.APPLIED
    }

    fun restore(blockedBy: List<PortableReceipt>) {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome restoration must run on EDT" }
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Chrome cannot be restored" }
        if (phase == Phase.RESTORED) return
        phase = Phase.RESTORING
        overlays.asReversed().forEach { it.restore(blockedBy) }
        peers.asReversed().forEach { it.write.restore(blockedBy) }
        fields.asReversed().forEach { it.restore(blockedBy) }
        phase = Phase.RESTORED
    }

    fun accept() {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome acceptance must run on EDT" }
        if (phase == Phase.ACCEPTED) return
        check(phase == Phase.APPLIED || phase == Phase.ACCEPTING) { "Chrome application has not completed" }
        phase = Phase.ACCEPTING
        fields.forEach { it.accept() }
        peers.forEach { it.write.accept() }
        overlays.forEach { it.accept() }
        phase = Phase.ACCEPTED
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTING, ACCEPTED }

    private data class Peer(
        val component: JComponent,
        val write: StudioAccentCell,
    )

    companion object {
        fun tint(
            references: StudioAccentReferences,
            element: AbstractChromeElement,
            color: Color,
            intensity: TintIntensity,
            canHandleEmpty: Boolean,
        ): StudioChromeSurface {
            check(SwingUtilities.isEventDispatchThread()) { "Chrome preparation must run on EDT" }
            if (!element.isAvailable) {
                return StudioChromeSurface(emptyList(), emptyList(), emptyList(), emptyList(), false)
            }
            requireDistinctKeys(element.stockKeys)
            val stock = element.stockKeys.map(ChromeBaseColors::observeStock)
            val colors = stock.mapNotNull { entry -> entry.stock?.let { entry.key to it } }.toMap()
            val plan = element.planTint(color, intensity, colors, canHandleEmpty)
            requireDistinctKeys(plan.backgrounds.keys, plan.overlays, plan.foregrounds.keys)
            val peers =
                plan.peer?.let { peer ->
                    preparePeers(references, LiveChromeRefresher.collect(peer.target), peer.color)
                }
            check(stock.all(ChromeBaseColors::isStockCurrent)) { "Chrome stock changed during capture" }
            val fields =
                buildList {
                    for (entry in stock) {
                        val tinted = plan.backgrounds[entry.key] ?: continue
                        if (entry.cached == null) {
                            add(cache(references, ChromeCacheSlot.STOCK, entry.key, entry.stock))
                            add(cache(references, ChromeCacheSlot.CURRENT, entry.key, entry.stock))
                        }
                        add(StudioAccentDefaults.prepareRaw(references, entry.key, tinted))
                        add(cache(references, ChromeCacheSlot.TINT, entry.key, tinted))
                    }
                    plan.overlays.forEach { add(StudioAccentDefaults.prepareRaw(references, it, null)) }
                    plan.foregrounds.forEach { (key, foreground) ->
                        add(StudioAccentDefaults.prepareRaw(references, key, foreground))
                    }
                }
            return StudioChromeSurface(stock, fields, peers.orEmpty(), emptyList(), plan.backgrounds.isNotEmpty())
        }

        /** Cleanup ignores apply-only capability gates, preserving the native v2 disable behavior. */
        fun clear(
            references: StudioAccentReferences,
            element: AbstractChromeElement,
        ): StudioChromeSurface {
            check(SwingUtilities.isEventDispatchThread()) { "Chrome preparation must run on EDT" }
            val plan = element.planClear()
            requireDistinctKeys(plan.backgrounds, plan.foregrounds, plan.overlays)
            val peers = plan.peer?.let { preparePeers(references, LiveChromeRefresher.collect(it), null) }.orEmpty()
            val fields =
                buildList {
                    plan.backgrounds.forEach { key ->
                        add(StudioAccentDefaults.prepareRaw(references, key, null))
                        add(cache(references, ChromeCacheSlot.TINT, key, null))
                    }
                    plan.foregrounds.forEach { add(StudioAccentDefaults.prepareRaw(references, it, null)) }
                }
            val overlays = plan.overlays.map { StudioAccentDefaults.prepareRaw(references, it, null) }
            return StudioChromeSurface(emptyList(), fields, peers, overlays, false)
        }

        private fun preparePeers(
            references: StudioAccentReferences,
            components: List<JComponent>,
            color: Color?,
        ): List<Peer> =
            components.map { component ->
                Peer(component, StudioAccentCell.peer(references, ChromePeerOverrides.prepare(component, color)))
            }

        private fun cache(
            references: StudioAccentReferences,
            slot: ChromeCacheSlot,
            key: String,
            color: Color?,
        ): StudioAccentCell = StudioAccentCell.cache(references, ChromeBaseColors.prepare(slot, key, color))

        private fun requireDistinctKeys(vararg groups: Collection<String>) {
            val seen = mutableSetOf<String>()
            groups.forEach { keys ->
                require(keys.all(seen::add)) { "Chrome surface contains overlapping UI fields" }
            }
        }
    }
}
