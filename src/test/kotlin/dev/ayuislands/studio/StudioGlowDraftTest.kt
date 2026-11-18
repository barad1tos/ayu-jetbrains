package dev.ayuislands.studio

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioGlowEdit
import dev.ayuislands.studio.model.StudioGlowTarget
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioGlowEditing
import dev.ayuislands.studio.session.StudioGlowProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioGlowDraftTest {
    @Test
    fun `legacy values project without normalizing the stored fields`() {
        val draft =
            draftWith(
                "glowPreset" to RawValue.Null,
                "glowStyle" to RawValue.Text("GRADIENT"),
                "glowAnimation" to RawValue.Text("BREATHE"),
                "gradientIntensity" to RawValue.Integer(45),
                "gradientWidth" to RawValue.Integer(10),
                "glowShape" to RawValue.Text("WAVEFORM"),
                "glowEditorPlacement" to RawValue.Text("TAB_BAR"),
                "glowToolWindowPlacement" to RawValue.Text("future-placement"),
                "glowProject" to RawValue.BooleanValue(false),
                "glowGit" to RawValue.BooleanValue(true),
                "waveformDirection" to RawValue.Text("CHAOTIC"),
                "waveformBaseline" to RawValue.Text("OUTSIDE"),
                "waveformLoopSeconds" to RawValue.FloatBits(19.125f.toRawBits()),
            )
        val settings = StudioGlowProjection.settings(draft.appearance)

        assertEquals(GlowPreset.AMBIENT, settings.preset)
        assertEquals(GlowShape.WAVEFORM, settings.shape)
        assertEquals(GlowPlacement.SIDE_EDGES, settings.editorPlacement)
        assertEquals(GlowPlacement.ISLAND, settings.toolWindowPlacement)
        assertEquals(false, settings.islandToggles["Project"])
        assertEquals(true, settings.islandToggles["Git"])
        assertEquals(WaveformMovement.CHAOTIC, settings.waveformMovement)
        assertEquals(WaveformBaseline.OUTSIDE, settings.waveformBaseline)
        assertEquals(19.125f.toRawBits(), settings.waveformLoopSeconds.toRawBits())
        assertEquals(RawValue.Null, field(draft, "glowPreset"))
        assertEquals(RawValue.Text("TAB_BAR"), field(draft, "glowEditorPlacement"))

        val future = draftWith("glowPreset" to RawValue.Text("future-preset"))
        assertEquals(GlowPreset.CUSTOM, StudioGlowProjection.settings(future.appearance).preset)
        val edited = StudioGlowEditing.edit(future, StudioGlowEdit.Width(12))
        assertEquals(setOf("softWidth"), touched(edited))
        assertEquals(RawValue.Text("future-preset"), field(edited, "glowPreset"))
    }

    @Test
    fun `disable and reenable restore the exact accepted records`() {
        val draft = draftWith("glowEnabled" to RawValue.BooleanValue(true), "glowShape" to RawValue.Text("WAVEFORM"))
        val off = StudioGlowEditing.edit(draft, StudioGlowEdit.Enable(false))

        assertFalse(StudioGlowProjection.settings(off.appearance).enabled)
        assertEquals(setOf("glowEnabled"), touched(off))
        assertEquals(draft.base, off.base)
        assertEquals(draft, StudioGlowEditing.edit(off, StudioGlowEdit.Enable(true)))
        assertEquals(draft, StudioDraftPolicy.discard(off))
    }

    @Test
    fun `a manual style change uses the new style slot and preserves the previous slots`() {
        val draft =
            draftWith(
                "glowPreset" to RawValue.Text("WHISPER"),
                "glowStyle" to RawValue.Text("SOFT"),
                "softIntensity" to RawValue.Integer(36),
                "softWidth" to RawValue.Integer(7),
                "gradientIntensity" to RawValue.Integer(42),
                "gradientWidth" to RawValue.Integer(9),
            )

        val changed =
            listOf(
                StudioGlowEdit.Style(GlowStyle.GRADIENT),
                StudioGlowEdit.Intensity(61),
                StudioGlowEdit.Width(12),
                StudioGlowEdit.Animation(GlowAnimation.REACTIVE),
            ).fold(draft, StudioGlowEditing::edit)
        val settings = StudioGlowProjection.settings(changed.appearance)

        assertEquals(
            setOf("glowPreset", "glowStyle", "gradientIntensity", "gradientWidth", "glowAnimation"),
            touched(changed),
        )
        assertEquals(GlowPreset.CUSTOM, settings.preset)
        assertEquals(GlowAnimation.REACTIVE, settings.animation)
        assertEquals(36, settings.intensity[GlowStyle.SOFT])
        assertEquals(7, settings.width[GlowStyle.SOFT])
        assertEquals(61, settings.intensity[GlowStyle.GRADIENT])
        assertEquals(12, settings.width[GlowStyle.GRADIENT])
        assertEquals(draft, StudioDraftPolicy.discard(changed))
    }

    @Test
    fun `a manual edit stays Custom when legacy settings happen to match a preset afterward`() {
        val draft =
            draftWith(
                "glowPreset" to RawValue.Null,
                "glowStyle" to RawValue.Text("SOFT"),
                "glowAnimation" to RawValue.Text("NONE"),
                "softIntensity" to RawValue.Integer(35),
                "softWidth" to RawValue.Integer(7),
            )
        assertEquals(GlowPreset.CUSTOM, StudioGlowProjection.settings(draft.appearance).preset)

        val edited = StudioGlowEditing.edit(draft, StudioGlowEdit.Width(8))

        assertEquals(GlowPreset.CUSTOM, StudioGlowProjection.settings(edited.appearance).preset)
        assertEquals(setOf("glowPreset", "softWidth"), touched(edited))
        assertEquals(draft, StudioDraftPolicy.discard(edited))
    }

    @Test
    fun `selecting a preset updates only that recipe while Custom keeps its settings`() {
        val draft =
            draftWith(
                "glowPreset" to RawValue.Text("WHISPER"),
                "glowStyle" to RawValue.Text("SOFT"),
                "glowAnimation" to RawValue.Text("NONE"),
                "gradientIntensity" to RawValue.Integer(42),
                "gradientWidth" to RawValue.Integer(9),
                "glowShape" to RawValue.Text("WAVEFORM"),
            )
        val ambient = StudioGlowEditing.edit(draft, StudioGlowEdit.Preset(GlowPreset.AMBIENT))
        val custom = StudioGlowEditing.edit(draft, StudioGlowEdit.Preset(GlowPreset.CUSTOM))
        val settings = StudioGlowProjection.settings(ambient.appearance)

        assertEquals(
            setOf("glowPreset", "glowStyle", "glowAnimation", "gradientIntensity", "gradientWidth"),
            touched(ambient),
        )
        assertEquals(GlowShape.WAVEFORM, settings.shape)
        assertEquals(GlowAnimation.BREATHE, settings.animation)
        assertEquals(45, settings.intensity[GlowStyle.GRADIENT])
        assertEquals(10, settings.width[GlowStyle.GRADIENT])
        assertEquals(setOf("glowPreset"), touched(custom))
    }

    @Test
    fun `preset reset keeps inactive style slots and user surface choices`() {
        val draft =
            draftWith(
                "glowEnabled" to RawValue.BooleanValue(true),
                "glowShape" to RawValue.Text("WAVEFORM"),
                "glowPreset" to RawValue.Text("CUSTOM"),
                "glowStyle" to RawValue.Text("GRADIENT"),
                "softIntensity" to RawValue.Integer(36),
                "softWidth" to RawValue.Integer(9),
                "gradientIntensity" to RawValue.Integer(62),
                "glowEditor" to RawValue.BooleanValue(false),
                "glowFocusRing" to RawValue.BooleanValue(false),
                "glowEditorPlacement" to RawValue.Text("TAB_BAR"),
                "waveformDirection" to RawValue.Text("CHAOTIC"),
            )
        val reset = StudioGlowEditing.edit(draft, StudioGlowEdit.Reset)
        val settings = StudioGlowProjection.settings(reset.appearance)

        assertTrue(settings.enabled)
        assertEquals(GlowShape.SOLID, settings.shape)
        assertEquals(GlowPreset.WHISPER, settings.preset)
        assertEquals(GlowStyle.SOFT, settings.style)
        assertEquals(35, settings.intensity[GlowStyle.SOFT])
        assertEquals(8, settings.width[GlowStyle.SOFT])
        assertEquals(WaveformMovement.CLOCKWISE, settings.waveformMovement)
        val preserved = setOf("glowEnabled", "gradientIntensity", "glowEditor", "glowFocusRing", "glowEditorPlacement")
        assertEquals(
            draft.appearance.glow.records
                .filter { it.key.name in preserved },
            reset.appearance.glow.records
                .filter { it.key.name in preserved },
        )
        assertEquals(draft, StudioDraftPolicy.discard(reset))
    }

    @Test
    fun `waveform and placement edit only their addressed field`() {
        val cases =
            listOf(
                Triple(StudioGlowEdit.Shape(GlowShape.WAVEFORM), "glowShape", RawValue.Text("WAVEFORM")),
                Triple(
                    StudioGlowEdit.Movement(WaveformMovement.CHAOTIC),
                    "waveformDirection",
                    RawValue.Text("CHAOTIC"),
                ),
                Triple(StudioGlowEdit.Baseline(WaveformBaseline.OUTSIDE), "waveformBaseline", RawValue.Text("OUTSIDE")),
                Triple(StudioGlowEdit.TraceDensity(3), "waveformTraceDensity", RawValue.Integer(3)),
                Triple(StudioGlowEdit.TraceLength(216), "waveformTraceLength", RawValue.Integer(216)),
                Triple(StudioGlowEdit.Amplitude(31), "waveformAmplitude", RawValue.Integer(31)),
                Triple(StudioGlowEdit.WaveformIntensity(117), "waveformIntensity", RawValue.Integer(117)),
                Triple(
                    StudioGlowEdit.LoopSeconds(23.125f),
                    "waveformLoopSeconds",
                    RawValue.FloatBits(23.125f.toRawBits()),
                ),
                Triple(
                    StudioGlowEdit.EditorPlacement(GlowPlacement.SIDE_EDGES),
                    "glowEditorPlacement",
                    RawValue.Text("SIDE_EDGES"),
                ),
                Triple(
                    StudioGlowEdit.ToolWindowPlacement(GlowPlacement.SIDE_EDGES),
                    "glowToolWindowPlacement",
                    RawValue.Text("SIDE_EDGES"),
                ),
            )

        cases.forEach { (edit, name, value) ->
            val draft = draftWith()
            val changed = StudioGlowEditing.edit(draft, edit)
            assertEquals(setOf(name), touched(changed))
            assertEquals(value, field(changed, name))
        }
    }

    @Test
    fun `bulk targets affect only the requested surfaces`() {
        val draft =
            draftWith(
                "glowEditor" to RawValue.BooleanValue(true),
                "glowGit" to RawValue.BooleanValue(true),
                "glowDebug" to RawValue.BooleanValue(true),
                "glowFocusRing" to RawValue.BooleanValue(true),
            )
        val targets = StudioList(listOf(StudioGlowTarget.EDITOR, StudioGlowTarget.GIT))
        val off = StudioGlowEditing.edit(draft, StudioGlowEdit.Targets(targets, enabled = false))

        assertEquals(setOf("glowEditor", "glowGit"), touched(off))
        val liveTargets = StudioGlowProjection.settings(off.appearance).islandToggles
        assertEquals(false, liveTargets["Editor"])
        assertEquals(false, liveTargets["Git"])
        assertEquals(true, liveTargets["Debug"])
        assertEquals(RawValue.BooleanValue(true), field(off, "glowFocusRing"))
        assertEquals(draft, StudioGlowEditing.edit(off, StudioGlowEdit.Targets(targets, enabled = true)))
        assertEquals(
            draft,
            StudioGlowEditing.edit(draft, StudioGlowEdit.Targets(StudioList(emptyList()), enabled = false)),
        )
    }

    private fun draftWith(vararg values: Pair<String, RawValue>): DraftRecord {
        val original = catalogTestWork(catalogTestDocument()).drafts.single()
        val updated =
            values.fold(original.appearance) { appearance, (name, value) ->
                val existing = StudioAppearanceFields.field(appearance, key(name))
                val origin = FieldOrigin(SourcePresence.VALUE, StudioBytes(name.encodeToByteArray()))
                StudioAppearanceFields.replace(appearance, existing.copy(value = value, origin = origin))
            }
        return original.copy(
            appearance = updated,
            base = original.base.copy(appearance = updated),
            tracking = DraftTracking(updated),
        )
    }

    private fun key(name: String): LegacyFieldKey = LegacyFieldKey(LegacyStore.SETTINGS, name)

    private fun field(
        draft: DraftRecord,
        name: String,
    ): RawValue = StudioAppearanceFields.field(draft.appearance, key(name)).value

    private fun touched(draft: DraftRecord): Set<String> =
        draft.tracking.touched
            .map { it.field.name }
            .toSet()
}
