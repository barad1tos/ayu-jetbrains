package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.Scheme
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import java.util.Collections
import javax.swing.SwingUtilities

/** Captured projections share one native base; omission does not prove local absence after loading. */
internal sealed interface SchemePersistenceResult {
    class Ready(
        val record: SchemeEntryRecord,
        val before: SchemeStoredCell,
        val plannedWithCapturedBase: SchemeStoredCell,
        val dependencies: SchemePersistenceContext,
    ) : SchemePersistenceResult

    class Unavailable(
        val reason: String,
    ) : SchemePersistenceResult
}

/** Retained native dependencies are evidence for this capture, not replacement original state. */
internal class SchemePersistenceContext(
    val target: SchemeTarget,
    val resource: AyuSchemeResource?,
    val original: AbstractColorsScheme?,
    val originalName: String?,
    val baseCell: SchemeCellFacts,
    val key: SchemePersistenceKey,
    val links: List<SchemePersistenceLink>,
    val comparisonBase: SchemeCellFacts = baseCell,
) {
    val base: AbstractColorsScheme? = links.getOrNull(1)?.scheme
}

internal class SchemePersistenceKey(
    val entry: SchemeEntry,
    val fallback: SchemeEntry?,
    val fallbackName: String?,
)

internal class SchemePersistenceLink(
    val scheme: AbstractColorsScheme,
    val parent: EditorColorsScheme?,
    val cell: SchemeCellFacts,
    val colorKey: ColorKey?,
)

/** Observes supported native state on EDT without serializing, adopting, or writing a scheme. */
internal object SchemePersistenceCapture {
    fun capture(
        target: SchemeTarget,
        record: SchemeEntryRecord,
        resource: AyuSchemeResource? = null,
    ): SchemePersistenceResult {
        checkEdt()
        if (!isQualified(target.scheme, resource) || resource?.isCurrent() == false) {
            return SchemePersistenceResult.Unavailable("The native scheme or canonical resource is unqualified")
        }
        if (!target.isSelected()) return SchemePersistenceResult.Unavailable("The native target selection changed")
        if (!supportsEntry(record.entry)) {
            return SchemePersistenceResult.Unavailable("The entry has unsupported native loading semantics")
        }
        val local =
            captureLink(target.scheme, record.entry)
                ?: return SchemePersistenceResult.Unavailable("The local cell is unavailable")
        if (!matchesRecord(
                target,
                record,
                local.cell,
            )
        ) {
            return SchemePersistenceResult.Unavailable("The original native record changed")
        }
        val key = captureKey(record.entry, local.colorKey)
        val originalName = target.scheme.metaProperties.getProperty(AbstractColorsScheme.META_INFO_ORIGINAL)
        val original = target.scheme.original
        val selectedBase = selectBase(target, original)
        val base = selectedBase as? AbstractColorsScheme
        if (selectedBase != null &&
            base == null
        ) {
            return SchemePersistenceResult.Unavailable("The native persistence base is unqualified")
        }
        val links =
            captureChain(local, base, key.entry, resource)
                ?: return SchemePersistenceResult.Unavailable("The base chain is unqualified or cyclic")
        val baseCell =
            captureDirectCell(base, key.entry)
                ?: return SchemePersistenceResult.Unavailable("The direct base cell is unavailable")
        val expectedBase =
            links.drop(1).firstOrNull { !SchemeAdmission.isAbsent(it.cell) }?.cell
                ?: checkNotNull(captureDirectCell(null, key.entry))
        if (!SchemeAdmission.matchesCell(
                expectedBase,
                baseCell,
            )
        ) {
            return SchemePersistenceResult.Unavailable("The direct base observation changed")
        }
        val context =
            SchemePersistenceContext(
                target,
                resource,
                original,
                originalName,
                baseCell,
                key,
                links,
                captureComparisonBase(baseCell),
            )
        return projectRecord(record, context)
    }

    /** Valid only before effects; this check never replaces either retained projection. */
    fun isCurrent(capture: SchemePersistenceResult.Ready): Boolean {
        checkEdt()
        val context = capture.dependencies
        if (context.resource?.isCurrent() == false || !context.target.isSelected()) return false
        if (!isBaseCurrent(context) || !isPlannedCurrent(capture.record.planned.cell)) return false
        val local = context.links.first()
        if (!isLinkCurrent(local, context.key.entry)) return false
        return matchesRecord(context.target, capture.record, local.cell) &&
            context.resource?.isCurrent() != false &&
            context.target.isSelected()
    }

    /** Projects a captured rollback cell against the retained base without replacing the original capture. */
    fun projectCell(
        capture: SchemePersistenceResult.Ready,
        cell: SchemeCellFacts,
    ): SchemeStoredCell? {
        checkEdt()
        val context = capture.dependencies
        if (!isBaseCurrent(context) || !isCellSupported(cell) || !isPlannedCurrent(cell)) return null
        val result =
            SchemePersistence.project(cell, context.comparisonBase, context.baseCell, context.key.fallbackName)
        return result.takeIf { isBaseCurrent(context) }
    }

    private fun isBaseCurrent(context: SchemePersistenceContext): Boolean {
        if (context.resource?.isCurrent() == false || !context.target.canRestore()) return false
        if (context.target.scheme.original !== context.original ||
            context.target.scheme.metaProperties
                .getProperty(AbstractColorsScheme.META_INFO_ORIGINAL) !=
            context.originalName
        ) {
            return false
        }
        if (selectBase(context.target, context.original) !== context.base || !isKeyCurrent(context.key)) return false
        if (!context.links.drop(1).all { isLinkCurrent(it, context.key.entry) }) return false
        val direct = captureDirectCell(context.base, context.key.entry) ?: return false
        return SchemeAdmission.matchesCell(context.baseCell, direct) &&
            SchemeAdmission.matchesCell(context.comparisonBase, captureComparisonBase(direct))
    }

    private fun selectBase(
        target: SchemeTarget,
        original: AbstractColorsScheme?,
    ): EditorColorsScheme? =
        if (target.scheme.name.startsWith(Scheme.EDITABLE_COPY_PREFIX) &&
            original != null
        ) {
            original
        } else {
            target.scheme.parentScheme
        }

    private fun captureChain(
        local: SchemePersistenceLink,
        base: AbstractColorsScheme?,
        entry: SchemeEntry,
        resource: AyuSchemeResource?,
    ): List<SchemePersistenceLink>? {
        val links = mutableListOf(local)
        var candidate: EditorColorsScheme? = base
        while (candidate != null) {
            val scheme = candidate as? AbstractColorsScheme ?: return null
            if (links.any { it.scheme === scheme } || !isQualified(scheme, resource)) return null
            val link = captureLink(scheme, entry) ?: return null
            links += link
            candidate = link.parent
        }
        return Collections.unmodifiableList(links)
    }

    private fun captureLink(
        scheme: AbstractColorsScheme,
        entry: SchemeEntry,
    ): SchemePersistenceLink? {
        val parent = scheme.parentScheme
        return when (entry) {
            is SchemeEntry.ColorEntry -> {
                val colors = scheme.directlyDefinedColors
                val stored = colors.entries.firstOrNull { it.key.externalName == entry.key.externalName }
                val cell = SchemeCellFacts.Color(captureColorFacts(stored?.value))
                SchemePersistenceLink(scheme, parent, cell, stored?.key)
            }

            is SchemeEntry.AttributesEntry -> {
                val cell = captureSchemeCell(entry, scheme.directlyDefinedAttributes, emptyMap()) ?: return null
                SchemePersistenceLink(scheme, parent, cell, null)
            }
        }
    }

    private fun captureDirectCell(
        scheme: AbstractColorsScheme?,
        entry: SchemeEntry,
    ): SchemeCellFacts? =
        when (entry) {
            is SchemeEntry.ColorEntry -> {
                SchemeCellFacts.Color(captureColorFacts(scheme?.getDirectlyDefinedColor(entry.key)))
            }

            is SchemeEntry.AttributesEntry -> {
                val attributes = scheme?.getDirectlyDefinedAttributes(entry.key)
                val values = attributes?.let { mapOf(entry.key.externalName to it) }.orEmpty()
                captureSchemeCell(entry, values, emptyMap())
            }
        }

    private fun captureComparisonBase(cell: SchemeCellFacts): SchemeCellFacts =
        if (cell == SchemeCellFacts.InheritedAttributes) {
            val marker = captureAttributeFacts(AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            check(marker is SchemeAttributeFacts.Native) { "Native inherited attributes are unqualified" }
            SchemeCellFacts.Attributes(marker)
        } else {
            cell
        }

    private fun captureKey(
        entry: SchemeEntry,
        storedColorKey: ColorKey?,
    ): SchemePersistenceKey =
        when (entry) {
            is SchemeEntry.ColorEntry -> {
                val key = storedColorKey ?: entry.key
                val fallback = key.fallbackColorKey
                SchemePersistenceKey(
                    SchemeEntry.ColorEntry(key),
                    fallback?.let(SchemeEntry::ColorEntry),
                    fallback?.externalName,
                )
            }

            is SchemeEntry.AttributesEntry -> {
                val key = TextAttributesKey.find(entry.key.externalName)
                val fallback = key.fallbackAttributeKey
                SchemePersistenceKey(
                    SchemeEntry.AttributesEntry(key),
                    fallback?.let(SchemeEntry::AttributesEntry),
                    fallback?.externalName,
                )
            }
        }

    private fun isKeyCurrent(binding: SchemePersistenceKey): Boolean =
        when (val entry = binding.entry) {
            is SchemeEntry.ColorEntry -> {
                val fallback = (binding.fallback as? SchemeEntry.ColorEntry)?.key
                entry.key.fallbackColorKey === fallback && fallback?.externalName == binding.fallbackName
            }

            is SchemeEntry.AttributesEntry -> {
                val fallback = (binding.fallback as? SchemeEntry.AttributesEntry)?.key
                TextAttributesKey.find(entry.key.externalName) === entry.key &&
                    entry.key.fallbackAttributeKey === fallback &&
                    fallback?.externalName == binding.fallbackName
            }
        }

    private fun isLinkCurrent(
        link: SchemePersistenceLink,
        entry: SchemeEntry,
    ): Boolean {
        val observed = captureLink(link.scheme, entry) ?: return false
        return link.parent === observed.parent &&
            link.colorKey === observed.colorKey &&
            SchemeAdmission.matchesCell(link.cell, observed.cell)
    }

    private fun isPlannedCurrent(cell: SchemeCellFacts): Boolean {
        if (cell !is SchemeCellFacts.Attributes || cell.value == null) return true
        val attributes = cell.value.identity as? TextAttributes ?: return false
        val observed = captureAttributeFacts(attributes) as? SchemeAttributeFacts.Native ?: return false
        return SchemeAdmission.matchesCell(cell, SchemeCellFacts.Attributes(observed))
    }

    private fun matchesRecord(
        target: SchemeTarget,
        record: SchemeEntryRecord,
        local: SchemeCellFacts,
    ): Boolean =
        record.before.parent === target.parent &&
            record.planned.parent === target.parent &&
            record.before.metadataTable === target.scheme.metaProperties &&
            SchemeAdmission.matchesCell(record.before.cell, local)

    private fun isQualified(
        scheme: AbstractColorsScheme,
        resource: AyuSchemeResource?,
    ): Boolean =
        scheme.javaClass == EditorColorsSchemeImpl::class.java ||
            scheme.javaClass == DefaultColorsScheme::class.java ||
            resource?.scheme === scheme

    private fun supportsEntry(entry: SchemeEntry): Boolean =
        when (entry) {
            is SchemeEntry.ColorEntry -> entry.key.externalName != "BACKGROUND"
            is SchemeEntry.AttributesEntry -> !TextAttributesKey.isTemp(entry.key)
        }

    private fun projectRecord(
        record: SchemeEntryRecord,
        context: SchemePersistenceContext,
    ): SchemePersistenceResult {
        if (!context.links.all { isCellSupported(it.cell) } || !isCellSupported(record.planned.cell)) {
            return SchemePersistenceResult.Unavailable("An opaque cell cannot establish native persistence")
        }
        val base = context.comparisonBase
        val directBase = context.baseCell
        val fallback = context.key.fallbackName
        val before =
            SchemePersistence.project(record.before.cell, base, directBase, fallback)
                ?: return SchemePersistenceResult.Unavailable("The original projection is unknown")
        val planned =
            SchemePersistence.project(record.planned.cell, base, directBase, fallback)
                ?: return SchemePersistenceResult.Unavailable(
                    "The planned projection is unknown: entry=${context.key.entry} " +
                        "local=${record.planned.cell.javaClass.simpleName} base=${base.javaClass.simpleName}",
                )
        val ready = SchemePersistenceResult.Ready(record, before, planned, context)
        return if (isCurrent(
                ready,
            )
        ) {
            ready
        } else {
            SchemePersistenceResult.Unavailable("The native dependencies changed during capture")
        }
    }

    private fun isCellSupported(cell: SchemeCellFacts): Boolean =
        when (cell) {
            is SchemeCellFacts.Color -> {
                cell.value !is SchemeColorFacts.Opaque
            }

            is SchemeCellFacts.Attributes -> {
                val fields = cell.value?.fields
                fields == null ||
                    listOf(fields.foreground, fields.background, fields.effectColor, fields.errorStripe)
                        .all { it == null || it is SchemeColorFacts.Plain }
            }

            SchemeCellFacts.InheritedAttributes -> {
                true
            }

            SchemeCellFacts.Unavailable -> {
                false
            }
        }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Native persistence capture must run on EDT" }
    }
}
