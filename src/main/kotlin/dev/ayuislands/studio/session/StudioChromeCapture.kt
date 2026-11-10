package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.ExternalChromeOwnership
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.elements.AbstractChromeElement
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.persistence.PortableReceipt
import java.awt.Color
import javax.swing.SwingUtilities

/** Captures the supplied extension census after theme routing; it never reads persisted settings. */
internal class StudioChromeCapture(
    private val references: StudioAccentReferences,
) {
    fun capture(
        input: StudioChromeInput,
        accent: Color?,
        elements: List<AccentElement>,
    ): StudioChromeBatch {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome capture must run on EDT" }
        require(input.entitlement != LicenseEntitlement.UNKNOWN) { "Chrome entitlement is unconfirmed" }
        val chrome = elements.filter { it.id.group == AccentGroup.CHROME }
        val ids = chrome.map { it.id }.toSet()
        require(ids.size == chrome.size) { "Duplicate Chrome element" }
        val entries = chrome.map { captureElement(input, accent, it) }.toMutableList()
        val membershipsFirst = input.context is AccentContext.Ayu
        if (membershipsFirst) {
            AccentElementId.entries.filter { it.group == AccentGroup.CHROME && it !in ids }.forEach { id ->
                membership(id, false, null)?.let { entries += StudioChromeEntry(null, it) }
            }
        }
        requireDistinctFields(entries)
        val barrier = if (membershipsFirst) entries.mapNotNull { it.membership } else emptyList()
        val surfaces = if (membershipsFirst) entries.map { it.copy(membership = null) } else entries.toList()
        return StudioChromeBatch(
            // Each native membership retains its own captured target and identity checks.
            StudioAccentFields.captured(barrier) { true },
            StudioChromeElements(surfaces),
        )
    }

    private fun captureElement(
        input: StudioChromeInput,
        accent: Color?,
        element: AccentElement,
    ): StudioChromeEntry {
        val owned = ExternalChromeOwnership.isOwned(element.id)
        val action =
            StudioChromePolicy.action(
                input,
                element.id,
                ConflictRegistry.getConflictFor(element.id) != null,
                owned,
            )
        val surface =
            when (action) {
                StudioChromeAction.TINT -> {
                    StudioChromeSurface.tint(
                        references,
                        requireNotNull(element as? AbstractChromeElement),
                        checkNotNull(accent) { "Chrome Accent handoff is unconfirmed" },
                        input.settings.intensity,
                        canHandleEmpty = input.context is AccentContext.Ayu,
                    )
                }

                StudioChromeAction.CLEAR -> {
                    StudioChromeSurface.clear(references, requireNotNull(element as? AbstractChromeElement))
                }

                StudioChromeAction.SKIP -> {
                    null
                }
            }
        val plannedOwned =
            when {
                input.context is AccentContext.Ayu || action == StudioChromeAction.CLEAR -> false
                surface?.hasTint == true -> true
                else -> owned
            }
        return StudioChromeEntry(surface, membership(element.id, plannedOwned, surface))
    }

    private fun membership(
        id: AccentElementId,
        owned: Boolean,
        surface: StudioChromeSurface?,
    ): StudioAccentCell? =
        ExternalChromeOwnership.prepare(id, owned)?.let {
            StudioAccentCell.membership(references, it, surface?.receipts.orEmpty())
        }

    private fun requireDistinctFields(entries: List<StudioChromeEntry>) {
        val seen = mutableListOf<PortableReceipt>()
        for (entry in entries) {
            val receipts = entry.surface?.receipts.orEmpty()
            require(receipts.none { StudioAccentReceipts.isBlocked(it.identity.resource, it.identity.field, seen) }) {
                "Chrome elements contain overlapping native fields"
            }
            seen += receipts
        }
    }
}
