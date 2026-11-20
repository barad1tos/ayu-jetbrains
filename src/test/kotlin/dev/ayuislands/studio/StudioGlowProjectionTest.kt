package dev.ayuislands.studio

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.loadGlowSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioFieldMapping
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StudioGlowProjectionTest {
    @Test
    fun `all preset names and shapes retain dormant style values instead of applying a recipe`() {
        for (preset in GlowPreset.entries) {
            for (shape in GlowShape.entries) {
                for (style in GlowStyle.entries) {
                    val state =
                        customizedState().apply {
                            glowPreset = preset.name
                            glowShape = shape.name
                            glowStyle = style.name
                        }
                    assertLegacyProjection(state)
                }
            }
        }
    }

    @Test
    fun `disabling and reloading preserves all granular choices and the hidden focus ring`() {
        val state = customizedState()
        val enabled = assertLegacyProjection(state)
        state.glowEnabled = false
        val disabled = assertLegacyProjection(state)
        assertEquals(enabled.copy(enabled = false), disabled)
        state.glowEnabled = true
        assertEquals(enabled, assertLegacyProjection(state))
        state.glowFocusRing = true
        assertEquals(enabled.copy(focusRing = true), assertLegacyProjection(state))
    }

    @Test
    fun `a saved v2 focus ring stays off after import and reload`() {
        val settings =
            """
            <application><component name="AyuIslandsSettings">
              <option name="glowFocusRing" value="false" />
            </component></application>
            """.trimIndent()
        val archives =
            LegacyStore.entries.map { store ->
                val file =
                    if (store == LegacyStore.SETTINGS) {
                        LegacyFile.Present(StudioBytes(settings.encodeToByteArray()))
                    } else {
                        LegacyFile.Absent
                    }
                LegacyArchive(store, file)
            }
        val source = MigrationSource("saved-glow", StudioList(archives))
        val captured =
            assertIs<LegacyCapture.Ready>(StudioLegacyCapture.read(source, "migration", "/home/ayu")).snapshot
        val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(captured)).document
        val reopened = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(document)))
        val appearance = reopened.document.currents.global.appearance
        assertEquals(source, reopened.document.header.migration)
        assertEquals(StudioFieldMapping.select(captured.fields, StudioFieldOwner.GLOW), appearance.glow)
        assertFalse(StudioGlowProjection.settings(appearance).focusRing)
    }

    @Test
    fun `legacy and fresh defaults remain distinct while absent preset names use legacy detection`() {
        assertLegacyProjection(AyuIslandsState())
        assertLegacyProjection(freshSettingsState())
        for (preset in GlowPreset.entries.filter { it != GlowPreset.CUSTOM }) {
            val state =
                customizedState().apply {
                    glowPreset = null
                    val style = requireNotNull(preset.style)
                    glowStyle = style.name
                    setIntensityForStyle(style, requireNotNull(preset.intensity))
                    setWidthForStyle(style, requireNotNull(preset.width))
                    glowAnimation = requireNotNull(preset.animation).name
                }
            assertEquals(preset, assertLegacyProjection(state).preset)
        }
    }

    @Test
    fun `unknown names nulls retired placements and unusual numbers never rewrite raw intent`() {
        val state =
            customizedState().apply {
                glowPreset = "FUTURE"
                glowShape = "FUTURE_SHAPE"
                glowStyle = "FUTURE_STYLE"
                glowAnimation = "FUTURE_ANIMATION"
                waveformDirection = "FUTURE_DIRECTION"
                waveformBaseline = "FUTURE_BASELINE"
                glowEditorPlacement = "TAB_BAR"
                glowToolWindowPlacement = "FUTURE_PLACEMENT"
                softIntensity = -9
                gradientWidth = 999
                waveformTraceDensity = -3
                waveformTraceLength = 0
                waveformAmplitude = -10
                waveformIntensity = 777
            }
        for (duration in listOf(-0.0f, -12.0f, Float.POSITIVE_INFINITY, Float.fromBits(0x7fc00041))) {
            state.waveformLoopSeconds = duration
            assertEquals(duration.toRawBits(), assertLegacyProjection(state).waveformLoopSeconds.toRawBits())
        }
        state.apply {
            glowPreset = null
            glowShape = null
            glowStyle = null
            glowAnimation = null
            waveformDirection = null
            waveformBaseline = null
            glowEditorPlacement = null
            glowToolWindowPlacement = null
        }
        assertLegacyProjection(state)
    }

    private fun assertLegacyProjection(state: AyuIslandsState): GlowSettings {
        val captured = captureFields(state)
        val snapshot =
            LegacySnapshot(
                MigrationSource("glow", StudioList(LegacyStore.entries.map { LegacyArchive(it, LegacyFile.Absent) })),
                captured,
                StudioFactoryProfiles.create(captureFields(freshSettingsState())),
                "glow-migration",
            )
        val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(snapshot)).document
        val appearance = document.currents.global.appearance
        val projected = StudioGlowProjection.settings(appearance)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        val presetName =
            state.glowPreset ?: GlowPreset
                .detect(
                    style,
                    state.getIntensityForStyle(style),
                    state.getWidthForStyle(style),
                    GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name),
                ).name
        val expected = loadGlowSettings(state, presetName, ISLAND_IDS)
        assertEquals(expected, projected)
        assertEquals(state.glowFocusRing, projected.focusRing)
        assertEquals(StudioFieldMapping.select(captured, StudioFieldOwner.GLOW), appearance.glow)
        assertEquals(captured, captureFields(state))
        val reopened = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(document)))
        val restoredAppearance = reopened.document.currents.global.appearance
        assertEquals(appearance, restoredAppearance)
        assertEquals(projected, StudioGlowProjection.settings(restoredAppearance))
        assertEquals(
            projected.waveformLoopSeconds.toRawBits(),
            StudioGlowProjection.settings(restoredAppearance).waveformLoopSeconds.toRawBits(),
        )
        return projected
    }

    private fun captureFields(state: AyuIslandsState): StudioFields =
        StudioLegacyFields.capture(state, SyntaxIntensityBaseState(), AccentMappingsState(), origins = emptyMap())

    private fun customizedState(): AyuIslandsState =
        AyuIslandsState().apply {
            glowEnabled = true
            glowFocusRing = false
            glowEditor = false
            glowProject = true
            glowTerminal = false
            glowRun = true
            glowDebug = false
            glowGit = true
            glowServices = false
            glowPreset = "AMBIENT"
            glowShape = "WAVEFORM"
            glowStyle = "SHARP_NEON"
            softIntensity = 13
            sharpNeonIntensity = 67
            gradientIntensity = 41
            softWidth = 3
            sharpNeonWidth = 11
            gradientWidth = 7
            glowAnimation = "REACTIVE"
            glowEditorPlacement = "SIDE_EDGES"
            glowToolWindowPlacement = "ISLAND"
            waveformDirection = "CHAOTIC"
            waveformBaseline = "OUTSIDE"
            waveformTraceDensity = 3
            waveformTraceLength = 193
            waveformAmplitude = 25
            waveformIntensity = 73
            waveformLoopSeconds = 7.25f
        }

    companion object {
        private val ISLAND_IDS = listOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services")
    }
}
