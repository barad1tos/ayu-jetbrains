package dev.ayuislands.glow

import java.util.IdentityHashMap
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.border.Border

/** Capture one shared component plan while preserving every manager's own listener appearance. */
internal fun captureGlowFocus(
    holds: List<FocusRingManager.CallbackHold>,
    isAvailable: () -> Boolean = { true },
    transform: (GlowFocusListener, GlowFocusAppearance) -> GlowFocusAppearance,
): List<GlowFocusListener.Checkpoint> {
    check(SwingUtilities.isEventDispatchThread()) { "Glow focus capture must run on EDT" }
    check(isAvailable() && holds.all { it.isActive }) { "Glow focus holds are unavailable" }
    require(holds.distinct().size == holds.size) { "Duplicate Glow focus hold" }
    val peers =
        holds.flatMap { it.listeners }.associateWith { listener ->
            val before = listener.currentAppearance
            GlowFocusPeer(listener, before, transform(listener, before), listener.borderOwnership)
        }
    val groups = IdentityHashMap<JComponent, MutableList<GlowFocusPeer>>()
    for (peer in peers.values) groups.getOrPut(peer.listener.component) { mutableListOf() }.add(peer)
    val plans = IdentityHashMap<JComponent, GlowFocusBorderPlan>()
    for ((component, members: List<GlowFocusPeer>) in groups) {
        if (members.all { GlowFocusFields.matches(it.before, it.planned) }) continue
        val ordered = component.focusListeners.mapNotNull { registered -> members.find { it.listener === registered } }
        check(ordered.size == members.size) { "Glow focus registration changed before capture" }
        plans[component] = planFocusBorder(component.border, ordered)
    }
    val checkpoints = holds.flatMap { it.captureShared(peers, plans, isAvailable) }
    holds.forEach { it.selectBorderWriters(plans) }
    return checkpoints
}

/** Select only captured native identities; unknown border links remain opaque user state. */
internal fun planFocusBorder(
    observed: Border?,
    peers: List<GlowFocusPeer>,
): GlowFocusBorderPlan {
    require(peers.isNotEmpty())
    val enabled = peers.filter { it.planned.enabled }.ifEmpty { peers }
    val writer = enabled.find { it.ownership != null && it.ownership.installed === observed } ?: enabled.last()
    val canWrite = peers.any { it.ownership == null || it.ownership.installed === observed }
    var baseline = observed
    val remaining = peers.mapNotNull { it.ownership }.toMutableList()
    while (true) {
        val link = remaining.find { it.installed === baseline } ?: break
        remaining.remove(link)
        baseline = link.original
    }
    return GlowFocusBorderPlan(writer.listener, observed, baseline, canWrite)
}
