package dev.ayuislands.studio.session

import com.intellij.ui.JBColor
import dev.ayuislands.accent.AccentUiInput
import dev.ayuislands.accent.AccentUiKeys
import dev.ayuislands.accent.UiDefaultsOwnership
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.UiOverrideValue
import dev.ayuislands.accent.accentUiPalette
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.accent.resolveUnderlineArc
import dev.ayuislands.accent.uiDefaultsOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Ordered native defaults retain their provenance until the outer transaction accepts them. */
internal class StudioAccentFields private constructor(
    private val fields: List<StudioAccentWrite>,
    private val sourceIsCurrent: () -> Boolean,
) : StudioAccentWrite {
    private var phase = Phase.PREPARED

    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(fields.flatMap { it.receipts })

    override fun apply() {
        check(SwingUtilities.isEventDispatchThread()) { "Accent fields must be applied on EDT" }
        check(phase == Phase.PREPARED) { "Accent field application was already started" }
        phase = Phase.APPLYING
        check(sourceIsCurrent()) { "Accent field source changed before application" }
        fields.forEach { it.apply() }
        phase = Phase.APPLIED
    }

    override fun restore(blockedBy: List<PortableReceipt>) {
        check(SwingUtilities.isEventDispatchThread()) { "Accent fields must be restored on EDT" }
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Accent fields cannot be restored" }
        if (phase == Phase.RESTORED) return
        phase = Phase.RESTORING
        var failure: Throwable? = null
        for (field in fields.asReversed()) {
            val error = runCatching { field.restore(blockedBy) }.exceptionOrNull()
            if (error is VirtualMachineError) throw error
            if (error != null) failure = combineUiFailures(failure, error)
        }
        failure?.let { throw it }
        phase = Phase.RESTORED
    }

    override fun accept() {
        check(SwingUtilities.isEventDispatchThread()) { "Accent fields must be accepted on EDT" }
        if (phase == Phase.ACCEPTED) return
        check(phase == Phase.APPLIED || phase == Phase.ACCEPTING) { "Accent field application has not completed" }
        phase = Phase.ACCEPTING
        fields.forEach { it.accept() }
        phase = Phase.ACCEPTED
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTING, ACCEPTED }

    companion object {
        /** Composes already captured owner surfaces without admitting another native write. */
        fun captured(
            fields: List<StudioAccentWrite>,
            sourceIsCurrent: () -> Boolean,
        ): StudioAccentFields = StudioAccentFields(fields.toList(), sourceIsCurrent)

        fun release(
            references: StudioAccentReferences,
            owner: UiDefaultsOwnership = uiDefaultsOwner,
        ): StudioAccentFields {
            check(SwingUtilities.isEventDispatchThread()) { "Accent release must be captured on EDT" }
            val target = UiDefaultsTarget()
            val keys = AccentUiKeys.colors + listOf(AccentUiKeys.TAB_HEIGHT, AccentUiKeys.TAB_ARC)
            val fields = keys.map { key -> StudioAccentDefaults.prepare(references, owner, key, null, target) }
            return StudioAccentFields(fields, target::isCurrent)
        }

        fun colors(
            references: StudioAccentReferences,
            settings: StudioAccentUiSettings,
            accent: Color,
            owner: UiDefaultsOwnership = uiDefaultsOwner,
        ): StudioAccentFields {
            check(SwingUtilities.isEventDispatchThread()) { "Accent colors must be captured on EDT" }
            val target = UiDefaultsTarget()
            val palette =
                accentUiPalette(
                    AccentUiInput(
                        accent,
                        settings.context?.variant?.let { Color.decode(it.neutralGray) },
                        settings.tabMode,
                        JBColor.WHITE.rgb,
                        settings.policy,
                    ),
                )
            check(target.isCurrent()) { "Accent color target changed during observation" }
            val fields =
                (AccentUiKeys.colors - palette.keys).map { key ->
                    StudioAccentDefaults.prepare(references, owner, key, null, target)
                } +
                    palette.map { (key, rgba) ->
                        val color = rgba?.let { JBColor(Color(it, true), Color(it, true)) }
                        StudioAccentDefaults.prepare(references, owner, key, UiOverrideValue(color), target)
                    }
            return StudioAccentFields(fields, target::isCurrent)
        }

        fun tabs(
            references: StudioAccentReferences,
            settings: StudioAccentUiSettings,
            owner: UiDefaultsOwnership = uiDefaultsOwner,
        ): StudioAccentFields {
            check(SwingUtilities.isEventDispatchThread()) { "Accent tabs must be captured on EDT" }
            val target = UiDefaultsTarget()
            return when (val geometry = settings.geometry) {
                StudioTabGeometry.Unchanged -> {
                    StudioAccentFields(emptyList()) { true }
                }

                StudioTabGeometry.Release -> {
                    val fields =
                        listOf(AccentUiKeys.TAB_HEIGHT, AccentUiKeys.TAB_ARC).map { key ->
                            StudioAccentDefaults.prepare(references, owner, key, null, target)
                        }
                    StudioAccentFields(fields, target::isCurrent)
                }

                is StudioTabGeometry.Set -> {
                    // Resolve native providers before capturing the raw cells that will be overwritten.
                    val arc = resolveUnderlineArc(UIManager.getInt(ISLAND_ARC))
                    check(target.isCurrent()) { "Accent tab target changed during observation" }
                    val local = readRawUiDefault(target.defaults, ISLAND_ARC)
                    val fallback = readRawUiDefault(UIManager.getLookAndFeelDefaults(), ISLAND_ARC)
                    val fields =
                        listOf(
                            AccentUiKeys.TAB_HEIGHT to geometry.height,
                            AccentUiKeys.TAB_ARC to arc,
                        ).map { (key, value) ->
                            StudioAccentDefaults.prepare(references, owner, key, UiOverrideValue(value), target)
                        }
                    StudioAccentFields(fields) {
                        target.isCurrent() &&
                            readRawUiDefault(target.defaults, ISLAND_ARC) === local &&
                            readRawUiDefault(UIManager.getLookAndFeelDefaults(), ISLAND_ARC) === fallback
                    }
                }
            }
        }

        private const val ISLAND_ARC = "Island.arc"
    }
}
