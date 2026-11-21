package dev.ayuislands.glow

import java.util.IdentityHashMap
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.border.Border

internal data class GlowFocusBorderEdit(
    val observed: Border?,
    val baseline: Border?,
)

/** Capture one native border edit per component, in its existing Swing listener order. */
internal fun captureGlowFocus(
    holds: List<FocusRingManager.CallbackHold>,
    transform: (FocusRingManager.CallbackHold, GlowFocusAppearance) -> GlowFocusAppearance,
): List<GlowFocusListener.Checkpoint> {
    check(SwingUtilities.isEventDispatchThread()) { "Glow focus capture must run on EDT" }
    require(holds.distinct().size == holds.size) { "Duplicate Glow focus hold" }
    check(holds.all { it.isActive }) { "Glow focus holds are unavailable" }
    val byComponent = IdentityHashMap<JComponent, MutableList<FocusPeer>>()
    val orderedGroups = mutableListOf<List<FocusPeer>>()
    for (hold in holds) {
        for (listener in hold.listeners()) {
            val group = byComponent.getOrPut(listener.component) { mutableListOf<FocusPeer>().also(orderedGroups::add) }
            val before = listener.currentAppearance
            group.add(FocusPeer(hold, listener, before, transform(hold, before)))
        }
    }
    val groups = orderedGroups.filter { peers -> peers.any { !it.before.matches(it.planned) } }.map(::planGroup)
    val checkpoints = groups.flatMap(SharedGlowFocus::capture)
    groups.forEach(SharedGlowFocus::enroll)
    return checkpoints
}

internal data class FocusPeer(
    val hold: FocusRingManager.CallbackHold,
    val listener: GlowFocusListener,
    val before: GlowFocusAppearance,
    val planned: GlowFocusAppearance,
)

private fun planGroup(peers: List<FocusPeer>): SharedGlowFocus {
    val component = peers.first().listener.component
    val registrations = component.focusListeners.filterIsInstance<GlowFocusListener>()
    val known = peers.associateBy(FocusPeer::listener)
    val ordered = registrations.mapNotNull(known::get)
    check(ordered.size == peers.size && ordered.all { it.hold.canCapture(it.listener) }) {
        "Glow focus registrations changed before capture"
    }
    val observed = component.border
    val owner = ordered.find { it.listener.ownedBorder?.installed === observed && observed != null }
    val hasPriorOwner = ordered.any { it.listener.ownedBorder != null }
    val hasOtherOwner = owner == null && (observed is GlowFocusBorder || hasPriorOwner)
    val baseline = traceBorder(observed, ordered.mapNotNull { it.listener.ownedBorder }).baseline
    val canWrite = registrations.size == ordered.size && !hasOtherOwner
    val border = if (canWrite) GlowFocusBorderEdit(observed, baseline) else null
    return SharedGlowFocus(component, ordered, border, owner)
}

/** Only unwrap exact known ownership links. A foreign border is never interpreted. */
private data class FocusBorderChain(
    val baseline: Border?,
    val links: Set<GlowFocusListener.Ownership>,
)

private fun traceBorder(
    observed: Border?,
    links: List<GlowFocusListener.Ownership>,
): FocusBorderChain {
    var baseline = observed
    val reachable =
        buildSet {
            while (true) {
                val link = links.find { it.installed === baseline } ?: break
                if (!add(link)) break
                baseline = link.baseline
            }
        }
    return FocusBorderChain(baseline, reachable)
}

/** Reconcile once, after the last overlapping hold has requested release. */
internal class SharedGlowFocus(
    private val component: JComponent,
    private val peers: List<FocusPeer>,
    private val border: GlowFocusBorderEdit?,
    private val formerOwner: FocusPeer?,
) {
    private val pending = peers.mapTo(mutableSetOf(), FocusPeer::listener)
    var isSettled = false
        private set

    fun capture(): List<GlowFocusListener.Checkpoint> {
        val enabled = peers.filter { it.planned.enabled }
        val writer =
            if (border != null) {
                formerOwner?.takeIf { it.planned.enabled } ?: enabled.lastOrNull() ?: formerOwner
            } else {
                null
            }
        return peers.mapNotNull { peer ->
            peer.listener.capturePlanned(peer.planned, border.takeIf { peer === writer }) {
                peer.hold.isActive && peer.hold.isRegistered(peer.listener)
            }
        }
    }

    fun enroll() {
        peers.forEach { it.hold.share(it.listener, this) }
    }

    fun release(listener: GlowFocusListener) {
        if (isSettled || !pending.remove(listener) || pending.isNotEmpty()) return
        try {
            reconcile()
        } finally {
            abandon()
        }
    }

    fun abandon() {
        if (isSettled) return
        isSettled = true
        pending.clear()
        peers.forEach { it.hold.finishRelease() }
    }

    private fun reconcile() {
        val edit = border ?: return
        val registrations = component.focusListeners.filterIsInstance<GlowFocusListener>()
        if (!matchesRegistrations(registrations)) return
        val observed = component.border
        val owner = registrations.find { it.ownedBorder?.installed === observed && observed != null }
        val baselineIsAvailable =
            observed === edit.baseline &&
                (observed === edit.observed || peers.any { it.listener.returnedOwnedBaseline })
        if (owner == null && !baselineIsAvailable) return
        val enabled = if (component.hasFocus()) registrations.filter { it.currentAppearance.enabled } else emptyList()
        val writer = if (owner in enabled) null else enabled.lastOrNull() ?: owner
        try {
            writer?.reconcileSharedBorder(observed, edit.baseline, enabled.isNotEmpty())
        } finally {
            retireObsoleteBorders(registrations, edit.baseline)
        }
    }

    private fun retireObsoleteBorders(
        registrations: List<GlowFocusListener>,
        baseline: Border?,
    ) {
        val observed = component.border
        val chain = traceBorder(observed, registrations.mapNotNull(GlowFocusListener::ownedBorder))
        if (chain.links.isEmpty() && observed !== baseline) return
        for (listener in registrations) {
            val owner = listener.ownedBorder ?: continue
            if (owner !in chain.links) listener.forgetBorder(owner)
        }
    }

    private fun matchesRegistrations(registrations: List<GlowFocusListener>): Boolean =
        registrations == peers.filter { it.hold.isRegistered(it.listener) }.map(FocusPeer::listener)
}
