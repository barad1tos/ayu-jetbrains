package dev.ayuislands.studio.ui

import com.intellij.ui.TitledSeparator
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsPreviewPanel
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioAccentPolicy
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel

internal data class StudioAccentView(
    val variant: AyuVariant,
    val draft: DraftRecord,
    val accepted: AppearanceSnapshot,
    val systemPolicy: SystemAccentPolicy,
    val isEditable: Boolean,
)

/** The Settings EDT owns this local sample. The host owns draft selection and acceptance. */
internal class StudioAccentSection(
    private val onDraftChanged: (DraftRecord) -> Unit,
    private val chooseCustom: (AccentHex) -> AccentHex?,
    private val shuffleColor: (AyuVariant) -> AccentHex,
) {
    private val spacing = StudioUiTokens.spacing()
    private val sample = AyuIslandsPreviewPanel().apply { previewGlowEnabled = false }
    private val sampleView =
        sample.createComponent(leadingInset = 0).apply {
            accessibleContext.accessibleName = "Global accent sample"
        }
    private val previewColor = JLabel()
    private val systemNotice = JLabel("Following system accent").apply { isVisible = false }
    private val palette =
        StudioAccentPalette(
            onSelect = { color -> edit { color } },
            onCustom = { edit { chooseCustom(StudioAccentEditing.sample(it.draft.appearance, it.variant)) } },
            onShuffle = { edit { shuffleColor(it.variant) } },
        )
    private var rendered: StudioAccentView? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.ACCENT, component(), ::preview)

    fun render(view: StudioAccentView) {
        rendered = view
        val followsSystem = StudioAccentPolicy.followsSystem(view.draft.appearance, view.systemPolicy)
        val selected = AccentHex.of(StudioAccentEditing.stored(view.draft.appearance, view.variant))
        palette.render(selected, view.isEditable && !followsSystem)
        systemNotice.isVisible = followsSystem
        preview(mode)
    }

    private fun component(): JPanel =
        JPanel(BorderLayout(0, spacing.fieldGap)).apply {
            minimumSize = Dimension(0, 0)
            add(TitledSeparator("Accent Color"), BorderLayout.NORTH)
            add(
                JPanel(BorderLayout(0, spacing.fieldGap)).apply {
                    add(previewColor, BorderLayout.NORTH)
                    add(sampleView, BorderLayout.CENTER)
                    add(systemNotice, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
            add(palette, BorderLayout.SOUTH)
        }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val view = rendered ?: return
        val appearance = if (selected == PreviewMode.CURRENT) view.accepted else view.draft.appearance
        val color = StudioAccentEditing.sample(appearance, view.variant)
        sample.previewAccentHex = color.value
        previewColor.text = "Global accent sample · ${color.value}"
        sampleView.accessibleContext.accessibleDescription = previewColor.text
        sample.updatePreview()
    }

    private fun edit(pick: (StudioAccentView) -> AccentHex?) {
        val before = editableView() ?: return
        val color = pick(before) ?: return
        val current = editableView() ?: return
        if (!sameColorTarget(before, current)) return
        val next = StudioAccentEditing.choose(current.draft, current.variant, color)
        if (next != current.draft) onDraftChanged(next)
    }

    private fun editableView(): StudioAccentView? =
        rendered?.takeIf { it.isEditable && !StudioAccentPolicy.followsSystem(it.draft.appearance, it.systemPolicy) }

    private fun sameColorTarget(
        before: StudioAccentView,
        current: StudioAccentView,
    ): Boolean =
        before.draft.id == current.draft.id &&
            before.variant == current.variant &&
            StudioAccentEditing.stored(before.draft.appearance, before.variant) ==
            StudioAccentEditing.stored(current.draft.appearance, current.variant)
}
