package dev.ayuislands.studio.ui

import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.settings.GlowPreview
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioGlowEdit
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioGlowEditing
import dev.ayuislands.studio.session.StudioGlowProjection
import java.awt.Color

internal data class StudioGlowView(
    val draft: DraftRecord,
    val accepted: AppearanceSnapshot,
    val acceptedAccent: Color,
    val draftAccent: Color,
    val isEditable: Boolean,
    val isLicensed: Boolean,
    val pendingBadges: StudioList<String> = StudioList(emptyList()),
)

/** All host renders and controls events run on the Settings EDT. */
internal class StudioGlowSection(
    private val onDraftChanged: (DraftRecord) -> Unit,
    onUnlock: () -> Unit,
    onBadgeSeen: (String) -> Unit,
) {
    private val controls = StudioGlowControls(::edit, onUnlock, onBadgeSeen)
    private var presentation: GlowPresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.GLOW, controls, ::preview)

    fun render(view: StudioGlowView) {
        val draft = StudioGlowProjection.settings(view.draft.appearance)
        val accepted = StudioGlowProjection.settings(view.accepted)
        presentation =
            GlowPresentation(
                view.draft,
                accepted.preview(view.acceptedAccent, view.isLicensed),
                draft.preview(view.draftAccent, view.isLicensed),
            )
        controls.render(draft, view.isEditable, view.isLicensed, view.pendingBadges)
        preview(mode)
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val current = presentation ?: return
        controls.preview(if (selected == PreviewMode.CURRENT) current.accepted else current.pending)
    }

    private fun edit(action: StudioGlowEdit) {
        val draft = checkNotNull(presentation).draft
        val revision = StudioGlowEditing.edit(draft, action)
        if (revision != draft) onDraftChanged(revision)
    }
}

private data class GlowPresentation(
    val draft: DraftRecord,
    val accepted: GlowPreview,
    val pending: GlowPreview,
)

private fun GlowSettings.preview(
    accent: Color,
    licensed: Boolean,
): GlowPreview =
    GlowPreview(
        shape = shape,
        style = style,
        intensity = intensity.getValue(style),
        width = width.getValue(style),
        color = accent,
        visible = enabled || !licensed,
        waveformConfig =
            WaveformConfig(
                movement = waveformMovement,
                baseline = waveformBaseline,
                amplitude = waveformAmplitude,
                intensity = waveformIntensity,
                loopSeconds = waveformLoopSeconds,
                traceDensity = waveformTraceDensity,
                traceLength = waveformTraceLength,
            ),
    )
