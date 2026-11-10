package dev.ayuislands.studio.session

import dev.ayuislands.font.FontSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioFontRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Native evidence belongs to the exact resource inventory observed before the filesystem/JVM scan. */
internal data class StudioFontFacts(
    val resources: StudioFields,
    val families: List<String>,
    val files: Map<String, Boolean>,
)

/** Shared availability policy for admission and detached previews; never reads legacy settings services. */
internal object StudioFonts {
    suspend fun availableFamilies(ioContext: CoroutineContext = Dispatchers.IO): List<String> =
        withContext(ioContext) { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList() }

    suspend fun capture(
        resources: StudioFields,
        ioContext: CoroutineContext = Dispatchers.IO,
    ): StudioFontFacts =
        withContext(ioContext) {
            val paths = recordedFiles(resources).values.flatMap(AyuIslandsState::decodeFontPaths).distinct()
            StudioFontFacts(
                resources,
                availableFamilies(ioContext),
                paths.associateWith { File(it).exists() },
            )
        }

    fun availability(
        request: StudioFontRequest,
        resources: StudioFields,
        facts: StudioFontFacts?,
    ): FontAvailability {
        if (facts == null || facts.resources != resources) return FontAvailability.UNCONFIRMED
        if (request !is StudioFontRequest.Enabled) return FontAvailability.UNCONFIRMED
        val settings = request.settings
        if (!settings.preset.isCurated) {
            return if (facts.sees(settings.fontFamily)) FontAvailability.AVAILABLE else FontAvailability.MISSING_CUSTOM
        }
        return curated(settings, facts)
    }

    private fun curated(
        settings: FontSettings,
        facts: StudioFontFacts,
    ): FontAvailability {
        val aliases = settings.preset.fontAliases
        val recorded =
            requireNotNull(
                facts.resources.records
                    .single { it.key.name == "installedFonts" }
                    .value as? RawValue.OrderedSet,
            )
        val family = aliases.firstOrNull { it in recorded.values }
        val isVisible = aliases.any(facts::sees)
        if (family == null) return if (isVisible) FontAvailability.AVAILABLE else FontAvailability.MISSING_CURATED
        val paths = AyuIslandsState.decodeFontPaths(recordedFiles(facts.resources)[family])
        if (paths.any { it !in facts.files }) return FontAvailability.UNCONFIRMED
        if (paths.any { facts.files[it] == false } && !facts.sees(family)) return FontAvailability.CORRUPTED
        return if (isVisible) FontAvailability.AVAILABLE else FontAvailability.RESTART_REQUIRED
    }

    private fun recordedFiles(resources: StudioFields): Map<String, String> {
        val field = resources.records.single { it.key.name == "installedFontFiles" }
        return requireNotNull(field.value as? RawValue.OrderedMap).entries.associate {
            it.key to requireNotNull(it.value as? RawValue.Text).value
        }
    }
}

private fun StudioFontFacts.sees(family: String): Boolean = families.any { it.equals(family, ignoreCase = true) }
