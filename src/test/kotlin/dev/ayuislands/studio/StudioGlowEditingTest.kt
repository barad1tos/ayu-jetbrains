package dev.ayuislands.studio

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.DEFAULT_LOOP_SECONDS
import dev.ayuislands.glow.waveform.DEFAULT_TRACE_DENSITY
import dev.ayuislands.glow.waveform.DEFAULT_TRACE_LENGTH
import dev.ayuislands.glow.waveform.DEFAULT_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.DEFAULT_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioGlowEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioGlowEditingTest {
    @Test
    fun `disable and reenable restore every dormant and unknown Glow choice`() {
        val initial = draft()
        val disabled = edit(initial, GlowPanelEvent.Enable(false))
        assertAppearance(initial, disabled, mapOf("glowEnabled" to RawValue.BooleanValue(false)))

        val restored = edit(disabled, GlowPanelEvent.Enable(true))
        assertEquals(initial.appearance, restored.appearance)
        assertTrue(restored.tracking.touched.isEmpty())
    }

    @Test
    fun `shape switches retain both shape memories and the selected solid preset`() {
        val initial = draft { glowShape = GlowShape.SOLID.name }
        val waveform = edit(initial, GlowPanelEvent.Shape(GlowShape.WAVEFORM))
        assertAppearance(initial, waveform, mapOf("glowShape" to RawValue.Text(GlowShape.WAVEFORM.name)))

        val restored = edit(waveform, GlowPanelEvent.Shape(GlowShape.SOLID))
        assertEquals(initial.appearance, restored.appearance)
    }

    @Test
    fun `intensity edits touch only the selected style and explicitly mark its preset custom`() {
        val initial = draft { glowStyle = GlowStyle.GRADIENT.name }
        val changed = edit(initial, GlowPanelEvent.Intensity(61))

        assertAppearance(
            initial,
            changed,
            mapOf(
                "gradientIntensity" to RawValue.Integer(61),
                "glowPreset" to RawValue.Text(GlowPreset.CUSTOM.name),
            ),
        )
    }

    @Test
    fun `explicit preset selection changes only its recipe while preserving waveform placement and targets`() {
        val initial = draft()
        val changed = edit(initial, GlowPanelEvent.Preset(GlowPreset.NEON))
        val preset = GlowPreset.NEON

        assertAppearance(
            initial,
            changed,
            mapOf(
                "glowPreset" to RawValue.Text(preset.name),
                "glowStyle" to RawValue.Text(requireNotNull(preset.style).name),
                "sharpNeonIntensity" to RawValue.Integer(requireNotNull(preset.intensity)),
                "sharpNeonWidth" to RawValue.Integer(requireNotNull(preset.width)),
                "glowAnimation" to RawValue.Text(requireNotNull(preset.animation).name),
            ),
        )
    }

    @Test
    fun `waveform edits preserve all unrelated raw unknown values and floating point payloads`() {
        val initial = draft { waveformLoopSeconds = Float.fromBits(0x7fc00041) }
        val changed = edit(initial, GlowPanelEvent.TraceLength(349))

        assertAppearance(initial, changed, mapOf("waveformTraceLength" to RawValue.Integer(349)))
        val moved = edit(changed, GlowPanelEvent.Movement(WaveformMovement.COUNTER_CLOCKWISE))
        assertAppearance(
            changed,
            moved,
            mapOf("waveformDirection" to RawValue.Text(WaveformMovement.COUNTER_CLOCKWISE.name)),
        )
    }

    @Test
    fun `explicit reset retains enabled state placements targets focus ring and inactive style values`() {
        val initial = draft { glowEnabled = false }
        val changed = edit(initial, GlowPanelEvent.Reset)
        val preset = GlowPreset.WHISPER

        assertAppearance(
            initial,
            changed,
            mapOf(
                "glowShape" to RawValue.Text(GlowShape.SOLID.name),
                "glowPreset" to RawValue.Text(preset.name),
                "glowStyle" to RawValue.Text(requireNotNull(preset.style).name),
                "softIntensity" to RawValue.Integer(requireNotNull(preset.intensity)),
                "softWidth" to RawValue.Integer(requireNotNull(preset.width)),
                "glowAnimation" to RawValue.Text(GlowAnimation.NONE.name),
                "waveformDirection" to RawValue.Text(WaveformMovement.CLOCKWISE.name),
                "waveformBaseline" to RawValue.Text(WaveformBaseline.CENTERED.name),
                "waveformTraceDensity" to RawValue.Integer(DEFAULT_TRACE_DENSITY),
                "waveformTraceLength" to RawValue.Integer(DEFAULT_TRACE_LENGTH),
                "waveformAmplitude" to RawValue.Integer(DEFAULT_WAVEFORM_AMPLITUDE),
                "waveformIntensity" to RawValue.Integer(DEFAULT_WAVEFORM_INTENSITY),
                "waveformLoopSeconds" to RawValue.FloatBits(DEFAULT_LOOP_SECONDS.toRawBits()),
            ),
        )
    }

    private fun draft(configure: AyuIslandsState.() -> Unit = {}): DraftRecord {
        val settings =
            AyuIslandsState().apply {
                glowEnabled = true
                glowFocusRing = false
                glowShape = GlowShape.WAVEFORM.name
                glowPreset = GlowPreset.AMBIENT.name
                glowStyle = GlowStyle.SOFT.name
                softIntensity = 17
                softWidth = 3
                gradientIntensity = 29
                gradientWidth = 7
                sharpNeonIntensity = 81
                sharpNeonWidth = 13
                glowAnimation = "FUTURE_ANIMATION"
                glowEditorPlacement = "TAB_BAR"
                glowToolWindowPlacement = "FUTURE_PLACEMENT"
                glowEditor = false
                glowProject = true
                glowTerminal = false
                glowRun = true
                glowDebug = false
                glowGit = false
                glowServices = true
                waveformDirection = "FUTURE_DIRECTION"
                waveformBaseline = "FUTURE_BASELINE"
                waveformTraceDensity = 8
                waveformTraceLength = 913
                waveformAmplitude = -4
                waveformIntensity = 272
                waveformLoopSeconds = 13.7f
                configure()
            }
        val document =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.capture(settings)),
            ).document
        return sessionRecord(document).work.drafts.single()
    }

    private fun edit(
        initial: DraftRecord,
        event: GlowPanelEvent,
    ): DraftRecord =
        StudioGlowEditing.edits(initial, event).fold(initial) { current, intent ->
            val field = assertIs<SessionIntent.EditField>(intent)
            assertEquals(initial.id, field.draft)
            StudioDraftPolicy.edit(current, field.address, field.value)
        }

    private fun assertAppearance(
        before: DraftRecord,
        after: DraftRecord,
        changes: Map<String, RawValue>,
    ) {
        val expected =
            before.appearance.glow.copy(
                records =
                    StudioList(
                        before.appearance.glow.records.map { field ->
                            val requested = changes[field.key.name]
                            if (requested == null || requested == field.value) {
                                field
                            } else {
                                field.copy(value = requested, origin = FieldOrigin(SourcePresence.VALUE))
                            }
                        },
                    ),
            )
        assertEquals(before.appearance.copy(glow = expected), after.appearance)
    }
}
