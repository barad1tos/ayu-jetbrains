package dev.ayuislands.studio.persistence

import com.intellij.serialization.SerializationException
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.xml.stream.XMLStreamException

/** Cold legacy reads never instantiate a settings service or mutate its original files. */
internal class StudioLegacyCapture(
    private val optionsDirectory: Path,
    private val store: StudioAppearanceStore,
    private val nextId: () -> String,
    private val userHome: String,
) {
    fun capture(): LegacySnapshot {
        val archives = StudioList(LegacyStore.entries.map { LegacyArchive(it, readFile(it)) })
        val source = store.findSource(archives) ?: MigrationSource(nextId(), archives)
        return try {
            captureFields(source)
        } catch (_: SerializationException) {
            throw StoreDataException(StoreIssue.INVALID_DATA)
        } catch (_: IllegalArgumentException) {
            throw StoreDataException(StoreIssue.INVALID_DATA)
        }
    }

    private fun captureFields(source: MigrationSource): LegacySnapshot {
        val components = readComponents(source)
        val settings =
            components[LegacyStore.SETTINGS]?.let {
                XmlSerializer.deserialize(it.toElement(), AyuIslandsState::class.java)
            } ?: freshSettingsState()
        val syntax =
            components[LegacyStore.SYNTAX]?.let {
                XmlSerializer.deserialize(it.toElement(), SyntaxIntensityBaseState::class.java)
            } ?: SyntaxIntensityBaseState()
        val mappings =
            components[LegacyStore.MAPPINGS]?.let {
                XmlSerializer.deserialize(it.toElement(), AccentMappingsState::class.java)
            } ?: AccentMappingsState()
        expandProjectPaths(mappings)
        val origins = linkedMapOf<LegacyFieldKey, FieldOrigin>()
        val extensions = mutableListOf<OpaqueRecord>()
        components.forEach { (legacyStore, component) ->
            if (component != null) captureOrigins(legacyStore, component, origins, extensions)
        }
        val fields = StudioLegacyFields.capture(settings, syntax, mappings, origins)
        val fresh = StudioLegacyFields.capture(freshSettingsState(), SyntaxIntensityBaseState(), AccentMappingsState())
        return LegacySnapshot(
            source,
            fields.copy(extensions = StudioList(extensions)),
            StudioFactoryProfiles.create(fresh),
            transactionId = nextId(),
        )
    }

    private fun readComponents(source: MigrationSource): Map<LegacyStore, LegacyXmlElement?> =
        try {
            source.archives.associate { archive ->
                val component =
                    (archive.file as? LegacyFile.Present)?.let {
                        StudioLegacyXml.component(it.bytes.toByteArray(), componentNames.getValue(archive.store))
                    }
                archive.store to component
            }
        } catch (_: XMLStreamException) {
            throw StoreDataException(StoreIssue.INVALID_DATA)
        } catch (_: CharacterCodingException) {
            throw StoreDataException(StoreIssue.INVALID_DATA)
        }

    private fun captureOrigins(
        legacyStore: LegacyStore,
        component: LegacyXmlElement,
        origins: MutableMap<LegacyFieldKey, FieldOrigin>,
        extensions: MutableList<OpaqueRecord>,
    ) {
        val definitions = StudioFieldCatalog.fields.filter { it.source == legacyStore }.associateBy { it.xmlName }
        component.children.forEachIndexed { index, child ->
            val definition = definitions[child.node.attributes["name"]].takeIf { child.node.name == "option" }
            if (definition == null) {
                extensions.add(OpaqueRecord("legacy/${legacyStore.name}/$index", child.lexeme))
            } else {
                val key = LegacyFieldKey(legacyStore, definition.name)
                val presence =
                    if ("value" in child.node.attributes || child.children.isNotEmpty()) {
                        SourcePresence.VALUE
                    } else {
                        SourcePresence.NULL
                    }
                require(origins.put(key, FieldOrigin(presence, child.lexeme)) == null) { "Duplicate legacy option" }
            }
        }
    }

    private fun expandProjectPaths(mappings: AccentMappingsState) {
        val projectMaps =
            listOf(
                mappings.projectAccents,
                mappings.projectDisplayNames,
                mappings.projectFallbackAccents,
                mappings.forcedProjectLanguages,
            )
        val expanded = projectMaps.map(::expandKeys)
        projectMaps.zip(expanded).forEach { (original, replacement) ->
            original.clear()
            original.putAll(replacement)
        }
    }

    private fun expandKeys(values: Map<String, String>): Map<String, String> =
        buildMap {
            for ((key, value) in values) {
                val expanded =
                    if (key.startsWith(USER_HOME_MACRO)) {
                        if (userHome.isBlank()) throw StoreDataException(StoreIssue.CONFLICT)
                        userHome + key.removePrefix(USER_HOME_MACRO)
                    } else {
                        key
                    }
                if (containsKey(expanded)) throw StoreDataException(StoreIssue.CONFLICT)
                put(expanded, value)
            }
        }

    private fun readFile(legacyStore: LegacyStore): LegacyFile =
        try {
            val path = optionsDirectory.resolve(legacyFileNames.getValue(legacyStore))
            LegacyFile.Present(StudioBytes(Files.readAllBytes(path)))
        } catch (_: NoSuchFileException) {
            LegacyFile.Absent
        }

    companion object {
        private const val USER_HOME_MACRO = $$"$USER_HOME$"
        private val componentNames =
            mapOf(
                LegacyStore.SETTINGS to "AyuIslandsSettings",
                LegacyStore.SYNTAX to "AyuIslandsSyntaxIntensityState",
                LegacyStore.MAPPINGS to "AyuIslandsAccentMappings",
            )
    }
}
