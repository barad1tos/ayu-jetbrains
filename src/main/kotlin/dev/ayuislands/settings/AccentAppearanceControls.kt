package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.studio.model.SystemAccentPolicy
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList

/** Native Theme/System and rotation rows. Callers own rendering guards and all completed edits. */
internal class AccentAppearanceControls(
    private val onEdit: (AccentPanelEvent) -> Unit,
) {
    val shared =
        AccentSharedToggle.entries.associateWith { target ->
            JBCheckBox(target.title).apply {
                addActionListener { if (isEnabled) onEdit(AccentPanelEvent.SharedToggle(target, isSelected)) }
            }
        }
    private val follow = JBCheckBox("Follow system accent color")
    private val policy = accentChoice(SystemAccentPolicy.entries, ::policyLabel)
    private val night = accentChoice(listOf("Mirage", "Dark")) { it }
    private val nightRaw = JLabel()
    private val rule = JLabel()
    private val nightVisible = AtomicBooleanProperty(false)
    private val systemVisible = AtomicBooleanProperty(false)
    private val followVisible = AtomicBooleanProperty(false)
    private val rotationEnabled = JBCheckBox("Enable accent rotation")
    private val rotationMode =
        accentChoice(AccentRotationMode.entries) {
            if (it == AccentRotationMode.PRESET) "Preset cycle" else "Random color"
        }
    private val rotationInterval = accentChoice(INTERVALS) { if (it == 1) "1 hour" else "$it hours" }
    private val rotationRaw = JLabel()
    private val rotationVisible = AtomicBooleanProperty(false)
    private val paused = JLabel("Paused: using system accent")
    private var context: AccentPanelContext? = null

    init {
        follow.addActionListener { if (follow.isEnabled) onEdit(AccentPanelEvent.FollowSystem(follow.isSelected)) }
        policy.addActionListener {
            (policy.selectedItem as? SystemAccentPolicy)
                ?.takeIf { policy.isEnabled }
                ?.let { onEdit(AccentPanelEvent.SystemPolicy(it)) }
        }
        night.addActionListener {
            val selected = night.selectedItem as? String
            val observed = context
            if (night.isEnabled && selected != null && observed != null) {
                val suffix = if (observed.isIslandsUi) " (Islands UI)" else ""
                onEdit(AccentPanelEvent.NightTheme("Ayu $selected$suffix"))
            }
        }
        rotationEnabled.addActionListener {
            if (rotationEnabled.isEnabled) onEdit(AccentPanelEvent.RotationEnabled(rotationEnabled.isSelected))
        }
        rotationMode.addActionListener {
            (rotationMode.selectedItem as? AccentRotationMode)
                ?.takeIf { rotationMode.isEnabled }
                ?.let { onEdit(AccentPanelEvent.RotationMode(it)) }
        }
        rotationInterval.addActionListener {
            (rotationInterval.selectedItem as? Int)
                ?.takeIf { rotationInterval.isEnabled }
                ?.let { onEdit(AccentPanelEvent.RotationInterval(it)) }
        }
    }

    fun buildSystem(panel: Panel) {
        panel.row { comment("Inherit appearance and accent color from the system.") }
        panel.row { cell(shared.getValue(AccentSharedToggle.FOLLOW_SYSTEM_APPEARANCE)) }.visibleIf(systemVisible)
        panel.row("Night theme:") { cell(night) }.visibleIf(nightVisible)
        panel.row { cell(nightRaw).resizableColumn().align(Align.FILL) }.visibleIf(nightVisible)
        panel.row { cell(follow) }.visibleIf(followVisible)
        panel.row { cell(rule).resizableColumn().align(Align.FILL) }.visibleIf(followVisible)
        panel.row { link("Change shared rule") { policy.requestFocusInWindow() } }.visibleIf(followVisible)
        panel.row("System accent rule:") { cell(policy) }.visibleIf(followVisible)
    }

    fun buildRotation(panel: Panel) {
        panel.row { comment("Automatically change your accent color on a schedule.") }
        panel.row { cell(rotationEnabled) }
        panel.row { cell(paused) }
        panel.row("Mode:") { cell(rotationMode) }.visibleIf(rotationVisible)
        panel.row("Interval:") { cell(rotationInterval) }.visibleIf(rotationVisible)
        panel.row { cell(rotationRaw).resizableColumn().align(Align.FILL) }.visibleIf(rotationVisible)
    }

    fun render(view: AccentPanelView) {
        context = view.context
        for ((target, checkbox) in shared) {
            checkbox.isSelected =
                view.settings.system.toggles
                    .getValue(target)
            checkbox.isEnabled = view.isEditable && (!target.isPremium || view.isLicensed)
            checkbox.toolTipText = if (target.isPremium && !view.isLicensed) "Requires Ayu Islands Pro" else null
        }
        follow.isSelected = view.settings.followSystemAccent
        follow.isEnabled = view.isEditable && view.context.isSystemAccentSupported
        followVisible.set(view.context.isSystemAccentSupported)
        systemVisible.set(view.context.isSystemAppearanceSupported)
        policy.isEnabled = follow.isEnabled
        policy.selectedItem = view.settings.systemAccentPolicy
        rule.text =
            if (view.settings.systemAccentPolicy == SystemAccentPolicy.BY_PROFILE) {
                "Using this profile's choice"
            } else {
                "Shared rule: ${policyLabel(view.settings.systemAccentPolicy)}"
            }
        renderNight(view)
        renderRotation(view)
    }

    private fun renderNight(view: AccentPanelView) {
        val follows =
            view.settings.system.toggles
                .getValue(AccentSharedToggle.FOLLOW_SYSTEM_APPEARANCE)
        nightVisible.set(view.context.isSystemAppearanceSupported && follows)
        night.isEnabled = view.isEditable
        val raw = view.settings.system.nightTheme
        night.selectedItem =
            when (raw) {
                "Ayu Mirage", "Ayu Mirage (Islands UI)" -> "Mirage"
                "Ayu Dark", "Ayu Dark (Islands UI)" -> "Dark"
                else -> null
            }
        nightRaw.text = if (night.selectedItem == null) "Stored night theme: ${raw ?: "none"}" else ""
        retainCaption(nightRaw)
    }

    private fun renderRotation(view: AccentPanelView) {
        val rotation = view.settings.rotation
        val allowed = view.isEditable && view.isLicensed
        rotationEnabled.isSelected = rotation.enabled
        rotationEnabled.isEnabled = allowed
        rotationEnabled.toolTipText = if (view.isLicensed) null else "Accent rotation requires Ayu Islands Pro"
        rotationVisible.set(rotation.enabled || !view.isLicensed)
        rotationMode.isEnabled = allowed
        rotationInterval.isEnabled = allowed
        rotationMode.selectedItem = AccentRotationMode.entries.firstOrNull { it.name == rotation.modeRaw }
        rotationInterval.selectedItem = rotation.intervalHoursRaw.takeIf { it in INTERVALS }
        rotationRaw.text =
            buildList {
                if (rotationMode.selectedItem == null) add("Stored mode: ${rotation.modeRaw ?: "none"}")
                if (rotationInterval.selectedItem == null) add("Stored interval: ${rotation.intervalHoursRaw} hours")
            }.joinToString(" · ")
        retainCaption(rotationRaw)
        paused.isVisible = rotation.enabled && view.settings.effectiveFollowSystem
    }

    private companion object {
        val INTERVALS = listOf(1, 3, 6, 12, 24)
    }
}

internal fun <T> accentChoice(
    items: List<T>,
    label: (T) -> String,
): JComboBox<T> =
    ComboBox<T>().apply {
        items.forEach(::addItem)
        renderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val selected = items.firstOrNull { it == value }
                    return super.getListCellRendererComponent(
                        list,
                        selected?.let(label).orEmpty(),
                        index,
                        isSelected,
                        cellHasFocus,
                    )
                }
            }
    }

private fun policyLabel(policy: SystemAccentPolicy): String =
    when (policy) {
        SystemAccentPolicy.BY_PROFILE -> "By profile"
        SystemAccentPolicy.FORCE_ON -> "On for all profiles"
        SystemAccentPolicy.FORCE_OFF -> "Off for all profiles"
    }

internal fun retainCaption(label: JLabel) {
    label.toolTipText = label.text
    label.accessibleContext.accessibleName = label.text
    label.minimumSize = Dimension(0, label.preferredSize.height)
}
