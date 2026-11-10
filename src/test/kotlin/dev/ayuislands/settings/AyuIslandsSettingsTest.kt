package dev.ayuislands.settings

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.font.FontPreset
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AyuIslandsSettingsTest {
    private fun createSettings(state: AyuIslandsState): AyuIslandsSettings {
        val studio = mockk<dev.ayuislands.studio.StudioService>()
        mockkObject(dev.ayuislands.studio.StudioService.Companion)
        every {
            dev.ayuislands.studio.StudioService
                .getInstance()
        } returns studio
        every { studio.acceptedView } answers {
            dev.ayuislands.studio.StudioFixtures
                .legacyView(state)
        }
        return AyuIslandsSettings()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fresh settings use the calibrated ECG profile`() {
        val state = freshSettingsState()
        val waveform = WaveformConfig()

        assertEquals(waveform.movement.name, state.waveformDirection)
        assertEquals(waveform.baseline.name, state.waveformBaseline)
        assertEquals(waveform.traceDensity, state.waveformTraceDensity)
        assertEquals(waveform.traceLength, state.waveformTraceLength)
        assertEquals(waveform.amplitude, state.waveformAmplitude)
        assertEquals(waveform.intensity, state.waveformIntensity)
        assertEquals(waveform.loopSeconds, state.waveformLoopSeconds)
    }

    @Test
    fun `loading legacy settings preserves the existing ECG profile`() {
        val legacy = AyuIslandsState()

        val settings = createSettings(legacy)

        assertEquals(WaveformBaseline.OUTSIDE.name, requireNotNull(settings.state).waveformBaseline)
        assertEquals(167, requireNotNull(settings.state).waveformTraceLength)
        assertEquals(10, requireNotNull(settings.state).waveformAmplitude)
        assertEquals(70, requireNotNull(settings.state).waveformIntensity)
        assertEquals(30f, requireNotNull(settings.state).waveformLoopSeconds)
    }

    @Test
    fun `getAccentForVariant returns stored accent for MIRAGE`() {
        val state = AyuIslandsState().apply { mirageAccent = "#FF0000" }
        val settings = createSettings(state)

        assertEquals("#FF0000", settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    @Test
    fun `getAccentForVariant returns default when no stored accent`() {
        val state =
            AyuIslandsState().apply {
                mirageAccent = null
                darkAccent = null
                lightAccent = null
            }
        val settings = createSettings(state)

        assertEquals("#FFCC66", settings.getAccentForVariant(AyuVariant.MIRAGE))
        assertEquals("#E6B450", settings.getAccentForVariant(AyuVariant.DARK))
        assertEquals("#F29718", settings.getAccentForVariant(AyuVariant.LIGHT))
    }

    @Test
    fun `getAccentForVariant returns system accent when followSystemAccent enabled`() {
        mockkObject(SystemAccentProvider)
        every { SystemAccentProvider.resolve() } returns "#AABBCC"

        val state =
            AyuIslandsState().apply {
                followSystemAccent = true
                mirageAccent = "#FF0000"
            }
        val settings = createSettings(state)

        assertEquals("#AABBCC", settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    @Test
    fun `getAccentForVariant falls back to stored when system accent null`() {
        mockkObject(SystemAccentProvider)
        every { SystemAccentProvider.resolve() } returns null

        val state =
            AyuIslandsState().apply {
                followSystemAccent = true
                mirageAccent = "#FF0000"
            }
        val settings = createSettings(state)

        assertEquals("#FF0000", settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    @Test
    fun `getAccentForVariant returns default for empty string`() {
        val state =
            AyuIslandsState().apply {
                mirageAccent = ""
            }
        val settings = createSettings(state)

        // BaseState's `by string()` coerces empty to default
        assertEquals(
            AyuVariant.MIRAGE.defaultAccent,
            settings.getAccentForVariant(AyuVariant.MIRAGE),
        )
    }

    @Test
    fun `unavailable accepted authority does not invent an accent or default settings`() {
        val studio = mockk<dev.ayuislands.studio.StudioService>()
        mockkObject(dev.ayuislands.studio.StudioService.Companion)
        every {
            dev.ayuislands.studio.StudioService
                .getInstance()
        } returns studio
        every { studio.acceptedView } returns null
        val settings = AyuIslandsSettings()
        kotlin.test.assertNull(settings.state)
        kotlin.test.assertNull(settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    @Test
    fun `already seeded inventory ignores later probes`() {
        val original = AyuIslandsState().apply { installedFontsSeeded = true }
        val result = seed(original, listOf(FontPreset.WHISPER.fontFamily))
        assertTrue(result.installedFontsSeeded)
        assertTrue(result.installedFonts.isEmpty())
    }

    @Test
    fun `first probe records the attempt even when unavailable or empty`() {
        for (families in listOf(null, emptyList<String>())) {
            val result = seed(AyuIslandsState(), families)
            assertTrue(result.installedFontsSeeded)
            assertTrue(result.installedFonts.isEmpty())
        }
    }

    @Test
    fun `seeding preserves existing order and never duplicates a family`() {
        val family = FontPreset.WHISPER.fontFamily
        val original = AyuIslandsState().apply { installedFonts.addAll(listOf("Custom personal font", family)) }
        val result = seed(original, listOf(family, family))
        assertEquals(listOf("Custom personal font", family), result.installedFonts.toList())
    }

    @Test
    fun `seeding respects explicit uninstalls while recording other installed catalog families`() {
        val original = AyuIslandsState().apply { explicitlyUninstalledFonts.add("Maple Mono") }
        val result = seed(original, listOf("Maple Mono", "Victor Mono", "Unknown installed font"))
        assertEquals(listOf("Victor Mono"), result.installedFonts.toList())
        assertEquals(listOf("Maple Mono"), result.explicitlyUninstalledFonts.toList())
        assertTrue(result.installedFontsSeeded)
    }

    @Test
    fun `seeding records completion when explicit guards block every catalog family`() {
        val original =
            AyuIslandsState().apply {
                explicitlyUninstalledFonts.addAll(
                    listOf("Maple Mono", "Victor Mono", "Monaspace Neon", "Monaspace Xenon"),
                )
            }
        val result = seed(original, listOf("Maple Mono", "Victor Mono"))
        assertTrue(result.installedFonts.isEmpty())
        assertTrue(result.installedFontsSeeded)
    }

    private fun seed(
        state: AyuIslandsState,
        families: List<String>?,
    ): dev.ayuislands.studio.persistence.AyuSettingsView {
        val document =
            dev.ayuislands.studio.StudioFixtures
                .legacyDocument(state)
        val command =
            dev.ayuislands.studio.model.StudioMetadataCommand.SeedFonts(
                families?.let {
                    dev.ayuislands.studio.model
                        .StudioList(it)
                },
            )
        val accepted =
            requireNotNull(
                dev.ayuislands.studio.fields.StudioMetadataPolicy
                    .apply(document, command),
            )
        assertEquals(
            document.copy(auxiliary = document.auxiliary.copy(resources = accepted.auxiliary.resources)),
            accepted,
        )
        return requireNotNull(
            dev.ayuislands.studio.persistence.StudioLegacyProjection.project(
                accepted,
                dev.ayuislands.studio.model.StudioTarget.Global,
            ),
        ).settings
    }
}
